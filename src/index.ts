#!/usr/bin/env node
import { execSync } from 'child_process';
import { writeFileSync, readFileSync, openSync, fsyncSync, closeSync } from 'fs';
import { join } from 'path';
// @ts-ignore
import * as termKit from 'terminal-kit';
import * as yaml from 'js-yaml';

const term = termKit.createTerminal({
    stdin: process.stdin,
    stdout: process.stdout,
    stderr: process.stderr,
    name: process.env.TERM || 'xterm-256color'
});

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

    // Read the pre-parsed payload from the Clojure wrapper via temporary file
    const payloadPath = process.env.MANDY_PAYLOAD_PATH;
    if (!payloadPath) {
        console.error('Error: MANDY_PAYLOAD_PATH not set.');
        process.exit(1);
    }
    
    const inputPayload = readFileSync(payloadPath, 'utf8');
    if (!inputPayload || inputPayload.trim() === '') {
        console.error('Error: No input data received from wrapper.');
        process.exit(1);
    }

    const payload = JSON.parse(inputPayload);
    const structuredData = payload.structuredData;
    const sanitizedYaml = payload.sanitizedYaml;
    const cmd = payload.cmd;

    const isDebugMode = args.includes('--debug') || args.includes('-d');
    const isStripMode = args.includes('--strip') || args.includes('-s');

    try {
        // 1. Reconstruct Models from Payload
        const unstrippedLines: string[] = [];
        const yamlLines: string[] = ["!yamlscript/v0/data"];
        const originalLineToYamlIndex = new Map<number, number>();

        for (const section of structuredData) {
            const [key, sectionLineMaps] = Object.entries(section)[0] as [string, LineMap[]];
            yamlLines.push(`- ${key}:`);
            for (const lm of sectionLineMaps) {
                unstrippedLines[lm.originalIndex] = lm.text;
                originalLineToYamlIndex.set(lm.originalIndex, yamlLines.length);
                const escaped = lm.text.replace(/'/g, "''");
                yamlLines.push(`    - '${escaped}'`);
            }
        }

        // Fill any gaps in unstrippedLines with empty strings (though unlikely)
        for (let i = 0; i < unstrippedLines.length; i++) {
            if (unstrippedLines[i] === undefined) unstrippedLines[i] = "";
        }

        // 2. Token Extraction via structuredData
        const extractedTokens: Token[] = [];
        const flagExtractRegex = /(?:^|\s|,)(-{1,2}[a-zA-Z0-9-]+)/g;

        for (const section of structuredData) {
            const [key, sectionLineMaps] = Object.entries(section)[0] as [string, LineMap[]];
            
            if (key !== 'SYNOPSIS' && key !== 'PREFACE') {
                for (const lm of sectionLineMaps) {
                    const sanitizedText = lm.text.replace(/\s+/g, ' ').trim();
                    let match;
                    while ((match = flagExtractRegex.exec(sanitizedText)) !== null) {
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

        const uniqueTokens = extractedTokens.sort((a, b) => a.line - b.line || a.startCol - b.startCol)
            .filter((t, i, arr) => !i || (t.line !== arr[i-1]!.line || t.startCol !== arr[i-1]!.startCol));

        // 3. Mode Handling (The Wrapper already handles context, but we respect flags)
        if (isDebugMode) {
            console.log(yamlLines.join('\n'));
            process.exit(0);
        }

        if (isStripMode) {
            console.log(sanitizedYaml);
            process.exit(0);
        }

        // Auto-Detect Mode (If stdout is piped, just print tokens)
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
                const fd = openSync(bufferPath, 'w');
                writeFileSync(fd, selected.trim());
                fsyncSync(fd);
                closeSync(fd);
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
            term.bgWhite.black.eraseLine(` Mandy: ${cmd} | Tab: Cycle | Enter: Add | V: View | Q: Exit `);

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
                    case 'v':
                    case 'V':
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
                    const match = uniqueTokens.find(t => {
                        const isCorrectLine = (viewMode === ViewMode.MAN ? t.line : t.yamlLine) === clickedLine;
                        if (!isCorrectLine) return false;
                        
                        // X-coordinate check (Visual column mapping)
                        const currentLines = viewMode === ViewMode.MAN ? unstrippedLines : yamlLines;
                        const line = currentLines[clickedLine];
                        if (!line) return false;
                        
                        const tokenIdx = line.indexOf(t.text);
                        if (tokenIdx === -1) return false;
                        
                        // terminal-kit uses 1-based coordinates
                        return data.x >= tokenIdx + 1 && data.x < tokenIdx + 1 + t.text.length;
                    });
                    
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
