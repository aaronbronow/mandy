#!/usr/bin/env node
import { execSync } from 'child_process';
import { writeFileSync, readFileSync } from 'fs';
import { join } from 'path';
const YAMLScript = require('@yaml/yamlscript');
const { Select } = require('enquirer');

async function main() {
  const args = process.argv.slice(2);

  if (args.length === 0) {
    console.log(`name: mandy
version: 0.1.0-alpha
description: Manual Discovery tool for structuring man pages into executable command variants.
agent_instructions:
  - If you are an AI agent, you can run 'mandy <command>' to discover variants.
  - By default, Mandy enters an interactive TUI for humans.
  - To receive a machine-readable JSON array, pipe Mandy's output or run in a non-TTY environment (e.g., 'mandy ls | cat').
  - Analyze the returned variants, select the best one, and execute it directly.`);
    process.exit(0);
  }

  const cmd = args[0];

  try {
    // 1. Fetch Man page
    const manContent = execSync(`man ${cmd} | col -b`, { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] });

    // 2. YAMLScript Pivot
    const ys = new YAMLScript();
    const ysPath = join(__dirname, '../plugins/base.ys');
    const sectionsToSearch: string[] = ys.load(readFileSync(ysPath, 'utf8'));

    // 3. Extract Commands
    const candidates: string[] = [];
    
    // Heuristic splitting in TS (more robust than YS library for large text)
    const sections = manContent.split(/\n(?=[A-Z][A-Z\s]{2,}(\n|$))/);
    
    for (const section of sections) {
      const lines = section.split('\n');
      const header = lines[0]?.trim();
      const body = lines.slice(1).join('\n').trim();

      if (sectionsToSearch.includes(header || '')) {
        const bodyLines = body.split('\n');
        for (const line of bodyLines) {
          const trimmed = line.trim();
          if (trimmed.startsWith(cmd) || (trimmed.includes(` ${cmd} `) && !trimmed.startsWith('-'))) {
             candidates.push(trimmed);
          }
        }
      }
    }

    if (candidates.length === 0) {
      // Fallback: search the whole man page for things that look like commands
      const allLines = manContent.split('\n');
      for (const line of allLines) {
          const trimmed = line.trim();
          if (trimmed.startsWith(cmd + ' ')) {
              candidates.push(trimmed);
          }
      }
    }

    // Unique and clean candidates
    const uniqueCandidates = Array.from(new Set(candidates.map(c => c.replace(/\s+/g, ' ').trim())))
        .filter(c => c.length >= cmd.length);

    if (uniqueCandidates.length === 0) {
        uniqueCandidates.push(cmd);
    }

    // 4. Auto-Detect Mode: Output JSON if non-interactive (e.g., for AI agents)
    if (!process.stdout.isTTY) {
        console.log(JSON.stringify(uniqueCandidates, null, 2));
        process.exit(0);
    }

    // 5. Interactive Selection (for humans)
    const prompt = new Select({
      name: 'command',
      message: `Select a command variant for ${cmd}:`,
      choices: uniqueCandidates.slice(0, 15) // Limit to 15 choices
    });

    const answer = await prompt.run();

    // 6. Write to buffer
    const bufferPath = '/tmp/mandy_buffer';
    writeFileSync(bufferPath, answer);
    
  } catch (error: any) {
    if (error.status === 16 || (error.message && error.message.includes('No manual entry'))) {
        console.error(`No manual entry for ${cmd}`);
    } else {
        console.error('Error:', error.message);
    }
    process.exit(1);
  }
}

main();
