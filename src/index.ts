#!/usr/bin/env node
import { execSync } from 'child_process';
import { writeFileSync, readFileSync } from 'fs';
import { join } from 'path';
// @ts-ignore
import { terminal as term } from 'terminal-kit';
import * as yaml from 'js-yaml';

interface LineMap {
    originalIndex: number;
    text: string;
}

interface Token {
    text: string;
    line: number;      // Original Man line index
    startCol: number; // Original Man start column
    yamlLine: number;  // YAML view line index
}

enum FocusArea {
    TEXT_AREA = 'TEXT_AREA',
    BUILDER_BAR = 'BUILDER_BAR'
}

enum ViewMode {
    MAN = 'MAN',
    YAML = 'YAML'
}

async function main() {
    const args = process.argv.slice(2);

    if (args.length === 0) {
      console.log(`name: mandy
    version: 0.1.0-alpha
    description: Manual Discovery tool for structuring man pages into executable command variants.
    usage: mandy [options] <command>
    options:
    --vim, -v    Enable VIM look and feel
    --debug, -d  Output raw YAMLScript and exit
    --strip, -s  Strip formatting (tabs, extra whitespace, empty rows)
    agent_instructions:
    - If you are an AI agent, you can run 'mandy <command>' to discover variants.
    - By default, Mandy enters an interactive TUI for humans.
    - To receive a machine-readable JSON array, pipe Mandy's output or run in a non-TTY environment (e.g., 'mandy ls | cat').
    - Analyze the returned variants, select the best one, and execute it directly.`);
      process.exit(0);
    }

    const isVimMode = args.includes('--vim') || args.includes('-v') || process.env.MANDY_VIM === '1';
    const isDebugMode = args.includes('--debug') || args.includes('-d');
    const isStripMode = args.includes('--strip') || args.includes('-s');
    const cmd = args.filter(a => !a.startsWith('-'))[0];

    if (!cmd) {
      console.error('Error: No command specified.');
      process.exit(1);
    }

    try {
        // 1. Fetch Man page (Model A: The Visuals)
        const manContentRaw = execSync(`man ${cmd} | col -b`, { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] });
        const unstrippedLines = manContentRaw.split('\n');

        // 2. Generate the Source Map (Bridge)
        const sourceMap: LineMap[] = [];
        for (let i = 0; i < unstrippedLines.length; i++) {
            const original = unstrippedLines[i]!;
            const sanitized = original.replace(/\s+/g, ' ').trim();
            if (sanitized !== '') {
                sourceMap.push({ originalIndex: i, text: sanitized });
            }
        }

        // 3. Structured Parsing (Source of Truth)
        // Model B: Unsanitized Structured Document (Preserves all lines/formatting)
        const structuredUnsanitized: any[] = unstrippedLines.reduce((acc: any[], line, i) => {
            const trimmed = line.trim();
            const lm: LineMap = { originalIndex: i, text: line };
            if (/^[A-Z][A-Z\s]{2,}$/.test(trimmed)) {
                acc.push({ [trimmed]: [] });
            } else {
                const last = acc[acc.length - 1];
                const key = Object.keys(last)[0]!;
                last[key].push(lm);
            }
            return acc;
        }, [{ "PREAMBLE": [] }]);

        // Model C: Sanitized Structured Document (For Token Extraction)
        const structuredSanitized: any[] = sourceMap.reduce((acc: any[], lineMap) => {
            const text = lineMap.text;
            if (/^[A-Z][A-Z\s]{2,}$/.test(text)) {
                acc.push({ [text]: [] });
            } else {
                const last = acc[acc.length - 1];
                const key = Object.keys(last)[0]!;
                last[key].push(lineMap);
            }
            return acc;
        }, [{ "PREAMBLE": [] }]);

        // Generate High-Fidelity YAML with YAML Source Map
        const yamlLines: string[] = ["!yamlscript/v0/data"];
        const originalLineToYamlIndex = new Map<number, number>();

        for (const section of structuredUnsanitized) {
            const key = Object.keys(section)[0]!;
            const sectionLineMaps = section[key] as LineMap[];
            yamlLines.push(`- ${key}:`);
            for (const lm of sectionLineMaps) {
                originalLineToYamlIndex.set(lm.originalIndex, yamlLines.length);
                // Basic YAML string escaping for the lines
                const escaped = lm.text.replace(/'/g, "''");
                yamlLines.push(`    - '${escaped}'`);
            }
        }
        
        if (isDebugMode) {
            console.log(yamlLines.join('\n'));
            process.exit(0);
        }

        // 4. Token Extraction via Source Map (Operating on Model C)
        const extractedTokens: Token[] = [];
        const flagExtractRegex = /(?:^|\s|,)(-{1,2}[a-zA-Z0-9-]+)/g;

        for (const section of structuredSanitized) {
            const key = Object.keys(section)[0]!;
            const sectionLineMaps = section[key] as LineMap[];
            
            if (key !== 'SYNOPSIS' && key !== 'PREAMBLE') {
                for (const lm of sectionLineMaps) {
                    if (lm.text.startsWith('-')) {
                        let match;
                        while ((match = flagExtractRegex.exec(lm.text)) !== null) {
                            const flagText = match[1]!;
                            const originalLine = unstrippedLines[lm.originalIndex]!;
                            const originalCol = originalLine.indexOf(flagText);
                            if (originalCol !== -1) {
                                extractedTokens.push({
                                    text: flagText,
                                    line: lm.originalIndex,
                                    startCol: originalCol,
                                    yamlLine: originalLineToYamlIndex.get(lm.originalIndex) || -1
                                });
                            }
                        }
                    }
                }
            }
        }

        const uniqueTokens = extractedTokens.sort((a, b) => a.line - b.line || a.startCol - b.startCol)
            .filter((t, i, arr) => !i || (t.line !== arr[i-1]!.line || t.startCol !== arr[i-1]!.startCol));

        // 5. Auto-Detect Mode
        if (!process.stdout.isTTY) {
            console.log(JSON.stringify(Array.from(new Set(uniqueTokens.map(t => t.text))), null, 2));
            process.exit(0);
        }

        // 6. Terminal-Kit TUI Implementation
        let activeIndex = 0;
        let offsetY = 0;
        let yamlOffsetY = 0;
        let builtCommand = `${cmd} `;
        let focusArea = FocusArea.TEXT_AREA;
        let viewMode = ViewMode.MAN;

        const terminate = (selected?: string) => {
            term.grabInput(false);
            term.hideCursor(false);
            term.styleReset();
            term.clear();
            if (selected) {
                const bufferPath = '/tmp/mandy_buffer';
                writeFileSync(bufferPath, selected.trim());
            }
            process.exit(0);
        };

        const render = () => {
            const width = term.width;
            const height = term.height - 3;

            const currentLines = viewMode === ViewMode.MAN ? unstrippedLines : yamlLines;
            let currentOffset = viewMode === ViewMode.MAN ? offsetY : yamlOffsetY;

            if (currentOffset < 0) currentOffset = 0;
            if (currentOffset > currentLines.length - height) currentOffset = currentLines.length - height;
            if (currentOffset < 0) currentOffset = 0;
            
            if (viewMode === ViewMode.MAN) offsetY = currentOffset;
            else yamlOffsetY = currentOffset;

            for (let y = 0; y < height; y++) {
                const lineIdx = y + currentOffset;
                term.moveTo(1, y + 1);

                if (lineIdx >= currentLines.length) {
                    term.eraseLine();
                    continue;
                }

                const line = currentLines[lineIdx]!;

                // Token Logic for both views
                const lineTokens = uniqueTokens.filter(t => (viewMode === ViewMode.MAN ? t.line : t.yamlLine) === lineIdx);
                if (lineTokens.length > 0) {
                    let lastCol = 0;
                    term.eraseLine();
                    for (const t of lineTokens) {
                        // Find where the token text is in the current line
                        // In YAML view, it might be wrapped in quotes
                        const displayIdx = line.indexOf(t.text, lastCol);
                        if (displayIdx !== -1) {
                            term(line.substring(lastCol, displayIdx));
                            const isPrimary = uniqueTokens.indexOf(t) === activeIndex && focusArea === FocusArea.TEXT_AREA;
                            if (isPrimary) {
                                term.bgGreen.black(t.text);
                            } else {
                                term.bgBlue.white(t.text);
                            }
                            lastCol = displayIdx + t.text.length;
                        }
                    }
                    term(line.substring(lastCol, lastCol + (width - lastCol)));
                } else {
                    term.eraseLine();
                    if (viewMode === ViewMode.YAML) {
                        const trimmed = line.trim();
                        if (trimmed.startsWith('!yamlscript')) term.magenta(line.substring(0, width));
                        else if (trimmed.startsWith('- ')) term.cyan('- ').white(line.substring(line.indexOf('- ') + 2, width));
                        else if (line.includes(':')) {
                            const colonIdx = line.indexOf(':');
                            term.green(line.substring(0, colonIdx + 1)).white(line.substring(colonIdx + 1, width));
                        } else term.white(line.substring(0, width));
                    } else {
                        term.white(line.substring(0, width));
                    }
                }
            }

            const statusLineY = term.height - 2;
            term.moveTo(1, statusLineY);
            if (isVimMode) {
                const percent = currentLines.length > height ? Math.round(((currentOffset + height) / currentLines.length) * 100) : 100;
                const posStr = `${currentOffset + 1}L, ${currentLines.length}C`;
                const modeStr = viewMode === ViewMode.MAN ? '[MAN]' : '[YAML]';
                term.bgGreen.black.eraseLine(` "${cmd}" ${modeStr} ${posStr} --${percent > 100 ? 100 : percent}%--`);
            } else {
                const toggleKey = viewMode === ViewMode.MAN ? 'Y: YAML' : 'M: MAN';
                term.bgWhite.black.eraseLine(` Mandy: ${cmd} | Tab: Cycle | Enter: Add | ${toggleKey} | ^X: Exit `);
            }

            const builderBarY = term.height - 1;
            term.moveTo(1, builderBarY);
            if (focusArea === FocusArea.BUILDER_BAR) {
                term.bgYellow.black.eraseLine(` > ${builtCommand}`);
                term.moveTo(builtCommand.length + 4, builderBarY);
            } else {
                term.bgBlack.white.eraseLine(` > ${builtCommand}`);
            }
        };

        const scrollIntoView = () => {
            const activeT = uniqueTokens[activeIndex]!;
            const targetLine = viewMode === ViewMode.MAN ? activeT.line : activeT.yamlLine;
            const height = term.height - 3;
            let currentOffset = viewMode === ViewMode.MAN ? offsetY : yamlOffsetY;

            if (targetLine < currentOffset) {
                currentOffset = targetLine;
            } else if (targetLine >= currentOffset + height) {
                currentOffset = targetLine - height + 1;
            }

            if (viewMode === ViewMode.MAN) offsetY = currentOffset;
            else yamlOffsetY = currentOffset;
        };

        term.fullscreen(true);
        term.hideCursor(true);
        term.grabInput({ mouse: 'button' });

        const removeLastToken = () => {
            const parts = builtCommand.trim().split(' ');
            if (parts.length > 1) {
                parts.pop();
                builtCommand = parts.join(' ') + ' ';
            } else {
                builtCommand = `${cmd} `;
            }
            render();
        };

        term.on('key', (name: string) => {
            if (focusArea === FocusArea.TEXT_AREA) {
                switch (name) {
                    case 'CTRL_C':
                    case 'CTRL_X':
                    case 'ESC':
                    case 'q':
                    case 'Q':
                        terminate();
                        break;
                    case 'BACKSPACE':
                    case 'DELETE':
                        removeLastToken();
                        break;
                    case 'UP':
                    case 'k':
                        if (viewMode === ViewMode.MAN) offsetY--; else yamlOffsetY--;
                        render();
                        break;
                    case 'DOWN':
                    case 'j':
                        if (viewMode === ViewMode.MAN) offsetY++; else yamlOffsetY++;
                        render();
                        break;
                    case 'PAGE_UP':
                    case 'CTRL_U':
                        if (viewMode === ViewMode.MAN) offsetY -= Math.floor(term.height / 2); else yamlOffsetY -= Math.floor(term.height / 2);
                        render();
                        break;
                    case 'PAGE_DOWN':
                    case 'CTRL_D':
                        if (viewMode === ViewMode.MAN) offsetY += Math.floor(term.height / 2); else yamlOffsetY += Math.floor(term.height / 2);
                        render();
                        break;
                    case 'g':
                        if (viewMode === ViewMode.MAN) offsetY = 0; else yamlOffsetY = 0;
                        render();
                        break;
                    case 'G':
                        const currentLines = viewMode === ViewMode.MAN ? unstrippedLines : yamlLines;
                        if (viewMode === ViewMode.MAN) offsetY = currentLines.length; else yamlOffsetY = currentLines.length;
                        render();
                        break;
                    case 'y':
                    case 'Y':
                    case 'm':
                    case 'M':
                        viewMode = viewMode === ViewMode.MAN ? ViewMode.YAML : ViewMode.MAN;
                        scrollIntoView();
                        render();
                        break;
                    case 'TAB':
                        activeIndex = (activeIndex + 1) % uniqueTokens.length;
                        scrollIntoView();
                        render();
                        break;
                    case 'SHIFT_TAB':
                        activeIndex = (activeIndex - 1 + uniqueTokens.length) % uniqueTokens.length;
                        scrollIntoView();
                        render();
                        break;
                    case 'ENTER':
                        if (uniqueTokens.length > 0) {
                            builtCommand += uniqueTokens[activeIndex]!.text + ' ';
                            focusArea = FocusArea.BUILDER_BAR;
                        }
                        render();
                        break;
                }
            } else if (focusArea === FocusArea.BUILDER_BAR) {
                switch (name) {
                    case 'ENTER':
                        terminate(builtCommand);
                        break;
                    case 'BACKSPACE':
                    case 'DELETE':
                        removeLastToken();
                        break;
                    case 'UP':
                    case 'ESC':
                    case 'TAB':
                    case 'SHIFT_TAB':
                        focusArea = FocusArea.TEXT_AREA;
                        render();
                        break;
                    case 'CTRL_C':
                    case 'CTRL_X':
                    case 'q':
                    case 'Q':
                        terminate();
                        break;
                    case 'y':
                    case 'Y':
                    case 'm':
                    case 'M':
                        viewMode = viewMode === ViewMode.MAN ? ViewMode.YAML : ViewMode.MAN;
                        scrollIntoView();
                        render();
                        break;
                }
            }
        });

        term.on('mouse', (name: string, data: any) => {
            if (name === 'MOUSE_LEFT_BUTTON_PRESSED') {
                if (data.y === term.height - 1) {
                    focusArea = FocusArea.BUILDER_BAR;
                    render();
                } else {
                    const currentOffset = viewMode === ViewMode.MAN ? offsetY : yamlOffsetY;
                    const clickedLine = data.y + currentOffset - 1;
                    const match = uniqueTokens.find(t => (viewMode === ViewMode.MAN ? t.line : t.yamlLine) === clickedLine);
                    if (match) {
                        activeIndex = uniqueTokens.indexOf(match);
                        builtCommand += match.text + ' ';
                        focusArea = FocusArea.BUILDER_BAR;
                        render();
                    }
                }
            }
        });

        term.on('terminal_resize', () => {
            render();
        });

        render();

    } catch (error: any) {
        term.fullscreen(false);
        console.error('Error:', error.message);
        process.exit(1);
    }
}

main();
