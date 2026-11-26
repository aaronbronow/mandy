---
name: mandy-ubuntu-discovery
description: Universal OS-layer CLI discovery and pre-execution guardrail skill (`discover_local_tool` + `check_local_command` / `mandy check --hook-stdin`) for Pi, Goose, OpenCode, Claude Code, and Gemini CLI on Ubuntu.
---

# Mandy Ubuntu Discovery & PreToolUse Guardrail

Mandy acts as a zero-JVM GraalVM native OS capability & safety layer that any agent harness can place in front of shell execution:
1. **Discovery (`discover_local_tool` / `mandy discover`)**: Resolves verified `$PATH` binaries, `dpkg` package provenance, `side_effects` / `order` / `dry_run` metadata, and relevance-filtered flags from `man` or `--help` (Lean YAML by default; `--verbose` for full `dpkg` provenance, backed by dir-`mtime` L1/L2 caching).
2. **Zero-Turn Preflight (`preflight_prompt` / `mandy preflight --hook-stdin`)**: Emits compact Lean YAML hints on `UserPromptSubmit` only when the prompt mentions missing or specialized/`man`-less tools (`0` tokens on routine prompts).
3. **Pre-Execution Guardrail (`mandy check --hook-stdin --enrich` / `check_local_command`)**: Validates proposed shell commands before `bash` executes—catching missing binaries (`exit 127`) and unknown long flags (auto-enriched with Lean YAML alternatives), GNU `tar` option-after-path ordering, archive compression mismatches, shell overwrite redirect truncation (`> file`), destructive `find -exec rm` chains, and unpreviewed `rsync --delete` mutations.

## When to Call `discover_local_tool`

### Token-Optimal Default (Hooks + Preflight Mode)
When running `mandy preflight --hook-stdin` (`UserPromptSubmit`) + `mandy check --hook-stdin --enrich` (`PreToolUse`), `discover_local_tool` does **not** need to be probed manually for routine commands (`tar`, `grep`, `rg`, `sed`, `zstd`, `jq`, `rsync`, `sqlite3`, `ffmpeg`): run directly and let the hook inject Lean hints only when needed.

### Primary / Hybrid Discovery Mode (When Preflight Hook Is Absent)
Call `discover_local_tool(intent, keyword?)` **before** executing unfamiliar, mutating, `man`-less, or prompt-named unverified binaries (e.g., PDF tools, `sqlite3`, `ffmpeg`, `b2sum`, `ys`, `busybox`, `rsync`, `tar`, `fd`, `yq`, `exiftool`, `7z`). Pass `verbose: true` only when full `dpkg` package/version metadata is required.

### Fallback Recovery Mode
Attempt direct execution first for routine POSIX/coreutils commands, and immediately call `discover_local_tool(intent, keyword)` whenever:
- A command or `PreToolUse` hook reports `MISSING_BINARY`, `UNKNOWN_FLAG`, or `exit code 127`
- A command fails with syntax or flag errors

## Reading Mandy Output

- `RESULTS`: Verified local executables on `$PATH` with `binary`, `package`, `manager`, `version`, `interface_source`, `summary`, optional `side_effects` / `dry_run_flag` / `usage_order`, and relevant `flags`.
- `NOT_INSTALLED`: Returned when `keyword` is not installed locally. **Never** run or retry a `NOT_INSTALLED` binary; switch to verified `RESULTS` alternatives or report unavailable.

## Harness Integration Chain

### MCP Stdio (`discover_local_tool`; pass `--expose-check` to also list `check_local_command`)
Start Mandy's zero-JVM native stdio MCP server with:
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
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "/home/aaron/dev/mandy/bin/mandy preflight --hook-stdin"
          }
        ]
      }
    ],
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
