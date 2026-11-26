import { mkdirSync, readFileSync, writeFileSync } from 'fs';
import { join } from 'path';

interface TaskSpec {
  id: string;
  category: string;
  expected_tool: string;
  prompt: string;
}

const OLLAMA_HOST = process.env.OLLAMA_HOST || 'http://lal:11434';
const MODEL = 'functiongemma';

const TOOLS = [
  {
    type: 'function',
    function: {
      name: 'discover_local_tool',
      description: 'Discover verified local Ubuntu CLI tools, package provenance, and flags before running unfamiliar or uncertain commands.',
      parameters: {
        type: 'object',
        properties: {
          intent: { type: 'string', description: 'Short task intent' },
          keyword: { type: 'string', description: 'Optional CLI command or keyword' },
        },
        required: ['intent'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'bash',
      description: 'Run a simple shell command directly when tool and exact flags are completely certain.',
      parameters: {
        type: 'object',
        properties: { command: { type: 'string' } },
        required: ['command'],
      },
    },
  },
];

async function main() {
  const tasks: TaskSpec[] = JSON.parse(readFileSync(join(import.meta.dir, 'tasks.json'), 'utf8'));
  const results: any[] = [];
  let psSnapshot: any = null;

  for (const task of tasks) {
    const t0 = performance.now();
    const resp = await fetch(`${OLLAMA_HOST}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: MODEL,
        messages: [
          {
            role: 'developer',
            content: 'You are a model that can do function calling with the following functions. Route CLI tasks either to discover_local_tool when tool availability or flags should be verified, or to bash.',
          },
          { role: 'user', content: task.prompt },
        ],
        tools: TOOLS,
        stream: false,
        options: { num_ctx: 4096, temperature: 0.1, seed: 42 },
      }),
    });
    const data: any = await resp.json();
    const ms = Math.round(performance.now() - t0);
    const tc = data?.message?.tool_calls?.[0]?.function ?? null;
    results.push({
      task_id: task.id,
      category: task.category,
      routed_tool: tc?.name ?? null,
      arguments: tc?.arguments ?? null,
      raw_content: tc ? null : String(data?.message?.content ?? '').slice(0, 120),
      prompt_tokens: data.prompt_eval_count ?? 0,
      completion_tokens: data.eval_count ?? 0,
      ms,
    });
    if (!psSnapshot) {
      try {
        psSnapshot = await (await fetch(`${OLLAMA_HOST}/api/ps`)).json();
      } catch {}
    }
  }

  const routedDiscover = results.filter(r => r.routed_tool === 'discover_local_tool').length;
  const routedBash = results.filter(r => r.routed_tool === 'bash').length;
  const noTool = results.filter(r => !r.routed_tool).length;
  const meanMs = +(results.reduce((s, r) => s + r.ms, 0) / results.length).toFixed(1);

  mkdirSync(join(import.meta.dir, 'results'), { recursive: true });
  const outPath = join(import.meta.dir, 'results', 'functiongemma_router.json');
  writeFileSync(outPath, JSON.stringify({
    meta: { model: MODEL, ollama_host: OLLAMA_HOST, ps_snapshot: psSnapshot },
    summary: { total: results.length, routed_discover: routedDiscover, routed_bash: routedBash, no_tool: noTool, mean_ms: meanMs },
    results,
  }, null, 2));
  console.log('functiongemma router summary:', { total: results.length, routedDiscover, routedBash, noTool, meanMs });
}

main();
