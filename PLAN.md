# Mandy Roadmap

Manual Discovery (**Mandy**) tool using Clojure, Node/TypeScript and YAMLScript.

## Status: 100% Complete (Alpha 0.4 - Agentic Discovery)

- [x] **Discovery Mode**
    - [x] **Context Search**: `-c` / `--context` for finding command variants based on description text.
    - [x] **Multiline Memory**: State-carrying `reduce` to associate descriptions with preceding tokens.
    - [x] **Word Boundaries**: Enforced regex `\b` to prevent false positive substring matches.
    - [x] **After-Context**: `-A` / `--after-context` to include trailing descriptive lines.
- [x] **Agentic Interface**
    - [x] **Fast Response**: Native Clojure implementation for sub-10ms discovery.
    - [x] **Structured Output**: Support for both clean string lists and machine-readable JSON arrays.
    - [x] **Self-Documenting**: High-performance `--help` response specifically for agents.
- [x] **TUI Robustness**
    - [x] **Handshake 2.0**: Switched to temporary file IPC to restore full TTY input for TUI.
    - [x] **Universal Tokens**: Extract and highlight flags mentioned anywhere in the man page.
    - [x] **Stable I/O**: Forced disk sync for reliable prompt injection.

## Status: 100% Complete (Alpha 0.3 - Polyglot Wrapper Architecture)
...
- [x] **Dev Tooling**: Added `debug-payload` and `run-tui` for isolated component testing.

## Next Steps (Beta 0.1)
1. Refine the command extraction heuristics (handle multi-line synopsis better).
2. Add support for local man page file parsing.
3. Add search functionality within the TUI.
4. Implement a "Natural Language" discovery bridge (e.g. LLM-assisted search).

