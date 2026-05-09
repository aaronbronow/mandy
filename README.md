# 🌿 Mandy (Manual Discovery) Alpha 0.3

Mandy is a manual discovery tool that turns crusty man pages into structured, interactive command injectors. It uses a **Polyglot Wrapper Architecture**: a high-performance **Clojure** native binary handles the heavy parsing and source-mapping, while a **Node.js/TypeScript** TUI provides the interactive experience.

## 🚀 Installation

Mandy is split into a **Native Parser** and a **Node.js TUI**.

### 1. Requirements
- **Node.js**: Required to run the TUI.
- **YAMLScript**: Required for high-level data processing.
  ```bash
  curl -sSL https://yamlscript.org/install | bash
  ```

### 2. Get Mandy

#### Option A: End Users (No JDK/Clojure required)
1. **Download the Binary**: Download the `mandy` native binary for your platform from the [Releases](https://github.com/aaronbronow/mandy/releases) page.
2. **Move to Path**: Place the binary in your `$PATH` (e.g., `/usr/local/bin/mandy`).
3. **Install TUI**:
   ```bash
   git clone https://github.com/aaronbronow/mandy.git
   cd mandy
   npm install
   npm run build
   ```

#### Option B: Developers (Requires Clojure/JDK)
If you want to build the native binary from source:
```bash
git clone https://github.com/aaronbronow/mandy.git
cd mandy
npm install
make native
```

### 3. Global Link
To make the TUI accessible to the binary:
```bash
sudo npm link
```

## 🛠 Project Structure
- **`src/clj/mandy/main.clj`**: The Native Parser (Clojure). Handles man page fetching, regex-based structure parsing, and source mapping.
- **`src/index.ts`**: The "Thin" TUI (TypeScript). Consumes pre-parsed data from the Clojure wrapper via `stdin`.
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
- **YAML View (`Y`)**: A structured YAMLScript document.
*Both views share the same Command Builder state—tab through tokens and hit Enter to build your command from either perspective.*

## 🔄 Development Loop
1. **Edit** Clojure logic in `src/clj/` or TUI logic in `src/index.ts`.
2. **Test**: `make test` or `make test-tokens`.
3. **Build**: `make native` and `npm run build`.

## 🤖 AI Agent Integration
Mandy is instant for agents and scripts. In non-interactive contexts (e.g., pipes or redirects):
- **Default (High Fidelity)**: Mandy outputs a full YAML document (Model B) preserving all formatting.
  ```bash
  mandy ls | cat
  ```
- **Sanitized**: Use the `--strip` or `-s` flag to receive a condensed version with whitespace normalized and empty lines removed.
  ```bash
  mandy ls -s | cat
  ```
- **JSON**: Pipe the output to `ys` to get a machine-readable JSON representation.
  ```bash
  mandy ls | ys -J -
  ```

## 🧠 Dev Notes
Check out `GEMINI.md` for technical learnings and `PLAN.md` for the roadmap.

**Go forth and discover!** 🌿
