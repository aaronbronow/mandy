# Mandy Model Compatibility & Integration Mode Tracker

Empirical benchmarks across **local edge** (`gemma4:e2b` Q4_K_M on `lal`, `num_ctx=8192`, `100%` GPU offload), **cloud fast agentic** (`gemini-3.8-flash` across `60` runs / `20` Ubuntu CLI tasks), **cloud frontier** (partial `gemini-3.1-pro-preview`), and **sub-1B nano router** (`functiongemma:latest`, `18` prompts), plus projected guidance for peer models in each capability tier.

---

## 1. Adoption & Mode Tracker (Large Cloud → Small Local)

| Tier | Deployment | Representative Models | Evidence Status | Use Mandy? | Recommended Mode | Expected / Observed Impact |
|---|---|---|---|---|---|---|
| **1. Frontier Reasoning / Coding** | Cloud | `gemini-3.1-pro-preview`, Claude Opus / Sonnet, GPT-5 / Codex | **Measured** *(partial `3.1-pro`: `19/19` base, `14/14` preflight)* | **Selective** | **`PreToolUse` safety + absent/custom-tool `preflight` only** *(no MCP tool)* | Minimal flag-recall gain on POSIX/GNU tools (`19/19` non-429 base). Value is deterministic OS safety (`rsync --delete`, `find \| xargs rm`), custom CLIs, and 0-turn `NOT_INSTALLED` checks. |
| **2. Fast / Balanced Agentic** | Cloud | **`gemini-3.8-flash`**, `gemini-3.5-flash-lite`, Claude Haiku, GPT-5-mini, DeepSeek-V3 / Qwen3-Coder (API) | **Measured** *(`3.8-flash`: `60` runs)* | **Yes** | **`v4_hook_preflight`** *(zero-schema hooks + Turn-0 preflight; skip manual MCP)* | **Measured (`3.8-flash`)**: **`+10.0 pp`** pass rate (`75% → 85%`), **`-11.9%`** total tokens (`315,180 → 277,554`), **`-10.5%`** thought tokens, **`-9.3%`** wall clock (`33.0 s → 29.9 s`). |
| **3. Large Local Workstation (24B–70B+)** | Local *(24–48 GB VRAM)* | `gemma-4-31b-it`, `gemma-4-26b-a4b-it` (MoE), Qwen2.5/3-Coder 32B, Devstral 24B, Llama 3.3 70B | Projected | **Yes** | **`v4_hook_preflight`** *(zero-schema hooks + preflight)* | Strong baseline CLI recall, but local decode is slower than cloud. Turn-0 preflight avoids `which`/`man` probing turns; `< 1 ms` warm `check` blocks non-idempotent footguns at zero turn cost. |
| **4. Edge / Consumer GPU (4B–14B)** | Local *(6–12 GB VRAM)* | **`gemma4:e2b` (4.6B)**, Qwen2.5/3-Coder 7B–14B, Llama 3.1 8B, Phi-4 | **Measured** *(`gemma4:e2b`: `80` v4 runs)* | **Strong Yes *(Sweet Spot)*** | **`v4_hook_preflight`** *(+ terse MCP if hooks unavailable)* | **Measured (`gemma4:e2b`)**: **`+10.0 pp`** pass rate (`55% → 65%`), **`-6.4%`** total tokens (`47,437 → 44,398`), **`2.57×` faster** (`6.27 s → 2.44 s`), `0` unwanted mutations, `0` `exit 127`. |
| **5. Ultra-Small Local (1B–3B)** | Local *(2–4 GB VRAM / CPU)* | Qwen2.5 1.5B–3B, Llama 3.2 1B–3B, Gemma 3 1B | Projected | **Caution** | **Guardrail + Preflight only** *(never expose extra MCP tools)* | Weak multi-tool routing means extra MCP schemas hurt reliability. Keep `tools[]` minimal and rely on silent `mandy check --enrich` to block bad flags/destructive commands before execution. |
| **6. Sub-1B Nano Routers (< 1B)** | Local *(< 1 GB VRAM)* | **`functiongemma:latest` (268M)**, SmolLM / 0.5B class | **Measured** *(`functiongemma`: `18` prompts)* | **No *(zero-shot)*** | **Do not use** *(unless fine-tuned on Mandy)* | **Measured (`functiongemma`)**: `0/18` discovery calls (`14/18` text refusals, `4/18` runaway bash loops). Not viable as an out-of-the-box router or agent. |

---

## 2. Measured Cloud (`gemini-3.8-flash`) vs. Local Edge (`gemma4:e2b`) Comparison (`n = 20`)

| Model & Mode (`n = 20` per arm) | Pass Rate | Unwanted Mutations | Failed Shell Calls (`exit != 0`) | Total Tokens Sum | Token Delta vs. Baseline | Mean Wall-Clock |
|---|---:|---:|---:|---:|---:|---:|
| **`gemma4:e2b` — Baseline (Control)** | 55.0% (`11/20`) | 0 | 15 | 47,437 | `0.0%` | 6,274.6 ms |
| **`gemma4:e2b` — `v4_hook_preflight`** | **65.0% (`13/20`)** | **0** | 8 | **44,398** | **`-6.4%`** | **2,441.7 ms (`2.57×` faster)** |
| **`gemma4:e2b` — `v4_mcp_terse`** | 50.0% (`10/20`) | 1 *(piped `xargs rm`, now fixed)* | **1** | 59,077 | `+24.5%` | 5,611.9 ms |
| **`gemini-3.8-flash` — Baseline (Control)** | 75.0% (`15/20`) | 0 | 8 | 315,180 | `0.0%` | 33,008.4 ms |
| **`gemini-3.8-flash` — `v4_hook_preflight`** | **85.0% (`17/20`)** | **0** | **5 (`-37.5%`)** | **277,554** | **`-11.9%`** | **29,929.4 ms (`-9.3%`)** |
| **`gemini-3.8-flash` — `v4_mcp_terse`** | 75.0% (`15/20`) | 0 | 6 | 321,110 | `+1.9%` | 33,795.2 ms |

*(On `gemini-3.8-flash`, `v4_hook_preflight` also cut mean thought tokens by `10.5%` (`762.1 → 681.8`), reduced mean bash execution latency from `2,040.2 ms` to `71.7 ms` by eliminating timed-out filesystem searches like `find / -name "*busybox*"`, and unlocked `ys_yaml_to_json`, `busybox_applet_count`, and `absent_7z_fallback`.)*

---

## 3. Quick Decision Rules for Harness Configuration

1. **Default to `v4_hook_preflight` (`mandy preflight --hook-stdin` + `mandy check --hook-stdin --enrich`) across 4B local models and fast cloud models:**
   - Adds `0` tool-schema tokens to `tools[]`, beats Baseline total tokens on both local `gemma4:e2b` (`-6.4%`) and cloud `gemini-3.8-flash` (`-11.9%`), and lifts task pass rate by `+10.0 pp` on both.
2. **Avoid exposing manual `discover_local_tool` MCP on cloud models:**
   - Cloud models already know common POSIX/GNU CLIs (`10/10` on `dpkg_specialized` + `ubuntu_core_sys` in `gemini-3.8-flash` Baseline). Exposing manual MCP caused extra exploration roundtrips (`v4_mcp_terse`: `+1.9%` tokens, `+2.4%` latency, `0` accuracy gain). Keep MCP discovery for local models running inside hookless harnesses (e.g., standard `opencode.json` MCP setups).
3. **Keep `shell-builtins` complete for cloud shell idioms:**
   - Richer bash scripts generated by cloud models (compound brace groups `{ ...; }`, `history`, `command -v`) require complete builtin coverage in [check.clj](file:///home/aaron/dev/mandy/src/clj/mandy/check.clj#L6-L15) to avoid false-positive `MISSING_BINARY` blocks.
4. **Full detailed benchmark report:** See [BENCHMARK_UBUNTU_GEMMA.md](./BENCHMARK_UBUNTU_GEMMA.md).
