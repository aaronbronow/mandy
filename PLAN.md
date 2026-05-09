# Mandy Alpha 0.1 Roadmap

Manual Discovery (**Mandy**) tool using Node/TypeScript and YAMLScript.

## Status: 100% Complete (Alpha 0.2)

- [x] **Environment & Dependencies**
    - [x] Node.js/TypeScript setup
    - [x] `@yaml/yamlscript`, `terminal-kit`, and `js-yaml` integration
    - [x] ZDOTDIR-based development shell
- [x] **Core CLI Logic**
    - [x] Fetch man pages with `man | col -b`
    - [x] **Source Map Architecture**: Tight coupling between Raw Man (Model A), Unsanitized YS (Model B), and Sanitized YS (Model C).
    - [x] **Extraction**: Precise flag extraction from the Sanitized model, projected onto the Visual model.
    - [x] **Command Builder UI**: Interactive full-text builder with shared state between MAN and YAML views.
    - [x] **VIM Mode**: VIM-style status line and keybindings with `MANDY_VIM` env var toggle.
    - [x] **Selection**: Mouse clicks and Tab-navigation for tokens across both views.
    - [x] **Buffer**: Write built command to `/tmp/mandy_buffer` for shell injection.
    - [x] **Auto-Detect**: Provide JSON array of tokens in non-interactive environments.
- [x] **YAMLScript Integration**
    - [x] **High-Fidelity View**: Structured YS representation preserving all original formatting.
    - [x] **Data Document**: Output `!yamlscript/v0/data` compliant documents via `--debug`.
- [x] **Shell Integration**
    - [x] `.mandyrc` Zsh function for command injection
    - [x] Powerlevel10k prompt indicator (**mandy** in right prompt)
    - [x] Automatic integration via `npm run shell`
- [x] **Mandy Home Manifest**
    - [x] Implement `mandy` (no args) to output a YAML manifest of available tools/plugins
- [x] **Packaging**
    - [x] `package.json` bin configuration
    - [x] Global linking with `npm link`


## Next Steps (Beta 0.2)
1. Refine the command extraction heuristics (handle multi-line synopsis better).
2. Add support for local man page file parsing.
3. Add search functionality within the TUI.

