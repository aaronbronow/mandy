#!/usr/bin/env node
import { execSync } from 'child_process';
import { writeFileSync, readFileSync } from 'fs';
import { join } from 'path';
// @ts-ignore
import { terminal as term } from 'terminal-kit';
const YAMLScript = require('@yaml/yamlscript');

interface Token {
    text: string;
    line: number;
    startCol: number;
}

enum FocusArea {
    TEXT_AREA = 'TEXT_AREA',
    BUILDER_BAR = 'BUILDER_BAR'
}

async function main() {
    const args = process.argv.slice(2);

    if (args.length === 0) {
      console.log(`name: mandy
    version: 0.1.0-alpha
    description: Manual Discovery tool for structuring man pages into executable command variants.
    usage: mandy [options] <command>
    options:
    --vim, -v  Enable VIM look and feel
    agent_instructions:
    - If you are an AI agent, you can run 'mandy <command>' to discover variants.
    - By default, Mandy enters an interactive TUI for humans.
    - To receive a machine-readable JSON array, pipe Mandy's output or run in a non-TTY environment (e.g., 'mandy ls | cat').
    - Analyze the returned variants, select the best one, and execute it directly.`);
      process.exit(0);
    }

    const isVimMode = args.includes('--vim') || args.includes('-v') || process.env.MANDY_VIM === '1';
    const cmd = args.filter(a => !a.startsWith('-'))[0];

    if (!cmd) {
      console.error('Error: No command specified.');
      process.exit(1);
    }

    try {
        // 1. Fetch Man page
        const manContent = execSync(`man ${cmd} | col -b`, { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] });

        // 2. YAMLScript Pivot for strategy
        const ys = new YAMLScript();
        const ysPath = join(__dirname, '../plugins/base.ys');
        const sectionsToSearch: string[] = ys.load(readFileSync(ysPath, 'utf8'));

        // 3. Extract Tokens and Man Content Lines
        const lines = manContent.split('\n');
        const tokens: Token[] = [];
        
        const flagRegex = /(?:\s|^)(-{1,2}[a-zA-Z0-9-]+)(?=[ \t\n=,\[]|$)/g;
        const synopsisRegex = /\[[A-Z0-9_\-]+\]|<[A-Z0-9_\-]+>|[A-Z][A-Z0-9_\-]{2,}/g;

        let currentHeader = '';
        for (let i = 0; i < lines.length; i++) {
            const line = lines[i]!;
            const trimmed = line.trim();

            // Detect section headers
            if (/^[A-Z][A-Z\s]{2,}$/.test(trimmed)) {
                currentHeader = trimmed;
                continue;
            }

            // Extract tokens based on section
            let matches;
            if (currentHeader === 'SYNOPSIS') {
                while ((matches = synopsisRegex.exec(line)) !== null) {
                    tokens.push({ text: matches[0], line: i, startCol: matches.index });
                }
            } else if (sectionsToSearch.includes(currentHeader) || currentHeader === 'DESCRIPTION' || currentHeader === 'OPTIONS') {
                while ((matches = flagRegex.exec(line)) !== null) {
                    const text = matches[1]!;
                    const index = line.indexOf(text, matches.index);
                    tokens.push({ text, line: i, startCol: index });
                }
            }
        }

        // Deduplicate and sort tokens
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

        // 4. Auto-Detect Mode
        if (!process.stdout.isTTY) {
            console.log(JSON.stringify(Array.from(new Set(uniqueTokens.map(t => t.text))), null, 2));
            process.exit(0);
        }

        // 5. Terminal-Kit TUI Implementation
        if (uniqueTokens.length === 0) {
            console.log(`No tokens found for ${cmd}. Check the man page structure.`);
            process.exit(0);
        }

        let activeIndex = 0;
        let offsetY = 0;
        let builtCommand = `${cmd} `;
        let focusArea = FocusArea.TEXT_AREA;

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

            // Clamp offsetY
            if (offsetY < 0) offsetY = 0;
            if (offsetY > lines.length - height) offsetY = lines.length - height;
            if (offsetY < 0) offsetY = 0;

            for (let y = 0; y < height; y++) {
                const lineIdx = y + offsetY;
                term.moveTo(1, y + 1);

                if (lineIdx >= lines.length) {
                    term.eraseLine();
                    continue;
                }

                const line = lines[lineIdx]!;

                const lineTokens = uniqueTokens.filter(t => t.line === lineIdx);
                if (lineTokens.length > 0) {
                    let lastCol = 0;
                    term.eraseLine(); // Clear existing content on this line
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
            }

            // Status Bar
            const statusLineY = term.height - 2;
            term.moveTo(1, statusLineY);
            if (isVimMode) {
                const percent = lines.length > height ? Math.round(((offsetY + height) / lines.length) * 100) : 100;
                const posStr = `${offsetY + 1}L, ${lines.length}C`;
                term.bgGreen.black.eraseLine(` "${cmd}" [RO] ${posStr} --${percent > 100 ? 100 : percent}%--`);
            } else {
                term.bgWhite.black.eraseLine(` Mandy: ${cmd} | Tab: Cycle | Enter: Add to Bar | ^X: Exit `);
            }

            // Command Builder Bar
            const builderBarY = term.height - 1;
            term.moveTo(1, builderBarY);
            if (focusArea === FocusArea.BUILDER_BAR) {
                term.bgYellow.black.eraseLine(` > ${builtCommand}`);
                // Move cursor to end of command for visibility
                term.moveTo(builtCommand.length + 4, builderBarY);
            } else {
                term.bgBlack.white.eraseLine(` > ${builtCommand}`);
            }
        };

        const scrollIntoView = () => {
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
                    case 'UP':
                    case 'k':
                        offsetY--;
                        render();
                        break;
                    case 'DOWN':
                    case 'j':
                        offsetY++;
                        render();
                        break;
                    case 'PAGE_UP':
                    case 'CTRL_U':
                        offsetY -= Math.floor(term.height / 2);
                        render();
                        break;
                    case 'PAGE_DOWN':
                    case 'CTRL_D':
                        offsetY += Math.floor(term.height / 2);
                        render();
                        break;
                    case 'g':
                        offsetY = 0;
                        render();
                        break;
                    case 'G':
                        offsetY = lines.length;
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
                        builtCommand += uniqueTokens[activeIndex]!.text + ' ';
                        focusArea = FocusArea.BUILDER_BAR;
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
                        const parts = builtCommand.trim().split(' ');
                        if (parts.length > 1) {
                            parts.pop();
                            builtCommand = parts.join(' ') + ' ';
                        } else {
                            builtCommand = `${cmd} `;
                        }
                        render();
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
