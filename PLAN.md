# Mandy Roadmap

Manual Discovery (**Mandy**) tool using Clojure, Node/TypeScript and YAMLScript.

## Status: 100% Complete (Alpha 0.3 - Polyglot Wrapper Architecture)

- [x] **Polyglot Wrapper Architecture**
    - [x] **Thin Loader**: High-performance Clojure native binary (GraalVM) as the primary entry point.
    - [x] **Rich UI**: Node.js/TypeScript TUI as a "thin" consumer of pre-parsed data.
    - [x] **IPC Handshake**: Seamless data transfer from Clojure to Node.js via JSON over stdin.
- [x] **Clojure Parsing Engine**
    - [x] **Native Speed**: Instant startup (<10ms) for pipe and agent modes.
    - [x] **Source Map Retention**: Capture original line indices during Clojure parsing for TUI interaction.
    - [x] **Sanitization Logic**: Integrated tab removal, whitespace condensation, and empty line omission.
- [x] **Build System**
    - [x] **Makefile**: Unified orchestration for Clojure (uberjar/native) and TypeScript (tsc).
    - [x] **GraalVM Integration**: Native-image compilation for zero-dependency distribution.
    - [x] **Release Packaging**: `dist` target for creating platform-specific tarballs.
    - [x] **Dev Tooling**: Added `debug-payload` and `run-tui` for isolated component testing.

## Status: 100% Complete (Alpha 0.2)
...
- [x] **Packaging**
    - [x] `package.json` bin configuration
    - [x] Global linking with `npm link`

## Next Steps (Beta 0.1)
1. Refine the command extraction heuristics (handle multi-line synopsis better).
2. Add support for local man page file parsing.
3. Add search functionality within the TUI.
4. Implement a "Discovery Mode" where Mandy can suggest commands based on natural language queries.

