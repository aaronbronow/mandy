---
name: mandy-ubuntu-discovery
description: Universal OS-layer CLI discovery and pre-execution guardrail skill (`discover_local_tool` + `check_local_command` / `mandy check --hook-stdin`) for Pi, Goose, OpenCode, Claude Code, and Gemini CLI on Ubuntu.
---

# Mandy Ubuntu Discovery & PreToolUse Guardrail

Mandy acts as a universal local OS capability & safety layer that any agent harness can place in front of shell execution:
1. **Discovery (`discover_local_tool` / `mandy discover`)**: Resolves verified `$PATH` binaries, `dpkg` package provenance, `side_effects` / `usage_order` / `dry_run_flag` safety metadata, and exact flags from `man` or `--help` (cached by `(realpath, mtime)`).
2. **Pre-Execution Guardrail (`check_local_command` / `mandy check --hook-stdin`)**: Validates proposed shell commands in `O(1)` time and `0` LLM turns before `bash` executes—catching missing binaries (`exit 127`), undocumented long flags, GNU `tar` option-after-path ordering, archive compression mismatches, shell overwrite redirect truncation (`> file`), destructive `find -exec rm` chains, and unpreviewed `rsync --delete` mutations.

## When to Call `discover_local_tool`

### Primary / Hybrid Discovery Mode
Call `discover_local_tool(intent, keyword?)` **before** executing unfamiliar, mutating, `man`-less, or prompt-named unverified binaries (e.g., PDF tools, `sqlite3`, `ffmpeg`, `b2sum`, `ys`, `busybox`, `rsync`, `tar`, `fd`, `yq`, `exiftool`, `7z`).

### Fallback Recovery Mode
Attempt direct execution first for routine POSIX/coreutils commands, and immediately call `discover_local_tool(intent, keyword)` whenever:
- A command or `PreToolUse` hook reports `MISSING_BINARY`, `UNKNOWN_FLAG`, or `exit code 127`
- A command fails with syntax or flag errors

## Reading Mandy Output

- `RESULTS`: Verified local executables on `$PATH` with `binary`, `package`, `manager`, `version`, `interface_source`, `summary`, optional `side_effects` / `dry_run_flag` / `usage_order`, and relevant `flags`.
- `NOT_INSTALLED`: Returned when `keyword` is not installed locally. **Never** run or retry a `NOT_INSTALLED` binary; switch to verified `RESULTS` alternatives or report unavailable.

## Harness Integration Chain

### MCP Stdio (`discover_local_tool` + `check_local_command`)
Start Mandy's stdio MCP server with:
```bash
/path/to/mandy/bin/mandy mcp
```

#### OpenCode (`opencode.json`)
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

#### Goose (`~/.config/goose/config.yaml`)
```yaml
extensions:
  mandy:
    type: stdio
    cmd: /home/aaron/dev/mandy/bin/mandy
    args: ["mcp"]
    enabled: true
```

#### Pi / Generic MCP Client
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

### `PreToolUse` Guardrail Hook (`mandy check --hook-stdin`)

#### Claude Code (`.claude/settings.json`)
```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "command",
            "command": "/home/aaron/dev/mandy/bin/mandy check --hook-stdin"
          }
        ]
      }
    ]
  }
}
```

#### Gemini CLI (`.gemini/settings.json`)
```json
{
  "hooks": {
    "BeforeTool": [
      {
        "matcher": "run_shell_command",
        "hooks": [
          {
            "type": "command",
            "command": "/home/aaron/dev/mandy/bin/mandy check --hook-stdin"
          }
        ]
      }
    ]
  }
}
```
