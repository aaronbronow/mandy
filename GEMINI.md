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

## Subagent Integration & Agentic Retrieval (Beta 0.1)
- **High-Signal Data Sources**: Standard tools like `man` or `apropos` are designed for human consumption and can be "noisy" for LLM context windows. By providing a structured YAML/JSON output (`mandy <cmd> | cat`), we create a high-signal API that allows agents to jump directly to specific sections (e.g., SYNOPSIS, OPTIONS) without expensive token-waste on formatting characters.
- **Downstream Agent Pattern**: To keep core tools lean, specialized agentic behavior (like a `man-expert`) should live in downstream projects. These projects contain the subagent definition (`.md` + YAML frontmatter) and pull in pre-compiled binaries from the core project as needed.
- **Policy-Based Sandboxing**: Custom subagents can be strictly restricted to specific tools and command sets via the Gemini CLI Policy Engine (`policy.toml`). This allows for safe, "expert" agents that can execute a narrow set of system commands (like `man` or `mandy`) while being denied access to more sensitive tools.
- **Deep Search Snippets**: Using `zgrep` with context flags (`-C 1`) and a custom `troff` stripper provides the LLM with enough context to evaluate search result relevance across the entire system manual database in a single turn.


## CI/CD & Release Management (Beta 0.1)
- **Two-Stage Pipelines**: Separating "build" and "release" into distinct jobs in GitHub Actions prevents partial release failures. Consolidate binaries as artifacts first, then create a single release with all consolidated assets.
- **Node.js 24 Transition**: Proactively setting `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true` ensures internal GitHub Actions are compatible with upcoming runtime deprecations (Node 20).
- **Non-Interactive Compatibility**: Always use `clojure` instead of `clj` in CI scripts to avoid the `rlwrap` dependency requirement.
- **TTY Detection Bypass**: When running TUI logic in CI for automated tests, bypass `process.stdout.isTTY` checks if test-simulation keys (e.g., `MANDY_TEST_KEYS`) are present. This allows the logic to execute even when output is captured by the runner.
- **GraalVM Experimental Options**: Some native-image flags (like `-H:IncludeResources`) now require `-H:+UnlockExperimentalVMOptions` to silence build-time warnings and ensure future compatibility.

## Search & Navigation (Beta 0.1)
- **Segment-based Rendering**: When rendering lines with multiple overlapping "features" (e.g., a search match inside an interactive token), calculating boundaries and fragmenting the line into discrete segments is the only way to reliably apply priority. We used a `Set` of boundaries, sorted them, and then styled each fragment based on the highest priority feature (SEARCH > TOKEN).
- **Live Snap Logic**: Providing a "Live Snap" (immediately updating the active token as you cycle search matches) creates a much more responsive feel than waiting for a confirmation key. 
- **Distance Weighting for Manuals**: Standard Manhattan distance is insufficient for man pages. Descriptions of flags are almost always *below* the flag itself. The formula was optimized to favor tokens with a positive vertical distance (token is above match), ensuring searching for a keyword in a description snaps back to the preceding flag.
- **Centered Scrolling**: Jumping to search results is jarring if they appear at the very top or bottom of the screen. Implementing a centered scroll helper ensures the user has immediate visual context above and below the match.

## Local File Resolution (Beta 0.1)
- **Dynamic Man Pipelines**: Using `man -l` allows the parser to consume local files as if they were system man pages. For Markdown, piping through `pandoc -s -t man` provides a high-quality conversion to the `roff` format required by `man`.
- **Pre-flight Dependency Checks**: When implementing features that depend on external tools (like `pandoc`), performing a pre-flight check with `sh "which" tool` allows for much friendlier error messages than a raw shell execution failure.

## Deep Search Router (Beta 0.1)
- **Global Apropos**: `man -K` is inherently interactive, making it unsuitable for automated routers. Using `man -wK` instead provides a non-interactive list of matching file paths, which can then be parsed and processed.
- **Section Filtering**: Searching the full text of all manuals is slow and often returns irrelevant results from development or kernel headers (sections 2, 3, 7, 9). Defaulting the search scope to sections **1, 6, and 8** via `man -S 1:6:8 -wK` significantly improves both performance and the quality of results for an average user.
- **Incremental Troff Stripping**: Raw manual source contains hundreds of potential `troff` and `mdoc` macros. We used an iterative "identify and strip" approach to clean the `zgrep` snippets. The final logic handles paragraphing (`.PP`, `.IP`), indentation (`.INDENT`), font changes (`\fB`, `\sN`), and specific BSD/mdoc prefixes (`.Ar`, `.Fl`, `.Ic`, `.It`), providing human-readable context without the overhead of full `man` rendering.
- **Agent-First YAML**: The search results use the command name as the primary key (`RESULTS: { cmd: { path: ..., context: [...] } }`). This flat structure allows LLM agents to instantly index and evaluate multiple candidates in a single pass.
- **Dynamic CLI/TUI Routing**: Implementing separate flags for CLI (`-k`) and TUI (`-K`) search while sharing the underlying search logic provides a flexible interface that caters to both scripted and interactive use cases.

## Deployment & Portability (Beta 0.1)
- **GraalVM Native Image**: Compiling Clojure to native binaries via SubstrateVM provides sub-10ms startup times and removes the requirement for a Java Runtime (JRE) on the user's system.
- **Embedded YAMLScript & Resources**: Using `clj-yamlscript` and GraalVM's `-H:IncludeResources` flag allows us to bake the YS engine and specific `.ys` plugins directly into the machine-code binary. This achieves a "Zero-Dependency" core that is still logic-extensible via embedded scripts.
- **Dynamic Binary Discovery**: Portability is maintained by dynamically locating the TUI binary (`mandy-ui`) relative to the location of the parser (`mandy`) using the Java classpath/system properties, ensuring they can move together as a single package.
- **Cross-Platform CI Build Matrix**: Bun's native support for `--target` combined with a GitHub Action matrix allows for the automated generation of multi-arch binaries (Linux x64, macOS x64/arm64) from a single push, serving as a reliable "Binary Factory".
- **Installer Platform Mapping**: When writing installers, mapping `uname -s` (e.g., `Darwin`) to release platform names (e.g., `darwin-arm64`) is critical for ensuring one-liner `curl | sh` scripts work seamlessly across ecosystems.
