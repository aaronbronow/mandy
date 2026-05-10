# 🌿 Mandy (Manual Discovery) Beta 0.1

Mandy is a manual discovery tool that turns crusty man pages into structured, interactive command injectors. It uses a **Polyglot Wrapper Architecture**: a high-performance **Clojure** native binary handles the heavy parsing and source-mapping, while a **Bun/TypeScript** TUI provides the interactive experience.

## 🚀 Installation

Mandy is split into a **Native Parser** and a **Bun-compiled TUI**.

### 1. Requirements
- **Bun**: Required to run and build the TUI.
  ```bash
  curl -fsSL https://bun.sh/install | bash
  ```
- **YAMLScript**: Required for high-level data processing.
  ```bash
  curl -sSL https://yamlscript.org/install | bash
  ```

### 2. Get Mandy

#### Option A: End Users (No JDK/Clojure required)
1. **Download the Binary**: Download the `mandy` (Clojure) and `mandy-ui` (Bun) native binaries for your platform from the [Releases](https://github.com/aaronbronow/mandy/releases) page.
2. **Move to Path**: Place the binaries in your `$PATH` (e.g., `/usr/local/bin/`).

#### Option B: Developers (Requires Clojure/JDK)
If you want to build from source:
```bash
git clone https://github.com/aaronbronow/mandy.git
cd mandy
bun install
make mandy-ui
make mandy-cli
```

## 🛠 Project Structure
- **`src/clj/mandy/main.clj`**: The Native Parser (Clojure). Handles man page fetching, regex-based structure parsing, and source mapping.
- **`src/index.ts`**: The "Thin" TUI (TypeScript). Consumes pre-parsed data from the Clojure wrapper via temporary files.
- **`Makefile`**: Orchestrates the multi-language build and test pipeline.
- **`.mandyrc`**: The shell bridge for prompt injection.

## 🧪 Try It Out
Run mandy on any command:
```bash
mandy tar
mandy ls
```

## ⌨️ Modes
Mandy supports two interface modes:
- **Default (Nano-style)**: Familiar shortcuts like `^X` to exit.
- **VIM Mode**: VIM status line and keybindings (`j`, `k`, `g`, `G`, `q`, etc.).
    - Enable via flag: `mandy --vim <cmd>`
    - Enable via env var: `export MANDY_VIM=1`

## 🖥 Unified Interactive View
Mandy provides two perspectives on the manual, both fully interactive:
- **Man View (Default)**: The familiar, formatted manual.
- **YAML View (`V`)**: A structured YAMLScript document.
*Both views share the same Command Builder state—tab through tokens and hit Enter to build your command from either perspective.*

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
```

## 🧠 Dev Notes
Check out `GEMINI.md` for technical learnings and `PLAN.md` for the roadmap.

**Go forth and discover!** 🌿
