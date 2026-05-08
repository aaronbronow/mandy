import { execSync } from 'child_process';
import { writeFileSync, readFileSync } from 'fs';
import { join } from 'path';
const YAMLScript = require('@yaml/yamlscript');
const { Select } = require('enquirer');

async function main() {
  const args = process.argv.slice(2);

  if (args.length === 0) {
    console.log('Mandy Alpha 0.1');
    console.log('Usage: mandy <command>');
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

    // 4. Interactive Selection
    const prompt = new Select({
      name: 'command',
      message: `Select a command variant for ${cmd}:`,
      choices: uniqueCandidates.slice(0, 15) // Limit to 15 choices
    });

    const answer = await prompt.run();

    // 5. Write to buffer
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
