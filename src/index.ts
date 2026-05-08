#!/usr/bin/env node
import { execSync } from 'child_process';
import { writeFileSync, readFileSync } from 'fs';
import { join } from 'path';
// @ts-ignore
import { terminal as term } from 'terminal-kit';
const YAMLScript = require('@yaml/yamlscript');

interface Highlight {
    text: string;
    line: number;
    startCol: number;
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

        // 3. Extract Commands and Man Content Lines
        const lines = manContent.split('\n');
        const highlights: Highlight[] = [];
        const sections = manContent.split(/\n(?=[A-Z][A-Z\s]{2,}(\n|$))/);
        
        // We still use the heuristic but record coordinates
        let currentLineOffset = 0;
        for (const section of sections) {
            const sectionLines = section.split('\n');
            const header = sectionLines[0]?.trim();
            const bodyLines = sectionLines.slice(1);

            if (sectionsToSearch.includes(header || '')) {
                for (let i = 0; i < bodyLines.length; i++) {
                    const line = bodyLines[i]!;
                    const trimmed = line.trim();
                    if (trimmed.startsWith(cmd) || (trimmed.includes(` ${cmd} `) && !trimmed.startsWith('-'))) {
                        highlights.push({
                            text: trimmed.replace(/\s+/g, ' ').trim(),
                            line: currentLineOffset + 1 + i,
                            startCol: line.indexOf(trimmed)
                        });
                    }
                }
            }
            currentLineOffset += sectionLines.length;
        }

        // Fallback: search whole man page if no synopsis/examples found
        if (highlights.length === 0) {
            for (let i = 0; i < lines.length; i++) {
                const line = lines[i]!;
                const trimmed = line.trim();
                if (trimmed.startsWith(cmd + ' ')) {
                    highlights.push({
                        text: trimmed.replace(/\s+/g, ' ').trim(),
                        line: i,
                        startCol: line.indexOf(trimmed)
                    });
                }
            }
        }

        // Deduplicate highlights by text
        const uniqueHighlights: Highlight[] = [];
        const seen = new Set<string>();
        for (const h of highlights) {
            if (!seen.has(h.text)) {
                uniqueHighlights.push(h);
                seen.add(h.text);
            }
        }

        // 4. Auto-Detect Mode
        if (!process.stdout.isTTY) {
            console.log(JSON.stringify(uniqueHighlights.map(h => h.text), null, 2));
            process.exit(0);
        }

        // 5. Terminal-Kit TUI Implementation
        if (uniqueHighlights.length === 0) {
            console.log(`No clear examples or synopsis found for ${cmd}.`);
            process.exit(0);
        }

        let activeIndex = 0;
        let offsetY = 0;

        const terminate = (selected?: string) => {
            term.grabInput(false);
            term.hideCursor(false);
            term.styleReset();
            term.clear();
            if (selected) {
                const bufferPath = '/tmp/mandy_buffer';
                writeFileSync(bufferPath, selected);
            }
            process.exit(0);
        };

        const render = () => {
            term.clear();
            const width = term.width;
            const height = term.height - 2; // Reserve space for instructions

            // Clamp offsetY
            if (offsetY < 0) offsetY = 0;
            if (offsetY > lines.length - height) offsetY = lines.length - height;
            if (offsetY < 0) offsetY = 0; // if lines.length < height

            for (let y = 0; y < height; y++) {
                const lineIdx = y + offsetY;
                if (lineIdx >= lines.length) break;

                const line = lines[lineIdx]!;
                term.moveTo(1, y + 1);

                // Check for highlights on this line
                const lineHighlights = uniqueHighlights.filter(h => h.line === lineIdx);
                if (lineHighlights.length > 0) {
                    let lastCol = 0;
                    lineHighlights.sort((a, b) => a.startCol - b.startCol);
                    for (const h of lineHighlights) {
                        term(line.substring(lastCol, h.startCol));
                        const isPrimary = uniqueHighlights.indexOf(h) === activeIndex;
                        if (isPrimary) {
                            term.bgGreen.black(line.substring(h.startCol, h.startCol + h.text.length));
                        } else {
                            term.bgBlue.white(line.substring(h.startCol, h.startCol + h.text.length));
                        }
                        lastCol = h.startCol + h.text.length;
                    }
                    term(line.substring(lastCol));
                } else {
                    term(line.substring(0, width));
                }
            }

      // Footer
      if (isVimMode) {
        const percent = lines.length > height ? Math.round(((offsetY + height) / lines.length) * 100) : 100;
        const posStr = `${offsetY + 1}L, ${lines.length}C`;
        term.moveTo(1, term.height - 1).bgGreen.black.eraseLine(` "${cmd}" [RO] ${posStr} --${percent > 100 ? 100 : percent}%--`);
        const activeH = uniqueHighlights[activeIndex]!;
        term.moveTo(1, term.height).eraseLine(` Variant ${activeIndex + 1}/${uniqueHighlights.length}: ${activeH.text.substring(0, width - 5)}`);
      } else {
        term.moveTo(1, term.height - 1).bgWhite.black.eraseLine(` Mandy: ${cmd} | Tab: Next | S-Tab: Prev | Enter: Select | Click to Select | ^X Exit `);
        const activeH = uniqueHighlights[activeIndex]!;
        term.moveTo(1, term.height).italic.dim(` Variant ${activeIndex + 1}/${uniqueHighlights.length}: ${activeH.text.substring(0, width - 5)}... `);
      }
    };

        const scrollIntoView = () => {
            const activeH = uniqueHighlights[activeIndex]!;
            const height = term.height - 2;
            if (activeH.line < offsetY) {
                offsetY = activeH.line;
            } else if (activeH.line >= offsetY + height) {
                offsetY = activeH.line - height + 1;
            }
        };

        term.fullscreen(true);
        term.hideCursor(true);
        term.grabInput({ mouse: 'button' });

        term.on('key', (name: string) => {
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
                    activeIndex = (activeIndex + 1) % uniqueHighlights.length;
                    scrollIntoView();
                    render();
                    break;
                case 'SHIFT_TAB':
                    activeIndex = (activeIndex - 1 + uniqueHighlights.length) % uniqueHighlights.length;
                    scrollIntoView();
                    render();
                    break;
                case 'ENTER':
                    terminate(uniqueHighlights[activeIndex]?.text);
                    break;
            }
        });

        term.on('mouse', (name: string, data: any) => {
            if (name === 'MOUSE_LEFT_BUTTON_PRESSED') {
                const clickedLine = data.y + offsetY - 1;
                const match = uniqueHighlights.find(h => h.line === clickedLine);
                if (match) {
                    activeIndex = uniqueHighlights.indexOf(match);
                    render();
                    // Optional: double click or just click to select? 
                    // Let's go with click to select for maximum "fun".
                    terminate(match.text);
                }
            }
        });

        term.on('terminal_resize', () => {
            render();
        });

        render();

    } catch (error: any) {
        term.fullscreen(false);
        if (error.status === 16 || (error.message && error.message.includes('No manual entry'))) {
            console.error(`No manual entry for ${cmd}`);
        } else {
            console.error('Error:', error.message);
        }
        process.exit(1);
    }
}

main();
