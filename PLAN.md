# Mandy Alpha 0.1 Roadmap

Manual Discovery (**Mandy**) tool using Node/TypeScript and YAMLScript.

## Status: 95% Complete

- [x] **Environment & Dependencies**
    - [x] Node.js/TypeScript setup
    - [x] `@yaml/yamlscript` and `enquirer` integration
    - [x] ZDOTDIR-based development shell
- [x] **Core CLI Logic**
    - [x] Fetch man pages with `man | col -b`
    - [x] **YAMLScript Pivot**: Use YS to define parsing strategy (sections)
    - [x] **Extraction**: Heuristic extraction of command variants from SYNOPSIS/EXAMPLES
    - [x] **Selection**: Interactive TUI selection with `enquirer`
    - [x] **Buffer**: Write selected command to `/tmp/mandy_buffer`
    - [x] **Auto-Detect**: Provide JSON output in non-interactive environments
- [x] **Shell Integration**
    - [x] `.mandyrc` Zsh function for command injection
    - [x] Powerlevel10k prompt indicator (**mandy** in right prompt)
    - [x] Automatic integration via `npm run shell`
- [x] **Mandy Home Manifest**
    - [x] Implement `mandy` (no args) to output a YAML manifest of available tools/plugins
- [ ] **Packaging**
    - [x] `package.json` bin configuration
    - [ ] Final `npm link` testing

## Next Steps
1. Implement the Home Manifest (YAML output when no args provided).
2. Refine the command extraction heuristics (handle multi-line synopsis better).
3. Finalize README and installation instructions.
