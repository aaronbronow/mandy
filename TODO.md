# Mandy Roadmap: The Path to Beta 1.0

Manual Discovery (**Mandy**) tool built with **Clojure (GraalVM)**, **Bun/TypeScript**, and **YAMLScript**.

## 🎯 Full Vision
Mandy is the **Interactive API for Documentation**. It transforms 40-year-old static man pages into structured, queryable data (YAMLScript) and provides a high-speed "Search" interface for intent-based command injection. 

By separating the **Parser (Clojure)** from the **UI (Bun)**, Mandy serves both humans (TUI) and machines (LLMs) with sub-10ms performance.

---

## 🛠 Status: Beta 0.1 Complete
- [x] **Polyglot Wrapper Architecture**: Clojure (GraalVM Native Image) for <10ms parsing; Bun-compiled TUI.
- [x] **Zero-Dependency Distribution**: Embedded YAMLScript engine/plugins; multi-arch native binaries for Linux & macOS.
- [x] **Environment Migration**: Fully transitioned from Node.js to Bun for both development and distribution.
- [x] **Local & Markdown Support**: Support for `man -l` and `pandoc` bridge for `.md` files.
- [x] **Search Selection Logic**:
    - [x] **Search Mode**: `/` for real-time substring matching and centered scrolling.
    - [x] **Freeze Mode**: `Enter` to lock results and cycle with `n/p`.
    - [x] **Live Snap**: Automatic cursor jumping to nearest flags during search/cycling.
    - [x] **Direct Action**: `Enter` from freeze to append token and return to normal mode.
- [x] **Deep Search Engine**: `-k` (CLI) and `-K` (TUI) for global manual search with `zgrep` context extraction.
- [x] **Zero-Friction Distribution**: Portable Uberjars, GraalVM native binaries, and a one-liner `install.sh`.

---

## 🚀 Beta 0.2: The "Brain" & Heuristics Phase
**Focus**: Semantic intelligence, better parsing, and rich UI widgets.

### 1. High-Fidelity Extraction
- [ ] **Stateful Block Collector**: Implement Clojure-based "Slurp and Stitch" to handle multi-line `SYNOPSIS` blocks.
- [ ] **Contextual Heuristics**: Better association of flags with their argument types (e.g., detecting `<file>` or `[DIR]`).

### 2. LLM Integration
- [ ] **NLP Query (`-q / --query`)**: Handshake with Gemini/Local LLM using structured `Model C` data to build commands from prose.
- [ ] **Explainer Mode**: Request a natural language explanation of a complex flag or command.

### 3. TUI Refinements
- [ ] **Results Dashboard**: TUI view for multiple search results with a "Peek" window for manual summaries.
- [ ] **Ghost Line Architecture**: Interstitial widgets for live templates and status badges.
- [ ] **Variant Buffer List**: Modal `:ls` for managing multiple command drafts.

---

## 🌊 Future Roadmap
- [ ] **Cross-Platform Release Automation**: GitHub Actions to package multi-arch binaries (`.tar.gz`) on every tag.
- [ ] **Plugin System**: Allow custom YAMLScript logic for specialized command parsers.

---

## 🏗 System Architecture Reference
- **Frontend**: Bun + `terminal-kit` (TUI), `TypeScript`.
- **Backend**: Clojure (GraalVM) + `YAMLScript` (Logic).
- **IPC**: Temporary file handshake via `/tmp/mandy_buffer`.
- **Model**: Source-mapped tokens across Model A (Raw), Model B (YS), and Model C (Sanitized).

---
*Mandy: The high-speed manual discovery engine.*
