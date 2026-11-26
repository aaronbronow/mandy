# 🌿 Mandy (Manual Discovery) Beta 0.1

Mandy is a manual discovery tool that turns crusty man pages into structured, interactive command injectors. It uses a **Polyglot Wrapper Architecture**: a high-performance **Clojure** native binary handles the heavy parsing and source-mapping, while a **Bun/TypeScript** TUI provides the interactive experience.

## 🚀 Installation

Mandy is split into a **Native Parser** and a **Bun-compiled TUI**.

### 1. Quick Install (Linux & macOS)
Run the following to install the latest binaries to `/usr/local/bin`:
```bash
curl -sSL https://raw.githubusercontent.com/aaronbronow/mandy/main/scripts/install.sh | bash
```

### 2. Requirements
- **Bun**: Required to build the TUI from source.
- **YAMLScript**: Required for high-level data processing.
  ```bash
  curl -sSL https://yamlscript.org/install | bash
  ```

### 3. Build from Source (Developers)
```bash
git clone https://github.com/aaronbronow/mandy.git
cd mandy
bun install
make all
```

## 🛠 Project Structure
- **`src/clj/mandy/main.clj`**: The Native Parser & CLI entry point (Clojure). Handles man page fetching, regex-based structure parsing, source mapping, and CLI/MCP subcommands.
- **`src/clj/mandy/{strategy,ubuntu,mcp}.clj`**: Live Ubuntu discovery engine (`dpkg` provenance + `man` + `--help` fallback + `(realpath, mtime)` cache) and stdio JSON-RPC 2.0 MCP server.
- **`plugins/ubuntu_discovery.ys`**: YAMLScript (`!yamlscript/v0/data`) ranking and discovery strategy config.
- **`src/index.ts`**: The "Thin" TUI (TypeScript). Consumes pre-parsed data from the Clojure wrapper via temporary files.
- **`skills/mandy-ubuntu-discovery/SKILL.md`**: Portable agent skill and Pi/Goose/OpenCode MCP setup.
- **`BENCHMARK_UBUNTU_GEMMA.md`**: Empirical `gemma4:e2b` and `functiongemma` A/B benchmark report.
- **`Makefile`**: Orchestrates the multi-language build and test pipeline.
- **`.mandyrc`**: The shell bridge for prompt injection.

## 🧪 Try It Out
Run mandy on any command or local file:
```bash
mandy ls
mandy ./README.md
```

## ⌨️ Modes & Shortcuts
Mandy supports fluid navigation across its interactive views:

### Navigation
- **`UP` / `DOWN` / `j` / `k`**: Scroll line by line.
- **`PAGE_UP` / `PAGE_DOWN` / `CTRL_U` / `CTRL_D` / `SPACE`**: Scroll half-pages.
- **`g` / `G`**: Jump to top / bottom.
- **`TAB` / `SHIFT_TAB` / `n` / `p`**: Cycle focus through interactive tokens (flags/args).

### Search Mode (`/`)
- **Activate**: Press `/` from the text area.
- **Search**: Type to find any text. View centers on the first match.
- **Freeze**: Press `ENTER` to lock search results.
- **Cycle**: Use `n` / `p` to cycle through matches. The token cursor will **Live Snap** to the nearest candidate flag.
- **Select**: Press `ENTER` while frozen to add the snapped token and exit search.
- **Cancel**: Press `ESC` to clear search and return to normal mode.

### Command Building
- **`ENTER`**: Add focused token to the command builder.
- **`BACKSPACE` / `DELETE`**: Remove the last added token.
- **`CTRL_O` / `:`**: Jump focus to the Command Bar to finalize.
- **`ENTER` (on Command Bar)**: Terminate and inject command into your shell.
- **`V`**: Toggle between **Man View** and **YAML View**.
- **`Q`**: Quit Mandy without injecting a command.

## 🔄 Development Loop
1. **Edit** Clojure logic in `src/clj/` or TUI logic in `src/index.ts`.
2. **Test**: `make test` (includes automated TUI tests).
3. **Build**: `make mandy-cli` and `make mandy-ui`.

## 🤖 Automated TUI Testing
Mandy supports "Headless Playback" for automated functional testing. Set the `MANDY_TEST_KEYS` environment variable to a comma-separated list of key names to simulate user input:
```bash
# Select the first token and exit automatically
MANDY_TEST_KEYS="ENTER,ENTER" MANDY_PAYLOAD_PATH=payload.json bun run src/index.ts

# Or run the integrated tests
make test-tui-select
make test-tui-quit
make test-tui-search
```

## 🔍 Deep Search
Mandy can search across all installed manual pages using the `-k` (CLI) or `-K` (TUI) flags.

- **Default Scope**: For speed and relevance, Mandy only searches sections **1** (User Commands), **6** (Games), and **8** (System Administration) by default.
- **Search All**: To search all manual sections, set the `MANDY_ALL_SECTIONS=1` environment variable.
- **Contextual Results**: When multiple matches are found, Mandy outputs a YAML document containing the command names, file paths, and a snippet of context where the keyword was found.

## 🤖 Agentic Ubuntu Discovery & MCP

Mandy exposes live, zero-vector-DB Ubuntu CLI discovery (`PATH` verification, `dpkg` package provenance, `man` + `--help` flag extraction, and `(realpath, mtime)` caching) over CLI and stdio MCP for local LLM harnesses (Pi, Goose, OpenCode):

```bash
# Direct CLI discovery (Agent-First YAML)
./bin/mandy discover --intent "case insensitive regex search by file type" --keyword rg

# Persistent stdio JSON-RPC 2.0 MCP server (`discover_local_tool`)
./bin/mandy mcp
```

See [skills/mandy-ubuntu-discovery/SKILL.md](./skills/mandy-ubuntu-discovery/SKILL.md) for drop-in harness configs and usage guidelines.

### Benchmark Highlights (`gemma4:e2b` on `lal`, 6 GB VRAM)
Evaluated across an 18-task Ubuntu CLI suite (`54` runs at `num_ctx=8192`, `100%` GPU offload) — full results in [BENCHMARK_UBUNTU_GEMMA.md](./BENCHMARK_UBUNTU_GEMMA.md):
- **Accuracy**: Task pass rate rose from **44.4% (`8/18`)** in Baseline to **55.6% (`10/18`)** with Mandy Primary v1 (**50.0%** in compact Primary v2).
- **Reliability**: Failed shell retries dropped **73%–80%** (`15 → 4` Primary, `15 → 3` compact Fallback) and `exit 127` (`command not found`) errors dropped **67%** (`3 → 1`).
- **Speed & Generation**: Mean completion tokens fell **73%–83%** (`612 → 163` v1 / `105–116` v2), cutting mean wall-clock latency **45%–71%** (`6.25–6.61 s` Baseline → `2.29 s` Primary v2 / `1.89 s` Fallback v2).
- **Compact v2 Optimization**: Adaptive `exact_binary_limit: 1`, `max_flags_per_tool: 5`, and session-scoped MCP query deduplication shrunk single-tool payloads **68%–72%** and cut total multi-turn tokens **33%–35%** vs. v1.
- **Recommended Pattern**: **Hybrid Selective Discovery** (Fallback for standard POSIX/coreutils; Primary before unfamiliar, `man`-less, or unverified binaries). Un-finetuned `functiongemma` (`268M`) was also tested as a zero-shot router and found unsuitable (`0/18` discovery calls).

## 🧠 Dev Notes
Check out `GEMINI.md` for technical learnings and `PLAN.md` for the roadmap.

**Go forth and discover!** 🌿
