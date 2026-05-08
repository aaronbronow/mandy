# Mandy Development Learnings

## YAMLScript Integration
- **Library vs CLI**: The `@yaml/yamlscript` library (JS bindings) is sensitive to how variables are passed. Concatenating `input =: <data>` to the top of the script is a robust way to inject variables.
- **Large Content Parsing**: Passing entire man pages into a YAMLScript literal can fail if the content contains characters that confuse the YAML scanner (e.g., specific colon/space combinations). 
- **Robust Pattern**: Use YS for configuration/strategy (e.g., "which sections to search") and handle high-volume text processing in TypeScript/Zsh for stability.

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
