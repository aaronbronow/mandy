# Mandy Roadmap: The Path to Beta 1.0

Manual Discovery (**Mandy**) tool built with **Clojure (GraalVM)**, **Bun/TypeScript**, and **YAMLScript**.

## 🎯 Full Vision
Mandy is the **Interactive API for Documentation**. It transforms 40-year-old static man pages into structured, queryable data (YAMLScript) and provides a high-speed "Sniper" interface for intent-based command injection. 

By separating the **Parser (Clojure)** from the **UI (Bun)**, Mandy serves both humans (TUI) and machines (LLMs) with sub-10ms performance.

---

## 🛠 Status: Alpha Complete (v0.1 - v0.4)
- [x] **Polyglot Wrapper Architecture**: Clojure (Native GraalVM) for <10ms parsing; Bun-ready TUI architecture.
- [x] **Source Map Architecture**: Linkage between Raw Man (Model A) and Sanitized YS (Model C).
- [x] **Agentic Discovery**: `-c / --context` for finding commands based on intent.
- [x] **Vim Integration**: Vim-style status line, keybindings, and `:ls` buffer logic.
- [x] **Shell Handshake**: `/tmp/mandy_buffer` + Zsh `print -z` for seamless prompt injection.

---

## 🚀 Beta 0.1: The "Deep Search" & Distribution MVP
**Focus**: Performance, Brute Force Retrieval, and Native Portability.

### 1. High-Fidelity Extraction & Heuristics
- [ ] **Stateful Block Collector**: Implement Clojure-based "Slurp and Stitch" to handle multi-line `SYNOPSIS` blocks.
- [ ] **Local & Markdown Support**: 
    - [ ] Support `man -l` for local `.1` files.
    - [ ] Integrate `pandoc` bridge to treat `.md` files as manuals.

### 2. Search & Sniper Selection (The "Mandy Flow")
- [ ] **Stage 1: Scout**: `/` triggers incremental substring matching.
- [ ] **Stage 2: Freeze**: `Enter` highlights all matches and enables `n/p` navigation.
- [ ] **Stage 3: Magnetic Lock**: `Enter` (again) snaps focus to the **nearest previous token** (flag/arg) in Model C.
- [ ] **Stage 4: Load**: `Enter` (final) appends focused token to the Command Variant and returns to normal mode.

### 3. Deep Search Engine (`-K`)
- [ ] **Global Search**: Clojure wrapper for `man -K` (Full-text) with fallback to `man -k` (Apropos).
- [ ] **Results Dashboard**: TUI view for multiple search results with a "Peek" window for manual summaries.

### 4. Deployment & Portability
- [ ] **Port TUI to Bun**: Migrate from Node.js to Bun for faster startup and native compilation.
- [ ] **Native Compilation**: Use `bun build --compile` for the UI and GraalVM for the core.
- [ ] **Release Automation**: GitHub Actions to package multi-arch binaries (`.tar.gz`).
- [ ] **Install Script**: `curl | sh` installer for zero-friction onboarding.

---

## 🌊 Beta 0.2 & Beyond: The "Brain" Phase
- [ ] **NLP Query (`-q / --query`)**: Handshake with Gemini/Local LLM using structured `Model C` data.
- [ ] **Rich Content Injections**: "Ghost Line" architecture for interstitial widgets (live templates, status badges).
- [ ] **Variant Buffer List**: Modal `:ls` for managing multiple command drafts.

---

## 🏗 System Architecture Reference
- **Frontend**: Bun + `terminal-kit` (TUI), `TypeScript`.
- **Backend**: Clojure (GraalVM) + `YAMLScript` (Logic).
- **IPC**: Temporary file handshake via `/tmp/mandy_buffer`.
- **Model**: Source-mapped tokens across Model A (Raw), Model B (YS), and Model C (Sanitized).

---
*Mandy: The high-speed manual discovery engine.*
