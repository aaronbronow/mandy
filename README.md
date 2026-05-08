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

### 3. Enter the Dev Shell
We use a specialized Zsh environment for testing that doesn't mess with your global config.
```bash
npm run shell
```
*Once inside, the `mandy` command is live and you'll see a blue `mandy` indicator in your right prompt.*

## 🛠 Project Structure
- `src/index.ts`: The TUI and CLI brain.
- `plugins/base.ys`: The YAMLScript strategy for parsing man pages.
- `.mandyrc`: The shell bridge for prompt injection.

## 🧪 Try It Out
Inside the dev shell:
```bash
mandy tar
mandy git commit
mandy ls
```

## 🧠 Dev Notes
Check out `GEMINI.md` for our hard-won technical learnings and `PLAN.md` for the roadmap.

**Go forth and discover!** 🌿
