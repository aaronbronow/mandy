#!/usr/bin/env node
import { execSync } from 'child_process';
import { writeFileSync, readFileSync, unlinkSync } from 'fs';
import { join } from 'path';
// @ts-ignore
import { terminal as term } from 'terminal-kit';
import * as yaml from 'js-yaml';

interface Token {
    text: string;
    line: number;
    startCol: number;
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
        // 1. Fetch Man page
        const manContentRaw = execSync(`man ${cmd} | col -b`, { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] });
        const unstrippedLines = manContentRaw.split('\n');
        
        let processedLines = unstrippedLines;
        if (isStripMode) {
            processedLines = processedLines
                .map(l => l.replace(/\s+/g, ' ').trim())
                .filter(l => l !== '');
        }

        // 2. Structured Parsing (Source of Truth)
        const structured: any[] = processedLines.reduce((acc: any[], line) => {
            const trimmed = line.trim();
            if (/^[A-Z][A-Z\s]{2,}$/.test(trimmed)) {
                acc.push({ [trimmed]: [] });
            } else {
                const last = acc[acc.length - 1];
                const key = Object.keys(last)[0]!;
                last[key].push(line);
            }
            return acc;
        }, [{ "PREAMBLE": [] }]);

        // Generate high-fidelity YAML string
        let yamlContent = "!yamlscript/v0/data\n" + yaml.dump(structured, { noRefs: true, lineWidth: -1 });
        
        if (isDebugMode) {
            console.log(yamlContent);
            process.exit(0);
        }

        const yamlLines = yamlContent.split('\n');

        // 3. Extract Valid Tokens from Structured Data
        const validFlags = new Set<string>();
        
        const flagExtractRegex = /(?:^|\s|,)(-{1,2}[a-zA-Z0-9-]+)/g;

        for (const section of structured) {
            const key = Object.keys(section)[0]!;
            const sectionLines = section[key] as string[];
            
            if (key !== 'SYNOPSIS') {
                for (const line of sectionLines) {
                    const trimmed = line.trim();
                    if (trimmed.startsWith('-')) {
                        let matches;
                        while ((matches = flagExtractRegex.exec(trimmed)) !== null) {
                            validFlags.add(matches[1]!);
                        }
                    }
                }
            }
        }

        // 4. Map Tokens to the Unstripped UI Lines
        const lines = unstrippedLines; // UI always shows the formatted manual
        const tokens: Token[] = [];
        let currentHeader = '';

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i]!;
            const trimmed = line.trim();

            if (/^[A-Z][A-Z\s]{2,}$/.test(trimmed)) {
                currentHeader = trimmed;
                continue;
            }

            // Skip tokens in SYNOPSIS for this version
            if (currentHeader !== 'SYNOPSIS') {
                // Highlight flags only where they are defined (line starts with '-')
                if (trimmed.startsWith('-')) {
                    for (const flag of validFlags) {
                        // Match flag as a distinct word to avoid partial matches
                        const regex = new RegExp(`(^|\\s|,)(${flag})(?=[ \\t\\n=,\\[]|$)`, 'g');
                        let match;
                        while ((match = regex.exec(line)) !== null) {
                            const text = match[2]!;
                            const index = line.indexOf(text, match.index);
                            tokens.push({ text, line: i, startCol: index });
                        }
                    }
                }
            }
        }

        const uniqueTokens: Token[] = [];
        const seenPos = new Set<string>();
        for (const t of tokens) {
            const key = `${t.line}:${t.startCol}`;
            if (!seenPos.has(key)) {
                uniqueTokens.push(t);
                seenPos.add(key);
            }
        }
        uniqueTokens.sort((a, b) => a.line - b.line || a.startCol - b.startCol);

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

            const currentLines = viewMode === ViewMode.MAN ? lines : yamlLines;
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

                if (viewMode === ViewMode.MAN) {
                    const lineTokens = uniqueTokens.filter(t => t.line === lineIdx);
                    if (lineTokens.length > 0) {
                        let lastCol = 0;
                        term.eraseLine();
                        for (const t of lineTokens) {
                            term(line.substring(lastCol, t.startCol));
                            const isPrimary = uniqueTokens.indexOf(t) === activeIndex && focusArea === FocusArea.TEXT_AREA;
                            if (isPrimary) {
                                term.bgGreen.black(line.substring(t.startCol, t.startCol + t.text.length));
                            } else {
                                term.bgBlue.white(line.substring(t.startCol, t.startCol + t.text.length));
                            }
                            lastCol = t.startCol + t.text.length;
                        }
                        term(line.substring(lastCol, lastCol + (width - lastCol)));
                    } else {
                        term.eraseLine();
                        term(line.substring(0, width));
                    }
                } else {
                    // YAML View: High-contrast syntax highlighting
                    term.eraseLine();
                    const trimmed = line.trim();
                    if (trimmed.startsWith('!yamlscript')) {
                        term.magenta(line.substring(0, width));
                    } else if (trimmed.startsWith('- ')) {
                        term.cyan('- ').white(line.substring(line.indexOf('- ') + 2, width));
                    } else if (line.includes(':')) {
                        const colonIdx = line.indexOf(':');
                        term.green(line.substring(0, colonIdx + 1)).white(line.substring(colonIdx + 1, width));
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
                const copyHint = viewMode === ViewMode.YAML ? ' | Shift+Drag to Copy' : '';
                term.bgWhite.black.eraseLine(` Mandy: ${cmd} | Tab: Cycle | Enter: Add | ${toggleKey}${copyHint} | ^X: Exit `);
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
            if (viewMode !== ViewMode.MAN) return;
            const activeT = uniqueTokens[activeIndex]!;
            const height = term.height - 3;
            if (activeT.line < offsetY) {
                offsetY = activeT.line;
            } else if (activeT.line >= offsetY + height) {
                offsetY = activeT.line - height + 1;
            }
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
                        if (viewMode === ViewMode.MAN) offsetY = lines.length; else yamlOffsetY = yamlLines.length;
                        render();
                        break;
                    case 'y':
                    case 'Y':
                    case 'm':
                    case 'M':
                        viewMode = viewMode === ViewMode.MAN ? ViewMode.YAML : ViewMode.MAN;
                        render();
                        break;
                    case 'TAB':
                        if (viewMode === ViewMode.MAN && uniqueTokens.length > 0) {
                            activeIndex = (activeIndex + 1) % uniqueTokens.length;
                            scrollIntoView();
                        }
                        render();
                        break;
                    case 'SHIFT_TAB':
                        if (viewMode === ViewMode.MAN && uniqueTokens.length > 0) {
                            activeIndex = (activeIndex - 1 + uniqueTokens.length) % uniqueTokens.length;
                            scrollIntoView();
                        }
                        render();
                        break;
                    case 'ENTER':
                        if (viewMode === ViewMode.MAN && uniqueTokens.length > 0) {
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
                } else if (viewMode === ViewMode.MAN) {
                    const clickedLine = data.y + offsetY - 1;
                    const match = uniqueTokens.find(t => t.line === clickedLine);
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
