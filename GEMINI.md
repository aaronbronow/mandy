# Mandy Development Learnings

## YAMLScript Integration
- **Library vs CLI**: The `@yaml/yamlscript` library (JS bindings) is sensitive to how variables are passed. Concatenating `input =: <data>` to the top of the script is a robust way to inject variables.
- **Large Content Parsing**: Passing entire man pages into a YAMLScript literal can fail if the content contains characters that confuse the YAML scanner (e.g., specific colon/space combinations). 
- **Robust Pattern**: Use YS for configuration/strategy (e.g., "which sections to search") and handle high-volume text processing in TypeScript/Zsh for stability.
- **Data Mode**: For documents that represent data (like a sequence of section maps), use the `!yamlscript/v0/data` tag. This allows the use of standard YAML sequences (`-`) which are prohibited in the default "code mode" of `!yamlscript/v0`.

## Powerlevel10k & Prompt Integration
- **Runtime Injection**: To add a prompt indicator at runtime (after `~/.zshrc` is sourced):
    1. Define a function `prompt_<name>`.
    2. Add `<name>` to `POWERLEVEL9K_RIGHT_PROMPT_ELEMENTS`.
    3. Call `p10k reload` to apply changes immediately.
- **Instant Prompt**: Avoid `echo` or any console I/O during `.zshrc` or `.mandyrc` initialization to prevent p10k's "console output detected" warnings.

## VIM Mode Implementation
- **Keybindings**: Implementing `j`/`k` for scrolling and `q` for quit provides a familiar pager experience.
- **Aesthetics**: A status line with file info, line numbers, and percentage progress enhances the "VIM feel" significantly.
- **Argument Handling**: Using flags like `--vim` allows for a single binary to support multiple user interface preferences (Nano vs VIM).

## Zsh Development Environment
- **ZDOTDIR Pattern**: Use `ZDOTDIR=$PWD zsh -i` to create an isolated development shell that still sources the user's global `~/.zshrc`.
- **Local .zshrc**: The local `.zshrc` should source the global one: `[[ -f ~/.zshrc ]] && source ~/.zshrc`.

## CLI TUI
- **Enquirer**: Use `enquirer` for interactive selections. It handles TTY correctly even when the shell function is wrapping the Node.js call.
- **Shell Injection**: Use a temporary buffer file (`/tmp/mandy_buffer`) and `print -z` in Zsh to inject strings back into the prompt.

## Polyglot Wrapper Architecture (Alpha 0.3)
- **Thin Loader / Rich UI**: Using a native-compiled language (Clojure/GraalVM) for the entry point solves the Node.js cold-start problem. The native binary handles heavy parsing and instant "Agent Mode" output, only spawning the Node.js TUI when interactivity is required.
- **Handshake via Stdin**: Standard input is a robust, low-latency IPC channel for passing large JSON payloads (pre-parsed state and source maps) from a parent wrapper to a child TUI process.
- **Source Map Preservation**: When offloading parsing to a separate script/process, you must explicitly track and pass original line indices if the UI needs to correlate data back to the original source text.
- **Unified Build System**: A `Makefile` is essential for orchestrating multi-language projects (Clojure, TypeScript, GraalVM). It provides a single source of truth for complex build pipelines that `package.json` alone cannot handle gracefully.
- **GraalVM Native Image**: Compiling Clojure to native binaries provides sub-10ms startup times and zero-dependency distribution, making JVM-based languages viable for performance-critical CLI tools.

## Agentic Discovery (Alpha 0.4)
- **Multiline Context Reduction**: A state-carrying `reduce` is the most effective functional pattern for associating flag tokens with description text on subsequent lines. It avoids complex data nesting while preserving infinite look-behind for descriptions.
- **Handshake 2.0 (Temp Files)**: Passing data through `stdin` to a child process effectively consumes the terminal's input stream, breaking TUI interactivity. Using a temporary file for the data handshake while inheriting `stdin` from the parent allows the child process to receive real-time TTY events (keystrokes/mouse).
- **Universal Token Extraction**: Removing positional restrictions (like `startsWith('-')`) on token extraction allows flags to be identified and interactive anywhere in a document, including within prose and explanatory notes.
- **Word Boundary Regex**: Enforcing `\b` word boundaries in substring searches is critical for reducing false positives (e.g., preventing a search for "log" from matching "logical").
