# 🌿 Mandy (Manual Discovery) Alpha 0.1

Mandy is a manual discovery tool that turns crusty man pages into structured, interactive command injectors. It uses **YAMLScript** for high-level parsing and **TypeScript** for the TUI magic.

## 🚀 Speedrun: Local Setup

Get Mandy running in under 60 seconds:

### 1. System Requirements
You need the **YAMLScript** native library:
```bash
curl -sSL https://yamlscript.org/install | bash
```

### 2. Install & Build
```bash
npm install
npm run build
```

### 3. Global Link (Optional)
To use `mandy` anywhere on your system:
```bash
sudo npm link
```
*Note: Because this creates a symlink, you only need to run `npm run build` to update the global binary after making changes.*

### 4. Enter the Dev Shell
We use a specialized Zsh environment for testing that doesn't mess with your global config.
```bash
npm run shell
```
*Once inside, the `mandy` command is live (via a shell function that enables prompt injection) and you'll see a blue `mandy` indicator in your right prompt.*

## 🛠 Project Structure
- `src/index.ts`: The TUI and CLI brain.
- `plugins/base.ys`: The YAMLScript strategy for parsing man pages.
- `.mandyrc`: The shell bridge for prompt injection.

## 🧪 Try It Out
Inside the dev shell (or anywhere if linked):
```bash
mandy tar
mandy git commit
mandy ls
```

## 🔄 Development Loop
Since Mandy is linked to your source, your workflow is:
1. **Edit** `src/index.ts` or `plugins/base.ys`.
2. **Build**: `npm run build` (or `npx tsc -w` for auto-build).
3. **Run**: `mandy <cmd>` is immediately updated.

## 🤖 AI Agent Integration
Mandy is built for humans and agents. If you are an AI agent:
- Run `mandy` (no args) to see the **Home Manifest**.
- Pipe Mandy's output (e.g., `mandy ls | cat`) to receive a **machine-readable JSON array** of discovered command variants.

## 🧠 Dev Notes
Check out `GEMINI.md` for our hard-won technical learnings and `PLAN.md` for the roadmap.

**Go forth and discover!** 🌿
