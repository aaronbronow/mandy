#!/usr/bin/env node
import { writeFileSync, readFileSync, openSync, fsyncSync, closeSync } from 'fs';
import { join } from 'path';
// @ts-ignore
import * as termKit from 'terminal-kit';
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
    BUILDER_BAR = 'BUILDER_BAR',
    SEARCH_BAR = 'SEARCH_BAR'
}

enum ViewMode {
    MAN = 'MAN',
    YAML = 'YAML'
}

async function main() {
    const term = termKit.createTerminal({
        stdin: process.stdin,
        stdout: process.stdout,
        stderr: process.stderr,
        name: process.env.TERM || 'xterm-256color'
    });
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

        let searchBuffer = '';
        let searchMatches: { line: number, col: number }[] = [];
        let searchMatchIndex = 0;
        let isFreezeMode = false;

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
                term.eraseLine();

                // 1. Identify all features (tokens and search matches) on this line
                const lineTokens = uniqueTokens.filter(t => (viewMode === ViewMode.MAN ? t.line : t.yamlLine) === lineIdx);
                const lineMatches = searchMatches.filter(m => m.line === lineIdx);

                const features: { start: number, end: number, type: 'TOKEN' | 'SEARCH', index: number }[] = [];
                for (const t of lineTokens) {
                    const start = line.indexOf(t.text);
                    if (start !== -1) features.push({ start, end: start + t.text.length, type: 'TOKEN', index: uniqueTokens.indexOf(t) });
                }
                for (const m of lineMatches) {
                    features.push({ start: m.col, end: m.col + searchBuffer.length, type: 'SEARCH', index: searchMatches.indexOf(m) });
                }

                // 2. Fragment the line into segments based on feature boundaries
                const boundaries = new Set<number>([0, line.length]);
                for (const f of features) {
                    boundaries.add(f.start);
                    boundaries.add(f.end);
                }
                const sortedBoundaries = Array.from(boundaries).sort((a, b) => a - b);

                // 3. Render each segment with the highest priority feature
                for (let i = 0; i < sortedBoundaries.length - 1; i++) {
                    const start = sortedBoundaries[i];
                    const end = sortedBoundaries[i+1];
                    const text = line.substring(start, end);
                    if (text === "") continue;

                    // Find all features that cover this segment
                    const segmentFeatures = features.filter(f => f.start <= start && f.end >= end);
                    
                    // Priority: SEARCH (current > others) > TOKEN (current > others)
                    const topFeature = segmentFeatures.sort((a, b) => {
                        if (a.type !== b.type) return a.type === 'SEARCH' ? -1 : 1;
                        if (a.type === 'SEARCH') {
                            const aIsCurrent = a.index === searchMatchIndex;
                            const bIsCurrent = b.index === searchMatchIndex;
                            if (aIsCurrent !== bIsCurrent) return aIsCurrent ? -1 : 1;
                        } else {
                            const aIsActive = a.index === activeIndex;
                            const bIsActive = b.index === activeIndex;
                            if (aIsActive !== bIsActive) return aIsActive ? -1 : 1;
                        }
                        return 0;
                    })[0];

                    if (topFeature) {
                        if (topFeature.type === 'TOKEN') {
                            const isPrimary = topFeature.index === activeIndex && focusArea === FocusArea.TEXT_AREA;
                            if (isPrimary) term.bgGreen.black(text);
                            else term.bgBlue.white(text);
                        } else {
                            const isCurrent = topFeature.index === searchMatchIndex && (focusArea === FocusArea.SEARCH_BAR || isFreezeMode);
                            if (isCurrent) term.bgYellow.black(text);
                            else term.bgWhite.black(text);
                        }
                    } else {
                        // Apply default styling (YAML colorization etc.)
                        if (viewMode === ViewMode.YAML) {
                            const trimmed = line.trim();
                            if (trimmed.startsWith('!yamlscript')) term.magenta(text);
                            else if (trimmed.startsWith('- ')) {
                                if (start < line.indexOf('- ') + 2) term.cyan(text);
                                else term.white(text);
                            } else if (line.includes(':') && start <= line.indexOf(':')) {
                                term.green(text);
                            } else term.white(text);
                        } else {
                            term.white(text);
                        }
                    }
                }
                
                // Ensure the rest of the line is erased if needed (though term.eraseLine was called)
                term.white(""); 
            }

            const statusLineY = term.height - 2;
            term.moveTo(1, statusLineY);
            let statusText = ` Mandy: ${cmd} | /: Search | Tab: Cycle | Enter: Add | V: View | Q: Exit `;
            if (isFreezeMode) {
                statusText = ` FREEZE: ${searchMatches.length} matches | n/p: Cycle | Enter: Snap | Esc: Cancel `;
            }
            term.bgWhite.black.eraseLine(statusText);

            const builderBarY = term.height - 1;
            term.moveTo(1, builderBarY);
            if (focusArea === FocusArea.SEARCH_BAR) {
                term.bgCyan.black.eraseLine(` /${searchBuffer}`);
                term.moveTo(searchBuffer.length + 3, builderBarY);
                term.hideCursor(false);
            } else if (focusArea === FocusArea.BUILDER_BAR) {
                term.bgYellow.black.eraseLine(` > ${builtCommand}`);
                term.moveTo(builtCommand.length + 4, builderBarY);
                term.hideCursor(false);
            } else {
                term.bgBlack.white.eraseLine(` > ${builtCommand}`);
                term.hideCursor(true);
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

        const updateActiveTokenFromMatch = () => {
            if (searchMatches.length === 0) return;
            const match = searchMatches[searchMatchIndex];
            let bestTokenIdx = -1;
            let minDistance = Infinity;

            for (let i = 0; i < uniqueTokens.length; i++) {
                const t = uniqueTokens[i];
                const tLine = viewMode === ViewMode.MAN ? t.line : t.yamlLine;
                if (tLine === -1) continue;

                const dy = match.line - tLine;
                const dx = match.col - t.startCol;

                // Distance formula: vertical has higher weight.
                // We want smallest POSITIVE distance (match line >= token line)
                const distance = dy * 100 + dx;
                
                // If distance is negative, token is AFTER the match. 
                // We penalize tokens after the match heavily.
                const score = distance >= 0 ? distance : 1000000 + Math.abs(distance);

                if (score < minDistance) {
                    minDistance = score;
                    bestTokenIdx = i;
                }
            }

            if (bestTokenIdx !== -1) {
                activeIndex = bestTokenIdx;
            }
        };

        const scrollToLineCentered = (lineIdx: number) => {
            const height = term.height - 3;
            let newOffset = lineIdx - Math.floor(height / 2);
            
            const currentLines = viewMode === ViewMode.MAN ? unstrippedLines : yamlLines;
            if (newOffset > currentLines.length - height) newOffset = currentLines.length - height;
            if (newOffset < 0) newOffset = 0;
            
            if (viewMode === ViewMode.MAN) offsetY = newOffset;
            else yamlOffsetY = newOffset;
        };

        const performSearch = () => {
            searchMatches = [];
            if (searchBuffer.length === 0) return;

            const lines = viewMode === ViewMode.MAN ? unstrippedLines : yamlLines;
            const searchStr = searchBuffer.toLowerCase();
            for (let i = 0; i < lines.length; i++) {
                const lineText = lines[i].toLowerCase();
                let col = -1;
                while ((col = lineText.indexOf(searchStr, col + 1)) !== -1) {
                    searchMatches.push({ line: i, col });
                }
            }

            if (searchMatches.length > 0) {
                searchMatchIndex = 0;
                updateActiveTokenFromMatch();
                scrollToLineCentered(searchMatches[0].line);
            }
        };

        const handleKey = (name: string) => {
            if (focusArea === FocusArea.TEXT_AREA) {
                if (isFreezeMode) {
                    switch (name) {
                        case 'n':
                            searchMatchIndex = (searchMatchIndex + 1) % searchMatches.length;
                            updateActiveTokenFromMatch();
                            scrollToLineCentered(searchMatches[searchMatchIndex].line);
                            render();
                            return;
                        case 'p':
                            searchMatchIndex = (searchMatchIndex - 1 + searchMatches.length) % searchMatches.length;
                            updateActiveTokenFromMatch();
                            scrollToLineCentered(searchMatches[searchMatchIndex].line);
                            render();
                            return;
                        case 'ESC':
                            isFreezeMode = false;
                            searchBuffer = '';
                            searchMatches = [];
                            render();
                            return;
                        case 'ENTER':
                            if (uniqueTokens.length > 0) {
                                builtCommand += uniqueTokens[activeIndex]!.text + ' ';
                                focusArea = FocusArea.BUILDER_BAR;
                            }
                            isFreezeMode = false;
                            searchBuffer = '';
                            searchMatches = [];
                            render();
                            return;
                    }
                }

                switch (name) {
                    case 'CTRL_C':
                    case 'CTRL_X':
                    case 'ESC':
                    case 'q':
                    case 'Q':
                        terminate();
                        break;
                    case '/':
                        focusArea = FocusArea.SEARCH_BAR;
                        render();
                        break;
                    case 'CTRL_O':
                    case ':':
                        focusArea = FocusArea.BUILDER_BAR;
                        render();
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
                    case ' ':
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
                    case 'n':
                        if (isFreezeMode) { isFreezeMode = false; searchBuffer = ''; searchMatches = []; }
                        activeIndex = (activeIndex + 1) % uniqueTokens.length;
                        scrollIntoView();
                        render();
                        break;
                    case 'SHIFT_TAB':
                    case 'p':
                        if (isFreezeMode) { isFreezeMode = false; searchBuffer = ''; searchMatches = []; }
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
                    case 'TAB':
                        focusArea = FocusArea.TEXT_AREA;
                        render();
                        break;
                    case 'SHIFT_TAB':
                        focusArea = FocusArea.TEXT_AREA;
                        render();
                        break;
                    case 'ESC':
                        focusArea = FocusArea.TEXT_AREA;
                        render();
                        break;
                    case '/':
                        focusArea = FocusArea.SEARCH_BAR;
                        render();
                        break;
                    case 'CTRL_C':
                    case 'CTRL_X':
                    case 'q':
                    case 'Q':
                        terminate();
                        break;
                    }
                    } else if (focusArea === FocusArea.SEARCH_BAR) {
                    switch (name) {
                    case 'ENTER':
                        if (searchBuffer.length > 0 && searchMatches.length > 0) {
                            isFreezeMode = true;
                        }
                        focusArea = FocusArea.TEXT_AREA;
                        render();
                        break;
                    case 'ESC':
                        searchBuffer = '';
                        searchMatches = [];
                        focusArea = FocusArea.TEXT_AREA;
                        render();
                        break;
                    case 'BACKSPACE':
                    case 'DELETE':
                        searchBuffer = searchBuffer.slice(0, -1);
                        performSearch();
                        render();
                        break;
                    case 'CTRL_C':
                        terminate();
                        break;
                    default:
                        if (name.length === 1) {
                            searchBuffer += name;
                            performSearch();
                            render();
                        }
                        break;
                    }
                    }
                    };

        term.on('key', handleKey);

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

        // Automated Testing Mode
        const testKeys = process.env.MANDY_TEST_KEYS;
        if (testKeys) {
            const keys = testKeys.split(',');
            for (const key of keys) {
                handleKey(key.trim());
            }
        }

    } catch (error: any) {
        term.fullscreen(false);
        console.error('Error:', error.message);
        process.exit(1);
    }
}

main();
