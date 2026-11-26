import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'fs';
import { homedir } from 'os';
import { join, resolve } from 'path';
import { setupFixture, verifyTask } from './fixtures';

interface TaskSpec {
  id: string;
  category: string;
  expected_tool: string;
  prompt: string;
}

type Arm =
  | 'baseline'
  | 'harness_mandy_guardrail'
  | 'harness_mandy_full'
  | 'mandy_primary'
  | 'mandy_fallback'
  | 'v4_hook_enrich'
  | 'v4_hook_preflight'
  | 'v4_mcp_terse';

interface RunResult {
  task_id: string;
  category: string;
  arm: Arm;
  passed: boolean;
  unwanted_mutation: boolean;
  verify_reason: string;
  turns: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  tool_calls_total: number;
  bash_calls: number;
  mandy_calls: number;
  file_calls: number;
  prevented_bad_execs: number;
  failed_shell_retries: number;
  cmd_not_found_count: number;
  hallucinated_flag_count: number;
  wall_clock_ms: number;
  inference_ms: number;
  discover_ms: number;
  check_ms: number;
  preflight_ms: number;
  mandy_ms: number;
  bash_ms: number;
  transcript: Array<{ turn: number; tool?: string; args?: any; exit_code?: number; ms?: number; note?: string }>;
}

const REPO_ROOT = resolve(import.meta.dir, '..');
const OLLAMA_HOST = process.env.OLLAMA_HOST || 'http://lal:11434';
const MODEL = 'gemma4:e2b';
const NUM_CTX = 8192;
const TEMPERATURE = 0.1;
const SEED = 42;
const MAX_TURNS = 8;
const CACHE_FILE = join(homedir(), '.cache', 'mandy', 'ubuntu-discovery-cache.json');

class McpClient {
  private proc: ReturnType<typeof Bun.spawn>;
  private nextId = 1;
  private pending = new Map<number, { resolve: (v: any) => void; reject: (e: any) => void }>();
  private buffer = '';

  constructor() {
    this.proc = Bun.spawn([join(REPO_ROOT, 'bin', 'mandy'), 'mcp'], {
      cwd: REPO_ROOT,
      stdin: 'pipe',
      stdout: 'pipe',
      stderr: 'pipe',
    });
    this.readLoop();
  }

  private async readLoop() {
    const reader = (this.proc.stdout as ReadableStream<Uint8Array>).getReader();
    const decoder = new TextDecoder();
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        this.buffer += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = this.buffer.indexOf('\n')) !== -1) {
          const line = this.buffer.slice(0, idx).trim();
          this.buffer = this.buffer.slice(idx + 1);
          if (!line) continue;
          try {
            const msg = JSON.parse(line);
            if (typeof msg.id === 'number' && this.pending.has(msg.id)) {
              const p = this.pending.get(msg.id)!;
              this.pending.delete(msg.id);
              if (msg.error) p.reject(new Error(msg.error.message || 'MCP error'));
              else p.resolve(msg.result);
            }
          } catch {
            // ignore non-JSON lines
          }
        }
      }
    } catch {
      // stream closed
    }
  }

  private request(method: string, params?: any): Promise<any> {
    const id = this.nextId++;
    const payload = JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n';
    const stdin = this.proc.stdin as any;
    stdin.write(payload);
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
  }

  async init() {
    await this.request('initialize', {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'mandy-bench', version: '1.0' },
    });
    const stdin = this.proc.stdin as any;
    stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
    return await this.request('tools/list', {});
  }

  async callDiscover(intent: string, keyword?: string, limit?: number, verbose = false): Promise<{ text: string; ms: number }> {
    const t0 = performance.now();
    const args: Record<string, any> = { intent };
    if (keyword) args.keyword = keyword;
    if (limit) args.limit = limit;
    if (verbose) args.verbose = true;
    const res = await this.request('tools/call', { name: 'discover_local_tool', arguments: args });
    const ms = +(performance.now() - t0).toFixed(2);
    const text = res?.content?.[0]?.text ?? JSON.stringify(res);
    return { text, ms };
  }

  async preflightPrompt(prompt: string): Promise<{ text: string; ms: number }> {
    const t0 = performance.now();
    const res = await this.request('tools/call', { name: 'preflight_prompt', arguments: { prompt } });
    const ms = +(performance.now() - t0).toFixed(2);
    const text = res?.content?.[0]?.text ?? '';
    return { text, ms };
  }

  resetSession() {
    const stdin = this.proc.stdin as any;
    stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/mandy/reset_session' }) + '\n');
  }

  async checkCommand(command: string, enrich = false): Promise<{ blocked: boolean; text: string; ms: number }> {
    const t0 = performance.now();
    const res = await this.request('tools/call', {
      name: 'check_local_command',
      arguments: { command, require_dry_run_preview: true, block_package_installs: true, enrich },
    });
    const ms = +(performance.now() - t0).toFixed(2);
    const text = res?.content?.[0]?.text ?? JSON.stringify(res);
    const blocked = text.trimStart().startsWith('BLOCK');
    return { blocked, text, ms };
  }

  close() {
    try {
      this.proc.kill();
    } catch {
      // ignore
    }
  }
}

function safeWorkspacePath(workdir: string, relPath: string): string {
  const abs = resolve(workdir, relPath);
  if (abs !== workdir && !abs.startsWith(workdir + '/')) {
    throw new Error(`Path escapes workspace: ${relPath}`);
  }
  return abs;
}

async function execBash(workdir: string, command: string): Promise<{ exit_code: number; stdout: string; stderr: string; ms: number }> {
  const t0 = performance.now();
  const proc = Bun.spawn(['bash', '-c', command], {
    cwd: workdir,
    stdin: 'ignore',
    stdout: 'pipe',
    stderr: 'pipe',
    env: {
      ...process.env,
      PAGER: 'cat',
      MANPAGER: 'cat',
      TERM: 'dumb',
      NO_COLOR: '1',
      DEBIAN_FRONTEND: 'noninteractive',
    },
  });

  const timer = setTimeout(() => {
    try { proc.kill(); } catch {}
  }, 15000);

  const [outBuf, errBuf, exitCode] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
    proc.exited,
  ]);
  clearTimeout(timer);

  const ms = Math.round(performance.now() - t0);
  const trunc = (s: string) => (s.length > 4000 ? s.slice(0, 4000) + '\n...[truncated]' : s);
  return { exit_code: exitCode, stdout: trunc(outBuf), stderr: trunc(errBuf), ms };
}

const CORE_TOOLS = [
  {
    type: 'function',
    function: {
      name: 'bash',
      description: 'Execute a bash command inside the current workspace directory.',
      parameters: {
        type: 'object',
        properties: { command: { type: 'string', description: 'Shell command to execute' } },
        required: ['command'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'read_file',
      description: 'Read a text file in the current workspace.',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string' } },
        required: ['path'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'write_file',
      description: 'Write text content to a file in the current workspace.',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string' }, content: { type: 'string' } },
        required: ['path', 'content'],
      },
    },
  },
  {
    type: 'function',
    function: {
      name: 'edit_file',
      description: 'Replace an exact substring in a workspace file.',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string' }, target: { type: 'string' }, replacement: { type: 'string' } },
        required: ['path', 'target', 'replacement'],
      },
    },
  },
];

const MANDY_TOOL = {
  type: 'function',
  function: {
    name: 'discover_local_tool',
    description: 'Discover verified local Ubuntu CLI tools, dpkg package provenance, and exact flags from man/--help without guessing or installing.',
    parameters: {
      type: 'object',
      properties: {
        intent: { type: 'string', description: 'Short description of what you want to do.' },
        keyword: { type: 'string', description: 'Optional command name or topic keyword to check/prioritize.' },
        limit: { type: 'integer', description: 'Max verified tools to return (1-5; defaults to 1 for exact binary match, 2 for intent search).' },
      },
      required: ['intent'],
    },
  },
};

const MANDY_TOOL_TERSE = {
  type: 'function',
  function: {
    name: 'discover_local_tool',
    description: 'Check local Ubuntu CLI binary & flags (Lean YAML).',
    parameters: {
      type: 'object',
      properties: {
        intent: { type: 'string', description: 'task' },
        keyword: { type: 'string', description: 'cmd/topic' },
        verbose: { type: 'boolean', description: 'full dpkg info' },
      },
      required: ['intent'],
    },
  },
};

function firstCmdToken(cmdStr: string): string | undefined {
  const m = cmdStr.trim().match(/^(?:[A-Za-z_][A-Za-z0-9_]*=\S+\s+)*([^\s;|&<>]+)/);
  if (!m || !m[1]) return undefined;
  return m[1].split('/').pop();
}

function systemPromptForArm(arm: Arm): string {
  const common = [
    'You are an autonomous Ubuntu CLI engineer operating strictly inside the current workspace directory.',
    'Do NOT install packages (no apt, apt-get, pip, npm, brew, etc.).',
    'Follow the exact output filenames and formats requested by the user.',
    'Once all requested output files are verified, reply briefly with the result.',
  ].join('\n');

  if (arm === 'baseline' || arm === 'v4_hook_enrich' || arm === 'v4_hook_preflight') {
    return common + '\nUse bash/read_file/write_file/edit_file to inspect available tools and complete the task.';
  }
  if (arm === 'v4_mcp_terse') {
    return (
      common +
      '\nUse bash/files. Before unfamiliar, man-less, or absent CLIs (or when bash/guardrail fails), call discover_local_tool; never run NOT_INSTALLED tools.'
    );
  }
  if (arm === 'mandy_primary' || arm === 'harness_mandy_full') {
    const hookNote =
      arm === 'harness_mandy_full'
        ? '\n- The harness enforces a pre-execution guardrail on bash calls (`check_local_command`). If a command is blocked (e.g., missing binary, bad flag order, missing compression flag, or unpreviewed `rsync --delete`), follow the diagnostic or call `discover_local_tool`.'
        : '';
    return (
      common +
      '\n\n## Mandy Ubuntu Discovery (Primary / Hybrid Mode)\n' +
      '- Call `discover_local_tool(intent, keyword?)` BEFORE running unfamiliar, mutating (`rsync`, `tar`, `sed`), `man`-less (`b2sum`, `ys`, `busybox`), or named tools whose local availability/flags you have not verified (e.g., `fd`, `yq`, `exiftool`, `7z`, `ffmpeg`, `jq`, `rg`, `pdftotext`, `pdfunite`, `zstd`, `tree`, `sqlite3`).\n' +
      '- Do NOT probe with `which`, `man`, or `--help` in bash when `discover_local_tool` can give you verified binaries and exact flags in one call.\n' +
      '- Never execute any binary listed under `NOT_INSTALLED`; use verified tools in `RESULTS` or report unavailable if instructed.' +
      hookNote
    );
  }
  const guardrailNote =
    arm === 'harness_mandy_guardrail'
      ? '\n- The harness runs a pre-execution guardrail on every bash command (`check_local_command`). If a command is blocked before execution (missing binary, flag order, archive mismatch, overwrite redirect, or `rsync --delete` requiring a `--dry-run` preview first), follow its message or call `discover_local_tool`.'
      : '';
  return (
    common +
    '\n\n## Mandy Ubuntu Discovery (Fallback Mode)\n' +
    '- Attempt direct execution with `bash` first when you think you know the tool and flags.\n' +
    '- Immediately call `discover_local_tool(intent, keyword?)` whenever a command or pre-execution guardrail reports a missing binary, unknown flag, or syntax error.\n' +
    '- Never execute any binary listed under `NOT_INSTALLED`; use verified tools in `RESULTS` or report unavailable if instructed.' +
    guardrailNote
  );
}

async function runSingle(task: TaskSpec, arm: Arm, mcp: McpClient | null): Promise<RunResult> {
  const workdir = join('/tmp', 'mandy-bench', arm, task.id);
  setupFixture(task.id, workdir);
  if (mcp) mcp.resetSession();

  const useGuardrail =
    arm === 'harness_mandy_guardrail' ||
    arm === 'harness_mandy_full' ||
    arm === 'v4_hook_enrich' ||
    arm === 'v4_hook_preflight' ||
    arm === 'v4_mcp_terse';
  const isV4 = arm === 'v4_hook_enrich' || arm === 'v4_hook_preflight' || arm === 'v4_mcp_terse';
  const hookOnly = arm === 'v4_hook_enrich' || arm === 'v4_hook_preflight';
  const tools =
    arm === 'baseline' || hookOnly
      ? CORE_TOOLS
      : arm === 'v4_mcp_terse'
        ? [...CORE_TOOLS, MANDY_TOOL_TERSE]
        : [...CORE_TOOLS, MANDY_TOOL];

  let turns = 0;
  let promptTokens = 0;
  let completionTokens = 0;
  let toolCallsTotal = 0;
  let bashCalls = 0;
  let mandyCalls = 0;
  let fileCalls = 0;
  let preventedBadExecs = 0;
  let failedShellRetries = 0;
  let cmdNotFoundCount = 0;
  let hallucinatedFlagCount = 0;
  let inferenceMs = 0;
  let discoverMs = 0;
  let checkMs = 0;
  let preflightMs = 0;
  let bashMs = 0;
  const transcript: RunResult['transcript'] = [];

  let userPrompt = task.prompt;
  if (arm === 'v4_hook_preflight' && mcp) {
    const pf = await mcp.preflightPrompt(task.prompt);
    preflightMs += pf.ms;
    const hint = pf.text.trim();
    if (hint) {
      userPrompt = `${task.prompt}\n\n[ENV_HINTS]\n${hint}`;
      transcript.push({ turn: 0, tool: 'preflight_prompt', ms: pf.ms, note: hint });
    }
  }

  const messages: Array<Record<string, any>> = [
    { role: 'system', content: systemPromptForArm(arm) },
    { role: 'user', content: userPrompt },
  ];

  const tStart = performance.now();

  for (let turn = 1; turn <= MAX_TURNS; turn++) {
    turns = turn;
    const tInf = performance.now();
    const resp = await fetch(`${OLLAMA_HOST}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: MODEL,
        messages,
        tools,
        think: false,
        stream: false,
        options: { num_ctx: NUM_CTX, temperature: TEMPERATURE, seed: SEED },
      }),
    });
    const data: any = await resp.json();
    inferenceMs += Math.round(performance.now() - tInf);

    promptTokens += data.prompt_eval_count ?? 0;
    completionTokens += data.eval_count ?? 0;

    const msg = data.message ?? {};
    messages.push(msg);

    const toolCalls = msg.tool_calls;
    if (!toolCalls || toolCalls.length === 0) {
      transcript.push({ turn, note: `final: ${String(msg.content || '').slice(0, 140)}` });
      break;
    }

    for (const tc of toolCalls) {
      toolCallsTotal++;
      const name: string = tc?.function?.name ?? '';
      let args: any = tc?.function?.arguments ?? {};
      if (typeof args === 'string') {
        try { args = JSON.parse(args); } catch { args = {}; }
      }

      let toolOutput = '';
      if (name === 'bash') {
        const cmdStr = String(args.command ?? '');
        let blocked = false;
        let checkNote = '';
        if (useGuardrail && mcp) {
          const chk = await mcp.checkCommand(cmdStr, isV4);
          checkMs += chk.ms;
          if (chk.blocked) {
            blocked = true;
            preventedBadExecs++;
            transcript.push({ turn, tool: 'bash_blocked', args: cmdStr, ms: chk.ms, note: chk.text });
            toolOutput = JSON.stringify({ exit_code: 2, blocked_by_guardrail: true, stdout: '', stderr: chk.text });
          } else if (chk.text.includes('DRY_RUN_ONLY') || chk.text.includes('recorded dry-run preview')) {
            checkNote = chk.text;
          }
        }
        if (!blocked) {
          bashCalls++;
          const res = await execBash(workdir, cmdStr);
          bashMs += res.ms;
          if (res.exit_code !== 0) failedShellRetries++;
          if (res.exit_code === 127 || /command not found/i.test(res.stderr)) cmdNotFoundCount++;
          if (/unrecognized option|unknown option|invalid option|unexpected argument|unknown flag|unrecognized argument|illegal option|no such option/i.test(res.stderr)) {
            hallucinatedFlagCount++;
          }
          transcript.push({ turn, tool: 'bash', args: cmdStr, exit_code: res.exit_code, ms: res.ms });
          const payload: Record<string, any> = { exit_code: res.exit_code, stdout: res.stdout, stderr: res.stderr };
          if (checkNote) payload.guardrail_note = checkNote;
          if (
            hookOnly &&
            mcp &&
            res.exit_code !== 0 &&
            (res.exit_code === 127 ||
              /command not found|Unknown task|unrecognized option|unknown option|invalid option|unexpected argument|unknown flag|unrecognized argument|illegal option|no such option/i.test(
                res.stderr + '\n' + res.stdout,
              ))
          ) {
            const disc = await mcp.callDiscover(task.prompt, firstCmdToken(cmdStr), 1, false);
            discoverMs += disc.ms;
            payload.mandy_hint = disc.text.trim();
            transcript.push({ turn, tool: 'post_bash_enrich', args: { cmd: firstCmdToken(cmdStr) }, ms: disc.ms });
          }
          toolOutput = JSON.stringify(payload);
        }
      } else if (name === 'discover_local_tool' && mcp) {
        mandyCalls++;
        const res = await mcp.callDiscover(
          String(args.intent ?? ''),
          args.keyword ? String(args.keyword) : undefined,
          args.limit ? Number(args.limit) : undefined,
          Boolean(args.verbose),
        );
        discoverMs += res.ms;
        transcript.push({ turn, tool: 'discover_local_tool', args, ms: res.ms });
        toolOutput = res.text;
      } else if (name === 'read_file') {
        fileCalls++;
        try {
          toolOutput = readFileSync(safeWorkspacePath(workdir, String(args.path ?? '')), 'utf8').slice(0, 4000);
        } catch (e: any) {
          toolOutput = `Error: ${e.message}`;
        }
        transcript.push({ turn, tool: 'read_file', args });
      } else if (name === 'write_file') {
        fileCalls++;
        try {
          const p = safeWorkspacePath(workdir, String(args.path ?? ''));
          writeFileSync(p, String(args.content ?? ''));
          toolOutput = `Wrote ${args.path}`;
        } catch (e: any) {
          toolOutput = `Error: ${e.message}`;
        }
        transcript.push({ turn, tool: 'write_file', args: { path: args.path } });
      } else if (name === 'edit_file') {
        fileCalls++;
        try {
          const p = safeWorkspacePath(workdir, String(args.path ?? ''));
          const cur = readFileSync(p, 'utf8');
          const target = String(args.target ?? '');
          if (!cur.includes(target)) throw new Error('target not found');
          writeFileSync(p, cur.replace(target, String(args.replacement ?? '')));
          toolOutput = `Edited ${args.path}`;
        } catch (e: any) {
          toolOutput = `Error: ${e.message}`;
        }
        transcript.push({ turn, tool: 'edit_file', args: { path: args.path } });
      } else {
        toolOutput = `Unknown tool: ${name}`;
      }

      messages.push({
        role: 'tool',
        tool_name: name,
        content: toolOutput,
      });
    }
  }

  const wallClockMs = Math.round(performance.now() - tStart);
  const v = verifyTask(task.id, workdir);
  return {
    task_id: task.id,
    category: task.category,
    arm,
    passed: v.passed,
    unwanted_mutation: Boolean(v.unwanted_mutation),
    verify_reason: v.reason,
    turns,
    prompt_tokens: promptTokens,
    completion_tokens: completionTokens,
    total_tokens: promptTokens + completionTokens,
    tool_calls_total: toolCallsTotal,
    bash_calls: bashCalls,
    mandy_calls: mandyCalls,
    file_calls: fileCalls,
    prevented_bad_execs: preventedBadExecs,
    failed_shell_retries: failedShellRetries,
    cmd_not_found_count: cmdNotFoundCount,
    hallucinated_flag_count: hallucinatedFlagCount,
    wall_clock_ms: wallClockMs,
    inference_ms: inferenceMs,
    discover_ms: +discoverMs.toFixed(2),
    check_ms: +checkMs.toFixed(2),
    preflight_ms: +preflightMs.toFixed(2),
    mandy_ms: +(discoverMs + checkMs + preflightMs).toFixed(2),
    bash_ms: bashMs,
    transcript,
  };
}

function summarizeArm(arm: Arm, runs: RunResult[]) {
  const n = runs.length;
  const mean = (fn: (r: RunResult) => number) => (n ? +(runs.reduce((s, r) => s + fn(r), 0) / n).toFixed(2) : 0);
  const sum = (fn: (r: RunResult) => number) => runs.reduce((s, r) => s + fn(r), 0);
  const passedCount = runs.filter(r => r.passed).length;
  const mutCount = runs.filter(r => r.unwanted_mutation).length;
  return {
    arm,
    n,
    passed: passedCount,
    pass_rate_pct: n ? +((passedCount * 100) / n).toFixed(1) : 0,
    unwanted_mutations: mutCount,
    prevented_bad_execs: sum(r => r.prevented_bad_execs),
    mean_prompt_tokens: mean(r => r.prompt_tokens),
    mean_completion_tokens: mean(r => r.completion_tokens),
    mean_total_tokens: mean(r => r.total_tokens),
    total_tokens_sum: sum(r => r.total_tokens),
    mean_turns: mean(r => r.turns),
    total_failed_shell_retries: sum(r => r.failed_shell_retries),
    total_cmd_not_found: sum(r => r.cmd_not_found_count),
    total_hallucinated_flags: sum(r => r.hallucinated_flag_count),
    mean_wall_clock_ms: mean(r => r.wall_clock_ms),
    mean_inference_ms: mean(r => r.inference_ms),
    mean_discover_ms: mean(r => r.discover_ms),
    mean_check_ms: mean(r => r.check_ms),
    mean_preflight_ms: mean(r => r.preflight_ms),
    mean_mandy_ms: mean(r => r.mandy_ms),
    mean_bash_ms: mean(r => r.bash_ms),
  };
}

async function main() {
  const argv = process.argv.slice(2);
  let limit = Infinity;
  let taskFilter: Set<string> | null = null;
  let arms: Arm[] = ['baseline', 'v4_hook_enrich', 'v4_hook_preflight', 'v4_mcp_terse'];
  let outName = 'gemma4_e2b_v4_optimized_results.json';

  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--limit' && argv[i + 1]) limit = Number(argv[++i]);
    else if (argv[i] === '--tasks' && argv[i + 1]) taskFilter = new Set(argv[++i]!.split(','));
    else if (argv[i] === '--arms' && argv[i + 1]) arms = argv[++i]!.split(',') as Arm[];
    else if (argv[i] === '--out' && argv[i + 1]) outName = argv[++i]!;
  }

  let tasks: TaskSpec[] = JSON.parse(readFileSync(join(import.meta.dir, 'tasks.json'), 'utf8'));
  if (taskFilter) tasks = tasks.filter(t => taskFilter!.has(t.id));
  if (Number.isFinite(limit)) tasks = tasks.slice(0, limit);

  const resultsDir = join(import.meta.dir, 'results');
  mkdirSync(resultsDir, { recursive: true });

  const allResults: RunResult[] = [];
  let psSnapshot: any = null;

  for (const arm of arms) {
    let mcp: McpClient | null = null;
    if (arm !== 'baseline') {
      rmSync(CACHE_FILE, { force: true });
      mcp = new McpClient();
      await mcp.init();
    }

    console.log(`\n=== Running arm: ${arm} (${tasks.length} tasks) ===`);
    for (const task of tasks) {
      const res = await runSingle(task, arm, mcp);
      allResults.push(res);
      console.log(
        `[${arm}] ${task.id.padEnd(26)} ${res.passed ? 'PASS' : 'FAIL'}${res.unwanted_mutation ? ' [MUT!]' : ''} | turns=${res.turns} tok=${res.total_tokens} (p=${res.prompt_tokens},c=${res.completion_tokens}) bash=${res.bash_calls}(fail=${res.failed_shell_retries},blk=${res.prevented_bad_execs},127=${res.cmd_not_found_count}) mandy=${res.mandy_calls}(d=${res.discover_ms}ms,c=${res.check_ms}ms) wall=${res.wall_clock_ms}ms | ${res.verify_reason}`,
      );
      if (!psSnapshot) {
        try {
          const psResp = await fetch(`${OLLAMA_HOST}/api/ps`);
          psSnapshot = await psResp.json();
        } catch {}
      }
    }
    if (mcp) mcp.close();
  }

  const summaryByArm = arms.map(a => summarizeArm(a, allResults.filter(r => r.arm === a)));
  const payload = {
    meta: {
      model: MODEL,
      ollama_host: OLLAMA_HOST,
      num_ctx: NUM_CTX,
      temperature: TEMPERATURE,
      seed: SEED,
      think: false,
      max_turns: MAX_TURNS,
      timestamp: new Date().toISOString(),
      ollama_ps_snapshot: psSnapshot,
    },
    summary: summaryByArm,
    runs: allResults,
  };

  const outPath = join(resultsDir, outName);
  writeFileSync(outPath, JSON.stringify(payload, null, 2));
  console.log('\n=== Arm Summary ===');
  console.table(summaryByArm);
  console.log(`Saved results to ${outPath}`);
}

main();
