# Mandy Alpha 0.1 Roadmap

Manual Discovery (**Mandy**) tool using Node/TypeScript and YAMLScript.

## Status: 100% Complete (Alpha 0.1+)

- [x] **Environment & Dependencies**
    - [x] Node.js/TypeScript setup
    - [x] `@yaml/yamlscript` and `terminal-kit` integration
    - [x] ZDOTDIR-based development shell
- [x] **Core CLI Logic**
    - [x] Fetch man pages with `man | col -b`
    - [x] **YAMLScript Pivot**: Use YS to define parsing strategy (sections)
    - [x] **Extraction**: Regex-based token extraction (flags & synopsis elements)
    - [x] **Command Builder UI**: Interactive full-text builder with persistent bar
    - [x] **Focus States**: TEXT_AREA vs BUILDER_BAR logic
    - [x] **VIM Mode**: VIM-style status line and keybindings (`j`, `k`, `g`, `G`, `q`, etc.) with `MANDY_VIM` env var toggle
    - [x] **Selection**: Mouse clicks and Tab-navigation for tokens
    - [x] **Buffer**: Write built command to `/tmp/mandy_buffer`
    - [x] **Auto-Detect**: Provide JSON array of tokens in non-interactive environments
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

