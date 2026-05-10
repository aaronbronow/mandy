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
... (No changes needed to Options A/B) ...

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
mandy ls
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

## 🧠 Dev Notes
Check out `GEMINI.md` for technical learnings and `PLAN.md` for the roadmap.

**Go forth and discover!** 🌿
