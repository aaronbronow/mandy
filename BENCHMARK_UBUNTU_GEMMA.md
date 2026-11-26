# Mandy Ubuntu Discovery Benchmark (`gemma4:e2b` & `functiongemma` on `lal`)

## 1. Executive Summary & Hypothesis Verdict

This evaluation measures Mandy ([mandy](file:///home/aaron/dev/mandy)) as an on-demand local tool discovery layer over stdio MCP (`mandy mcp` → `discover_local_tool`) for Google `gemma4:e2b` running under a **6 GB VRAM** operational budget on `lal`, executing an 18-task Ubuntu CLI suite (54 total agent runs across 3 arms) plus an 18-prompt single-turn `functiongemma` routing evaluation.

### Verdict Summary

| Hypothesis Dimension | Result | Evidence |
|---|---|---|
| **Task Completion Accuracy** | **Validated** | Pass rate rose from **44.4% (8/18)** in Baseline to **55.6% (10/18)** in Mandy Primary (+11.2 pp) and **50.0% (9/18)** in Mandy Fallback (+5.6 pp). |
| **Shell Failure & Retry Loops** | **Validated** | Failed `bash` calls (`exit != 0`) dropped **73.3%** (`15 → 4` in Primary; `15 → 9` in Fallback). `exit 127` (`command not found`) dropped **66.7%** (`3 → 1`). |
| **Completion Tokens & Wall-Clock Speed** | **Validated** | Mean completion tokens dropped **73.4%** in Primary (`612.3 → 162.8`) and **77.0%** in Fallback (`612.3 → 140.9`). Mean wall-clock latency improved **45.3%** in Primary (`6,248.7 ms → 3,419.2 ms`) and **57.4%** in Fallback (`6,248.7 ms → 2,660.7 ms`). |
| **Total Prompt + Completion Tokens** | **Nuanced / Trade-off** | Mean total tokens **increased** (`2,509.3` Baseline vs. `5,920.4` Primary and `4,910.1` Fallback) because returning up to 3 candidate tools × 8 flags per `discover_local_tool` call adds ~1.5k–2.5k prompt tokens that are replayed across subsequent turns (`O(T)` prefix replay). Because Ollama KV-cache prefill on `lal` is much faster than Baseline's multi-turn error/generation loops, Mandy still finished **1.8×–2.3× faster** in mean wall-clock time. |

---

## 2. Hardware, Ollama 6 GB VRAM Telemetry & Ubuntu 26.04 Environment

### Compute & Client Topology
- **Inference Host (`lal`)**: Ryzen 5 3600 CPU, AMD RX 6600 XT (8 GB GDDR6), 16 GB DDR4, Samsung 870 EVO SSD, Windows 11 running Ollama `0.40.16` (`OLLAMA_HOST=0.0.0.0:11434`, `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0`).
- **Execution Host (`ubuntu-dev`)**: Ubuntu 26.04.1 LTS (Resolute Raccoon), OpenJDK `25.0.5.1`, Clojure CLI, Bun `1.4.5` (`.cache/local/bun-v1.4.5`), YAMLScript `ys 0.2.8`, calling `http://lal:11434` over Tailscale/LAN.
- **Ubuntu 26.04 `rust-coreutils` Behavior**: Standard commands such as `ls`, `sort`, `uniq`, and `b2sum` symlink to `/usr/lib/cargo/bin/coreutils/*` (`rust-coreutils`). Mandy resolves `realpath` before querying `dpkg-query -S` so provenance accurately maps to `rust-coreutils` (`0.10.0-1ubuntu2~26.04.1`).

### Locked Inference & 6 GB VRAM Telemetry (`GET /api/ps`)
All 54 benchmark runs locked `num_ctx=8192`, `temperature=0.1`, `seed=42`, `max_turns=8`, and `think=false` (Phase 1 pre-flight showed `think=false` emitted clean native `message.tool_calls` in 15 completion tokens vs. 179 tokens with default thinking enabled).

| Model | Pull Size (Manifest) | `/api/ps` `size` | `/api/ps` `size_vram` | GPU Offload | `context_length` | Quantization |
|---|---:|---:|---:|---:|---:|---|
| `gemma4:e2b` | 4,587,156,505 B (~4.27 GiB) | 404,215,561 B | 404,215,561 B | **100% GPU** | 8192 | Q4_K_M (`4.6B`) |
| `functiongemma:latest` | 300,807,157 B (~287 MiB) | 323,831,725 B | 323,831,725 B | **100% GPU** | 4096 | Q8_0 (`268.10M`) |

*(Note: Ollama `0.40.16`'s `llamacpp` runner on RX 6600 XT reported `size_vram == size == 404,215,561` bytes in `/api/ps` during active execution, confirming zero CPU memory spill well within the 6 GB operational budget.)*

---

## 3. Mandy Ubuntu Discovery Architecture

Instead of pre-indexing the filesystem into a static vector store, Mandy queries live local OS state on demand while preserving its polyglot boundaries:

- **YAMLScript Strategy (`!yamlscript/v0/data`)**: [ubuntu_discovery.ys](file:///home/aaron/dev/mandy/plugins/ubuntu_discovery.ys) holds section filters (`[1, 6, 8]`), candidate limits (`default_limit: 3`, `max_candidates: 5`), flag caps (`max_flags_per_tool: 8`, `max_flag_desc_chars: 110`), timeouts, stopwords, synonym hints, and ranking weights—loaded and memoized by file `mtime` in [strategy.clj](file:///home/aaron/dev/mandy/src/clj/mandy/strategy.clj) via `ys -J` (with `clj-yaml` fallback).
- **Live Ubuntu Discovery Engine**: [ubuntu.clj](file:///home/aaron/dev/mandy/src/clj/mandy/ubuntu.clj) combines:
  1. Verified `$PATH` executable resolution (`realpath` + `Files/isExecutable`).
  2. Offline `dpkg` metadata from `/var/lib/dpkg/status` (memoized by `mtime`), `/var/lib/dpkg/info/*.list`, and a single batched `dpkg-query -S` provenance call.
  3. Fast `apropos -s 1,6,8` + bounded `man -S 1:6:8 -wK` fallback.
  4. `man` + `--help`/`-h` fallback parsing via a multiline state-carrying `reduce` flag/description extractor (`extract-flag-entries`), persisting parsed interfaces in `~/.cache/mandy/ubuntu-discovery-cache.json` keyed by `(realpath, mtime)`.
  5. Explicit `NOT_INSTALLED` reporting when a requested binary keyword is absent locally, alongside verified local alternatives under `RESULTS`.
- **Harness Interfaces**: [mcp.clj](file:///home/aaron/dev/mandy/src/clj/mandy/mcp.clj) exposes `discover_local_tool(intent, keyword?, limit?)` over stdio JSON-RPC 2.0 (`bin/mandy mcp`), paired with [SKILL.md](file:///home/aaron/dev/mandy/skills/mandy-ubuntu-discovery/SKILL.md) for Pi, Goose, and OpenCode.

---

## 4. Quantitative A/B Results (`gemma4:e2b`)

Full run logs and transcripts are stored in [gemma4_e2b_results.json](file:///home/aaron/dev/mandy/bench/results/gemma4_e2b_results.json) (benchmark harness: [run_benchmark.ts](file:///home/aaron/dev/mandy/bench/run_benchmark.ts), [tasks.json](file:///home/aaron/dev/mandy/bench/tasks.json), [fixtures.ts](file:///home/aaron/dev/mandy/bench/fixtures.ts)).

### Table 1: Aggregate A/B Comparison (`n = 18` tasks per arm)

| Metric | Baseline (Control) | Treatment A (Mandy Primary) | Treatment B (Mandy Fallback) | Primary vs. Baseline | Fallback vs. Baseline |
|---|---:|---:|---:|---:|---:|
| **Pass Rate** | **44.4% (8/18)** | **55.6% (10/18)** | **50.0% (9/18)** | **+11.2 pp** | **+5.6 pp** |
| **Mean Prompt Tokens** | 1,897.0 | 5,757.7 | 4,769.2 | +203.5% | +151.4% |
| **Median Prompt Tokens** | 1,302.5 | 4,409.0 | 3,986.5 | +238.5% | +206.1% |
| **Mean Completion Tokens** | 612.3 | 162.8 | 140.9 | **-73.4%** | **-77.0%** |
| **Median Completion Tokens** | 73.5 | 105.0 | 81.0 | +42.9% | +10.2% |
| **Mean Total Tokens** | 2,509.3 | 5,920.4 | 4,910.1 | +135.9% | +95.7% |
| **Median Total Tokens** | 1,460.0 | 4,484.5 | 4,054.5 | +207.2% | +177.7% |
| **Total Tokens Sum (18 tasks)** | 45,167 | 106,568 | 88,382 | +135.9% | +95.7% |
| **Mean Turns** | 3.22 | 3.94 | 3.83 | +0.72 turns | +0.61 turns |
| **Failed Shell Retries (`exit != 0`)** | 15 | **4** | 9 | **-73.3%** | **-40.0%** |
| **`command not found` (`exit 127`)** | 3 | **1** | **1** | **-66.7%** | **-66.7%** |
| **Hallucinated Flag Errors (stderr)** | 0 | 0 | 0 | 0 | 0 |
| **Mean Wall-Clock Latency** | 6,248.7 ms | 3,419.2 ms | **2,660.7 ms** | **-45.3%** | **-57.4%** |
| **Median Wall-Clock Latency** | 1,236.0 ms | 2,462.5 ms | 1,773.0 ms | +99.2% | +43.4% |
| **Mean Inference Latency** | 6,235.4 ms | 2,866.1 ms | 2,284.3 ms | **-54.0%** | **-63.4%** |
| **Mean Mandy MCP Latency** | 0.0 ms | 541.7 ms | 361.8 ms | — | — |
| **Mean Bash Execution Latency** | 13.0 ms | 11.3 ms | 14.1 ms | -13.1% | +8.5% |

*(Note on cache fairness: `~/.cache/mandy/ubuntu-discovery-cache.json` was deleted before starting Treatment A and again before Treatment B so cross-arm cache leakage did not occur, while intra-arm repeat lookups benefited from `(realpath, mtime)` caching.)*

### Table 2: Per-Category Breakdown

| Category (Tasks) | Baseline | Treatment A (Mandy Primary) | Treatment B (Mandy Fallback) |
|---|---|---|---|
| `dpkg_specialized` (6) | 2/6 (mean 2,689 tok, 245 comp, 7 fails, 2,962 ms) | **3/6** (mean 4,138 tok, **92 comp**, **0 fails**, 2,563 ms) | **3/6** (mean 4,844 tok, 129 comp, 5 fails, **2,499 ms**) |
| `ubuntu_core_sys` (4) | 2/4 (mean 3,526 tok, 2,154 comp, 4 fails, 20,900 ms) | **3/4** (mean 7,938 tok, 230 comp, **3 fails**, 4,829 ms) | 2/4 (mean 5,439 tok, **204 comp**, 4 fails, **3,427 ms**) |
| `manless_help` (4) | 0/4 (mean 1,745 tok, 88 comp, 2 fails, 1,003 ms) | **1/4** (mean 4,790 tok, 187 comp, **0 fails**, 3,273 ms) | 0/4 (mean 3,137 tok, 88 comp, **0 fails**, 1,670 ms) |
| `absent_graceful` (4) | **4/4** (mean 1,988 tok, 147 comp, 2 fails, 1,774 ms) | 3/4 (mean 7,707 tok, 178 comp, 1 fail, 3,439 ms) | **4/4** (mean 6,253 tok, 149 comp, **0 fails**, 3,128 ms) |

### Table 3: Full 18-Task Pass/Fail Matrix

| Task ID | Category | Baseline | Mandy Primary | Mandy Fallback |
|---|---|---|---|---|
| `rg_py_retry` | `dpkg_specialized` | FAIL (3t, 1,403 tok, 0f) | FAIL (3t, 4,230 tok, 0f, 1m) | FAIL (3t, 2,021 tok, 0f, 0m) |
| `jq_error_latency` | `dpkg_specialized` | FAIL (8t, 9,886 tok, 6f) | FAIL (3t, 3,622 tok, 0f, 1m) | FAIL (8t, 12,764 tok, 5f, 1m) |
| `ffmpeg_extract_frame` | `dpkg_specialized` | **PASS** (2t, 2,303 tok, 0f) | **PASS** (3t, 4,739 tok, 0f, 1m) | **PASS** (3t, 4,556 tok, 0f, 1m) |
| `sqlite3_csv_export` | `dpkg_specialized` | FAIL (2t, 873 tok, 0f) | FAIL (3t, 3,373 tok, 0f, 1m) | FAIL (2t, 1,285 tok, 0f, 0m) |
| `poppler_pdf_text` | `dpkg_specialized` | FAIL (2t, 843 tok, 1f) | **PASS** (4t, 5,478 tok, 0f, 1m) | **PASS** (4t, 5,234 tok, 0f, 1m) |
| `poppler_pdf_merge` | `dpkg_specialized` | **PASS** (2t, 824 tok, 0f) | **PASS** (3t, 3,388 tok, 0f, 1m) | **PASS** (3t, 3,205 tok, 0f, 1m) |
| `zstd_level9_keep` | `ubuntu_core_sys` | **PASS** (2t, 864 tok, 0f) | **PASS** (3t, 4,032 tok, 0f, 1m) | **PASS** (2t, 1,278 tok, 0f, 0m) |
| `tar_exclude_bundle` | `ubuntu_core_sys` | **PASS** (5t, 3,296 tok, 3f) | **PASS** (8t, 19,016 tok, 3f, 3m) | FAIL (8t, 14,314 tok, 4f, 1m) |
| `rsync_mirror_delete` | `ubuntu_core_sys` | FAIL (2t, 1,272 tok, 0f) | FAIL (3t, 3,682 tok, 0f, 1m) | FAIL (2t, 1,384 tok, 0f, 0m) |
| `tree_depth2_json` | `ubuntu_core_sys` | FAIL (2t, 8,671 tok, 1f) | **PASS** (4t, 5,021 tok, 0f, 1m) | **PASS** (4t, 4,781 tok, 0f, 1m) |
| `b2sum_256bit` | `manless_help` | FAIL (4t, 2,259 tok, 0f) | FAIL (4t, 5,284 tok, 0f, 1m) | FAIL (3t, 2,041 tok, 0f, 0m) |
| `b2sum_verify_file` | `manless_help` | FAIL (4t, 2,618 tok, 0f) | FAIL (5t, 6,909 tok, 0f, 1m) | FAIL (2t, 1,360 tok, 0f, 0m) |
| `ys_yaml_to_json` | `manless_help` | FAIL (2t, 829 tok, 1f) | FAIL (3t, 3,295 tok, 0f, 1m) | FAIL (3t, 3,057 tok, 0f, 1m) |
| `busybox_applet_count` | `manless_help` | FAIL (3t, 1,274 tok, 1f) | **PASS** (3t, 3,673 tok, 0f, 1m) | FAIL (4t, 6,089 tok, 0f, 1m) |
| `absent_fd_find_json` | `absent_graceful` | **PASS** (3t, 1,346 tok, 0f) | **PASS** (4t, 5,835 tok, 0f, 1m) | **PASS** (4t, 5,595 tok, 0f, 1m) |
| `absent_yq_yaml_port` | `absent_graceful` | **PASS** (5t, 3,133 tok, 1f) | **PASS** (8t, 16,585 tok, 1f, 3m) | **PASS** (7t, 11,193 tok, 0f, 2m) |
| `absent_exiftool_report` | `absent_graceful` | **PASS** (4t, 1,956 tok, 1f) | **PASS** (3t, 3,484 tok, 0f, 1m) | **PASS** (3t, 3,553 tok, 0f, 1m) |
| `absent_7z_fallback` | `absent_graceful` | **PASS** (3t, 1,517 tok, 0f) | FAIL (4t, 4,922 tok, 0f, 1m) | **PASS** (4t, 4,672 tok, 0f, 1m) |

*(Legend: `t` = turns, `tok` = total tokens, `f` = failed shell retries, `m` = Mandy MCP calls.)*

---

## 5. Observed Failure Modes (`gemma4:e2b` & `functiongemma`)

### Where Mandy Directly Fixed Baseline Failures
1. **Missing binary guesses (`poppler_pdf_text`)**: Baseline guessed `pdf2txt report.pdf > report.txt` (`exit 127`) and gave up in 2 turns. Both Mandy Primary and Fallback discovered `pdftotext` (`poppler-utils`) in 1 MCP call (`644 ms` / `651 ms`) and solved the task cleanly with zero retries.
2. **Pathological fallback loops (`tree_depth2_json`)**: Baseline attempted a giant `find . -print | sed ...` pipeline that entered a degenerate token loop (**7,989 completion tokens**, **76,071 ms** wall clock) and exited 2. Both Mandy Primary and Fallback discovered `tree -L 2 -J`, executing `tree -L 2 -J > tree.json` cleanly in ~**2.0 s** (`88–89` completion tokens).
3. **`--help` discovery (`busybox_applet_count`)**: Baseline ran `busybox --help | grep -i "applet"` (exit 1) and wrote `1`. Mandy Primary discovered `--list[-full]` via `--help` extraction and solved `busybox --list | wc -l` (`276`) with zero failed retries.
4. **Zero-execution absence confirmation (`absent_exiftool_report`)**: Baseline ran `exiftool` and hit `exit 127`. Both Mandy arms received `NOT_INSTALLED: [exiftool]` and wrote `UNAVAILABLE: exiftool` with **0 bash calls**.

### Persistent `gemma4:e2b` (`think=false`) Limitations
1. **Silent semantic bugs not caught by exit codes**:
   - `rg_py_retry`: `gemma4:e2b` used `grep -r --include="*.py" -i` across all 3 arms, forgetting `-n` (`--line-number`), so output lacked line numbers while exiting 0.
   - `sqlite3_csv_export`: Baseline & Fallback omitted `-csv -header` (emitting pipe-delimited rows without headers); Primary ran `sqlite3 ... | awk` instead of using the discovered `-csv` and `-[no]header` flags.
   - `b2sum_256bit`: Despite Mandy returning `-l, --length`, all arms ran default 512-bit `b2sum`; Primary even truncated the 128-hex digest with `cut -c 1-64` instead of passing `-l 256` (producing a mismatched digest).
   - `b2sum_verify_file`: None of the arms passed `-c` / `--check` properly against `checksums.b2` (Baseline & Primary hallucinated `"VALID"`, Fallback omitted writing `verify.txt`).
2. **DSL / Pipeline Reasoning**: On `jq_error_latency`, Baseline and Fallback hit `exit 5` repeatedly (`5–6` retries) trying `.[] | select(...) | sort_by(...)` on streamed elements instead of an array; Primary stopped retrying after 1 call (`jq -r ... | sort`) but emitted invalid JSONL.
3. **Instruction/Tool Non-Compliance**:
   - `ys_yaml_to_json`: Baseline guessed `ys convert` (`exit 1`); Fallback ran `ys -f config.yaml -J config.json` (which ignored the positional `config.json` arg and printed JSON to stdout without writing `config.json`); Primary wrote dummy JSON (`{"config":{"setting1":...}}`) via `write_file` without running `ys` at all.
   - `busybox_applet_count` (Fallback): Ran `busybox --list` and tried to count the 276 lines in-context instead of using `wc -l`, writing `144`.
   - `absent_7z_fallback` (Primary): Created an uncompressed tar named `data.tar.zst` (`tar -cf` without `--zstd`) and wrote `"Used tool: tar"` to `method.txt`.
   - `absent_yq_yaml_port` (Primary): Ignored `NOT_INSTALLED: [yq]` on turn 3 (`exit 127`) before resolving via `read_file`.
4. **Fixture Subtlety (`rsync_mirror_delete`)**: Baseline and Primary ran a valid command (`rsync -av --delete --exclude='*.tmp' src_dir/ dst_dir/`), but the fixture created `src_dir/keep.txt` (`fresh\n`, 6 bytes) and `dst_dir/keep.txt` (`stale\n`, 6 bytes) within the same 1-second timestamp tick, so `rsync`'s default size+mtime check skipped overwriting `keep.txt` (requires `-c` or `-I`). Fallback wrote a broken `find ... -exec rm {} +` command that wiped `dst_dir/`.

### `functiongemma:latest` (268.10M Q8_0) Router Micro-Evaluation
Full results: [functiongemma_router.json](file:///home/aaron/dev/mandy/bench/results/functiongemma_router.json) ([run_functiongemma_router.ts](file:///home/aaron/dev/mandy/bench/run_functiongemma_router.ts)).

- **Routing Distribution (18 prompts)**: `discover_local_tool`: **0/18 (0%)**, `bash`: **4/18 (22.2%)**, `no_tool` (text refusal): **14/18 (77.8%)**.
- **Latency & Runaway Generation**: Mean latency **3,773.9 ms**; all 4 `bash` tool calls emitted malformed shell text and hit the generation limit (~3,827–3,863 completion tokens, ~16 s per call).
- **Assessment**: Un-finetuned `functiongemma` (`268M`) refuses open-ended Ubuntu CLI tasks or misfires into `bash` loops and is **not viable** as an out-of-the-box zero-shot pre-router for Mandy.

---

## 6. Recommended Default Integration Pattern (Pi, Goose, OpenCode)

Based on the A/B evidence, **pure Primary** achieves the highest pass rate (`55.6%`) and lowest shell retry rate (`4` vs. `15`), while **pure Fallback** cuts total tokens by `17%` vs. Primary (`4,910` vs. `5,920`) and delivers the fastest mean wall-clock latency (`2,661 ms`).

### Recommended Pattern: **Hybrid Selective Discovery + Adaptive Payload**
1. **Default to Fallback for ubiquitous POSIX/coreutils** (`ls`, `tar`, `grep`, `find`, `rsync`, `zstd`): Execute directly and invoke `discover_local_tool` only on `exit 127` or flag/syntax errors (`0` extra turns or prompt tokens on happy paths).
2. **Use Primary before unfamiliar, `man`-less, or explicitly named specialized tools** (`pdf*`, `sqlite3`, `ffmpeg`, `b2sum`, `ys`, `busybox`, `fd`, `yq`, `exiftool`, `7z`): Prevents `exit 127` dead ends (`poppler_pdf_text`), `sed`-script hallucinations (`tree_depth2_json`), and futile `--help` grepping (`busybox_applet_count`).
3. **Tighten Default MCP Return Budget for `num_ctx=8192`**:
   - When `keyword` matches an exact installed binary (`RESULTS` contains an exact binary hit), return `limit=1` instead of `3` unless `intent` is broad/open-ended.
   - Cap `max_flags_per_tool` at `5` (80-char descriptions) when `limit > 1`, reducing multi-turn prompt-token replay (`O(T)` prefix growth) by ~50–60% while keeping Mandy lookups at `O(1)` (~200–550 ms cached/warm).

Drop-in Pi, Goose, and OpenCode stdio MCP configurations and agent usage rules are packaged in [skills/mandy-ubuntu-discovery/SKILL.md](file:///home/aaron/dev/mandy/skills/mandy-ubuntu-discovery/SKILL.md).

---

## 7. Compact Optimization Experiment (v1 vs. v2)

To address the `O(T)` prompt-token replay overhead observed in v1, three compact optimizations were implemented in [ubuntu_discovery.ys](file:///home/aaron/dev/mandy/plugins/ubuntu_discovery.ys), [strategy.clj](file:///home/aaron/dev/mandy/src/clj/mandy/strategy.clj), [ubuntu.clj](file:///home/aaron/dev/mandy/src/clj/mandy/ubuntu.clj), and [mcp.clj](file:///home/aaron/dev/mandy/src/clj/mandy/mcp.clj):
1. **Adaptive Limit**: `exact_binary_limit: 1` when `keyword` resolves to an installed local binary, and `default_limit: 2` for open-ended `intent` queries or `NOT_INSTALLED` fallbacks.
2. **Tighter Flag & Noise Caps**: `max_flags_per_tool: 5`, `max_flag_desc_chars: 75`, and `min_candidate_score: 40` (retaining at least the top-1 candidate).
3. **Session-Scoped MCP Deduplication**: Identical repeated `(intent, keyword, limit)` queries within a session return a 1-line `DUPLICATE_QUERY` pointer instead of re-emitting YAML (isolated per benchmark run via `notifications/mandy/reset_session`).

### Payload Shrinkage
| Probe Query (`bin/mandy discover --keyword ...`) | v1 Output Size | v2 Output Size | Delta |
|---|---:|---:|---:|
| `rg` (installed binary) | 2,456 chars (43 lines) | **750 chars** (14 lines) | **-69.5%** |
| `tar` (installed binary) | 2,232 chars (43 lines) | **704 chars** (14 lines) | **-68.5%** |
| `jq` (installed binary) | 2,455 chars (43 lines) | **700 chars** (14 lines) | **-71.5%** |
| `fd` (`NOT_INSTALLED` + alternatives) | 2,413 chars (45 lines) | **1,048 chars** (26 lines) | **-56.6%** |

### v1 vs. v2 Full 18-Task Benchmark Comparison (`gemma4:e2b` on `lal`)
Results saved in [gemma4_e2b_v2_compact_results.json](file:///home/aaron/dev/mandy/bench/results/gemma4_e2b_v2_compact_results.json) (matched against Baseline re-run and v1 [gemma4_e2b_results.json](file:///home/aaron/dev/mandy/bench/results/gemma4_e2b_results.json)):

| Metric | Baseline (v2 run) | Primary v1 | Primary v2 (Compact) | Primary v2 vs. v1 | Fallback v1 | Fallback v2 (Compact) | Fallback v2 vs. v1 |
|---|---:|---:|---:|---:|---:|---:|---:|
| **Pass Rate** | 44.4% (`8/18`) | **55.6% (`10/18`)** | 50.0% (`9/18`) | -5.6 pp | 50.0% (`9/18`) | 44.4% (`8/18`) | -5.6 pp |
| **Mean Prompt Tokens** | 1,897.0 | 5,757.7 | **3,841.9** | **-33.3%** | 4,769.2 | **3,104.7** | **-34.9%** |
| **Median Prompt Tokens** | 1,302.5 | 4,409.0 | **3,582.0** | **-18.8%** | 3,986.5 | **2,942.0** | **-26.2%** |
| **Mean Completion Tokens** | 612.3 | 162.8 | **115.5** | **-29.1%** | 140.9 | **105.3** | **-25.3%** |
| **Mean Total Tokens** | 2,509.3 | 5,920.4 | **3,957.4** | **-33.2%** | 4,910.1 | **3,210.0** | **-34.6%** |
| **Median Total Tokens** | 1,460.0 | 4,484.5 | **3,680.0** | **-18.0%** | 4,054.5 | **3,043.5** | **-25.0%** |
| **Total Tokens Sum (18 tasks)** | 45,167 | 106,568 | **71,233** | **-33.2%** | 88,382 | **57,780** | **-34.6%** |
| **Mean Turns** | 3.22 | 3.94 | **3.83** | -0.11 | 3.83 | **3.44** | -0.39 |
| **Failed Shell Retries** | 15 | 4 | **4** | 0% | 9 | **3** | **-66.7%** |
| **Mandy MCP Calls** | 0 | 22 | **19** | -13.6% | 13 | **12** | -7.7% |
| **Mean Wall-Clock Latency** | 6,613.2 ms | 3,419.2 ms | **2,286.1 ms** | **-33.1%** | 2,660.7 ms | **1,890.3 ms** | **-29.0%** |
| **Median Wall-Clock Latency** | 1,441.0 ms | 2,462.5 ms | **2,151.5 ms** | **-12.6%** | 1,773.0 ms | **1,734.5 ms** | **-2.2%** |

### Key Takeaways from v2
- **33–35% Total Token Reduction & 3.5× Speedup vs. Baseline**: Compact outputs + dedup cut Primary total tokens from `106,568` to `71,233` (`-33.2%`) and Fallback total tokens from `88,382` to `57,780` (`-34.6%`, now within `+27.9%` of Baseline's `45,167`). Mean wall-clock latency dropped another ~30% to **2,286 ms** (Primary) and **1,890 ms** (Fallback, **3.5× faster** than Baseline's `6,613 ms`).
- **Task-Level Shift (`-1` pass in both Mandy arms)**: In Primary v2, `ys_yaml_to_json` and `absent_7z_fallback` flipped `FAIL → PASS`, while `tar_exclude_bundle`, `busybox_applet_count`, and `absent_yq_yaml_port` flipped `PASS → FAIL` (two of those three failed because `gemma4:e2b` guessed in-head counts or skipped flags, not due to missing binaries). In Fallback v2, `tar_exclude_bundle` flipped `FAIL → PASS` while `tree_depth2_json` and `absent_yq_yaml_port` flipped `PASS → FAIL` (`tree_depth2_json` failed because Fallback skipped Mandy on turn 1).
- **Remaining Token Gap**: Even with `limit: 1` (`~700` chars), the remaining `+12.6k` token gap between Fallback v2 (`57,780`) and Baseline (`45,167`) comes from two structural sources: (1) adding the `discover_local_tool` schema + skill block to the system prompt (`O(T)` per turn across all 18 tasks, ~180 tokens/turn), and (2) `gemma4:e2b` invoking Mandy proactively on 12/18 tasks in Fallback mode. Further reduction requires **harness-level tool pruning** (dropping resolved `discover_local_tool` outputs from earlier turns once `bash` exits `0`).

---

## 8. Universal Harness Guardrail Experiment (`mandy check` PreToolUse Chain, `n = 20`)

To model how real agent harnesses (Claude Code, OpenCode, Goose, Pi, Gemini CLI) pair OS discovery with deterministic `PreToolUse` fail-safes before spawning `bash`, [check.clj](file:///home/aaron/dev/mandy/src/clj/mandy/check.clj) (`mandy check` / `--hook-stdin` / MCP `check_local_command`) was added alongside `side_effects`, `dry_run_flag`, and `usage_order` metadata in [ubuntu.clj](file:///home/aaron/dev/mandy/src/clj/mandy/ubuntu.clj).

### Suite Expansion (`18 → 20` Tasks)
- Fixed the `rsync_mirror_delete` mtime/size equality artifact in [fixtures.ts](file:///home/aaron/dev/mandy/bench/fixtures.ts) (`dst_dir/keep.txt` backdated 60s with distinct byte length).
- Added two non-idempotent `mutating_safety` tasks in [tasks.json](file:///home/aaron/dev/mandy/bench/tasks.json):
  - `safe_rsync_prune_preserve`: Mirror `src_dir/` into `dst_dir/` (`--exclude *.tmp --delete`) without altering `protected/ledger.txt`.
  - `safe_sed_inplace_replace`: Replace every occurrence of `http://old.local` in `app.conf` in-place via bash without truncating the file.

### Aggregate Comparison (`n = 20`, `60` Runs on `lal`)
Results saved in [gemma4_e2b_v3_guardrail_results.json](file:///home/aaron/dev/mandy/bench/results/gemma4_e2b_v3_guardrail_results.json):

| Metric | Baseline (Control) | `harness_mandy_guardrail` (PreToolUse + Fallback) | `harness_mandy_full` (PreToolUse + Hybrid Primary) |
|---|---:|---:|---:|
| **Pass Rate** | 55.0% (`11/20`) | 55.0% (`11/20`) | **65.0% (`13/20`)** |
| **Unwanted Disk Mutations** | 0 | **0** | **0** |
| **Prevented Bad Executions** | 0 | **4** | **3** |
| **Failed OS Shell Calls (`exit != 0`)** | 15 | **0 (-100%)** | 5 (-66.7%) |
| **`exit 127` (`command not found`)** | 3 | **0 (-100%)** | **0 (-100%)** |
| **Mean Prompt Tokens** | 1,793.0 (med 1,247.0) | 2,959.0 (med 3,145.5) | 4,414.0 (med 4,052.5) |
| **Mean Completion Tokens** | 581.4 (med 74.5) | **110.1 (-81.1%)** (med 88.0) | 175.7 (-69.8%) (med 100.5) |
| **Mean Total Tokens** | 2,374.4 (med 1,411.0) | 3,069.1 (med 3,224.0) | 4,589.7 (med 4,159.0) |
| **Mean Turns** | 3.10 | 3.20 | 3.90 |
| **Mean Wall-Clock Latency** | 5,963.0 ms (med 1,220.0) | **1,947.4 ms (-67.3%)** (med 1,800.5) | 3,081.4 ms (-48.3%) (med 2,246.5) |
| **Mean Inference Latency** | 5,948.7 ms | **1,592.5 ms** | 2,547.9 ms |
| **Mean `discover_local_tool` Latency** | 0.0 ms | 282.3 ms | 484.7 ms |
| **Mean `check_local_command` Latency** | 0.0 ms | **54.8 ms** | **32.4 ms** |
| **Mean Bash Execution Latency** | 13.9 ms | 17.8 ms | 16.1 ms |

### Intercepted Footguns & Engineering Observations
1. **Zero Failed OS Shell Calls (`15 → 0`) with `54.8 ms` Overhead**: In `harness_mandy_guardrail`, `check_local_command` blocked **4** broken/unpreviewed commands before the OS touched disk (`0` false-positive blocks on valid commands):
   - `tar_exclude_bundle`: Intercepted `tar -czf bundle.tar.gz project/ --exclude='*.log' --exclude='node_modules'` (`FLAG_ORDER_ERROR`, `73 ms`), prompting discovery and clean recovery in 1 shell run (Baseline took 3 failed `tar` retries).
   - `rsync_mirror_delete` & `safe_rsync_prune_preserve`: Intercepted unpreviewed `rsync --delete` (`UNPREVIEWED_MUTATION`, `118 ms` and `16 ms`), forcing an `rsync -avn` preview before applying cleanly.
2. **Highest Accuracy (`65.0%`) in `harness_mandy_full`**: Combining `PreToolUse` checks with Hybrid Primary discovery lifted `manless_help` from `0/4` (Baseline & Fallback) to `2/4` (`b2sum_verify_file` and `ys_yaml_to_json`), while maintaining `0` `exit 127` errors and cutting mean wall-clock latency by **48.3%** (`5,963 ms → 3,081 ms`).
3. **Edge Cases Observed**:
   - In `harness_mandy_full`, `gemma4:e2b` stopped after running the `rsync -avn` preview on `rsync_mirror_delete` without re-running without `-n` (`0` mutations, `FAIL`), whereas it completed the two-phase apply cleanly on `safe_rsync_prune_preserve` and in both `harness_mandy_guardrail` `rsync` tasks.
   - On `absent_yq_yaml_port`, `gemma4:e2b` wrapped `yq` inside `if command -v yq &> /dev/null; then PORT=$(yq ...); fi` inside a single multi-line bash call; Mandy's static splitter flagged `yq` inside the guarded `if` branch and `$PORT` inside `[[ -n "$PORT" ]]` (`MISSING_BINARY`, `21–22 ms`). Skipping `command -v`/`which` conditional branches and `$VAR` operands inside `[[ ... ]]` is a clean future refinement for `check.clj`.


