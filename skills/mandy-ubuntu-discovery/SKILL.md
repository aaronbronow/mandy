---
name: mandy-ubuntu-discovery
description: On-demand Ubuntu CLI tool discovery via Mandy (`discover_local_tool` MCP / `mandy discover`). Resolves verified local binaries, dpkg package provenance, versions, and exact flags from man pages or `--help` without guessing or installing packages.
---

# Mandy Ubuntu Tool Discovery

Use Mandy to inspect what CLI tools are actually installed on the local Ubuntu system and which exact flags they accept, before guessing binary names or flags.

## When to Call `discover_local_tool`

### Primary Discovery Mode
Call `discover_local_tool(intent, keyword?)` **before** running non-trivial or unfamiliar CLI utilities (e.g., PDF extraction/merging, JSON/YAML parsing, media transcoding, checksums, compression, regex search, network/socket inspection, or when a prompt names a specific tool you haven't verified locally).

### Fallback Recovery Mode
Attempt direct execution first for familiar commands, and immediately call `discover_local_tool(intent, keyword)` whenever:
- A shell command fails with `exit code 127` (`command not found`)
- A command fails with unknown/unrecognized/invalid flag or option errors
- A tool lacks a `man` page or fails silently on syntax guesses

## Reading Mandy Output

Mandy returns compact Agent-First YAML:
- `RESULTS`: Only verified local executables on `$PATH` with:
  - `binary`: Resolved executable path
  - `package`, `manager`, `version`: Provenance from local `dpkg` (or `manager: path` for user-installed tools)
  - `interface_source`: Where flags came from (`man(1)`, `man(8)`, `--help`, etc.)
  - `summary` and `flags`: Relevant option syntax and short descriptions
- `NOT_INSTALLED`: Present if `keyword` is not installed locally.
  - **Never** run or retry a binary listed under `NOT_INSTALLED` unless installing packages was explicitly requested.
  - Use a verified alternative from `RESULTS`, or report the missing tool clearly if the task requires it and no local alternative exists.

## Harness Integration (MCP Stdio)

Start Mandy's stdio MCP server with:

```bash
/path/to/mandy/bin/mandy mcp
```

### OpenCode (`opencode.json`)
```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "mandy": {
      "type": "local",
      "command": ["/home/aaron/dev/mandy/bin/mandy", "mcp"],
      "enabled": true
    }
  }
}
```

### Goose (`~/.config/goose/config.yaml`)
```yaml
extensions:
  mandy:
    type: stdio
    cmd: /home/aaron/dev/mandy/bin/mandy
    args: ["mcp"]
    enabled: true
```

### Pi / Generic MCP Client
```json
{
  "mcpServers": {
    "mandy": {
      "command": "/home/aaron/dev/mandy/bin/mandy",
      "args": ["mcp"]
    }
  }
}
```
