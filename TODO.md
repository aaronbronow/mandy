# Mandy Roadmap: The Path to Beta 1.0

Manual Discovery (**Mandy**) tool built with **Clojure**, **Node/TypeScript**, and **YAMLScript**.

## 🎯 Vision
Transforming static documentation into a **Command IDE**. Mandy bridges the gap between intent (natural language/search) and syntax (flags/arguments) using a high-performance polyglot architecture.

---

## 🛠 Status: Alpha Complete (v0.1 - v0.4)
- [x] **Polyglot Wrapper Architecture**: Clojure (Native GraalVM) for <10ms parsing; Node.js for rich TUI.
- [x] **Source Map Architecture**: Linkage between Raw Man (Model A) and Sanitized YS (Model C).
- [x] **Agentic Discovery**: `-c / --context` for finding commands based on intent.
- [x] **Vim Integration**: Vim-style status line, keybindings, and `:ls` buffer logic.
- [x] **Shell Handshake**: `/tmp/mandy_buffer` + Zsh `print -z` for seamless prompt injection.

---

## 🚀 Beta 1.0 Milestone: The "Command IDE"

### 1. High-Fidelity Extraction & Heuristics
- [ ] **Stateful Block Collector**: Implement Clojure-based "Slurp and Stitch" to handle multi-line `SYNOPSIS` blocks.
- [ ] **Local & Markdown Support**: 
    - [ ] Support `man -l` for local `.1` files.
    - [ ] Integrate `pandoc` bridge to treat `.md` files as manuals.

### 2. Search & Sniper Selection (The "Mandy Flow")
- [ ] **Stage 1: Scout**: `/` triggers incremental substring matching.
- [ ] **Stage 2: Freeze**: `Enter` highlights all matches and enables `n/p` navigation.
- [ ] **Stage 3: Magnetic Lock**: `Enter` (again) snaps focus to the **nearest previous token** (flag/arg) in Model C.
- [ ] **Stage 4: Cycle**: `Shift+Tab` to move selection to previous/alternate tokens from the snap point.
- [ ] **Stage 5: Load**: `Enter` (final) appends focused token to the Command Variant and returns to normal mode.

### 3. Variant Management (Vim-Style Buffers)
- [ ] **Variant Buffer List**: Implement an `:ls` / `\b` modal overlay for managing multiple command drafts.
- [ ] **Stateful Drafts**: Keep multiple command variations in memory for a single man page.
- [ ] **Indicators**: Use `%a` (active), `#` (alternate), and `+` (modified) flags in the list view.
- [ ] **Hot-Swapping**: Rapidly switch between built command strings in the TUI footer.

### 4. Brute Force Discovery
- [ ] **Global Search (`-f / --find`)**:
    - [ ] Logic: `man -k` (Apropos) -> `man -K` (Full-text).
    - [ ] Clojure: Parse `--names-only` output into a structured YAML result list.
    - [ ] TUI: "Search Results" dashboard with live "Peek" previews of identified pages.

### 5. Rich Content & Plugin Injection
- [ ] **Interstitial Layer**: "Ghost Line" architecture to inject content between standard man lines.
- [ ] **Plugin Hooks**:
    - [ ] `live-template`: Interactive builders injected below `SYNOPSIS`.
    - [ ] `collapsible-section`: Hide/show complex `EXAMPLES` blocks.
    - [ ] `status-badge`: Tool-check indicators (e.g., "Dependency installed").

### 6. Agentic & NLP Bridge
- [ ] **NLP Query (`-q / --query`)**: 
    - [ ] Handshake with Gemini/Local LLM to map natural language to specific man pages and flags.
    - [ ] Automatically open Mandy with specific tokens pre-highlighted.

---

## 🏗 System Architecture Reference
- **Frontend**: Node.js + `terminal-kit` (TUI), `TypeScript`.
- **Backend**: Clojure (GraalVM) + `YAMLScript` (Parser/Logic).
- **Data Model**: 
    - **Model A**: Raw Text.
    - **Model B**: Unsanitized YS.
    - **Model C**: Sanitized/Tokenized YS.
- **IPC**: Temporary file-based handshake for stable TTY.

---
*Mandy: Manual Discovery & YAML-driven Syntax Injection.*
