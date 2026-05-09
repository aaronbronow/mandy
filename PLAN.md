# PLAN.md: Beta 0.1 Speedrun for Gemini-CLI

## Objective
Migrate the TUI to Bun, implement the "Sniper" Stage 1-3 selection logic, and set up the local file resolution.

## 1. Environment Transition (Node -> Bun)
- [ ] Update `package.json` dependencies for Bun compatibility.
- [ ] Replace `child_process` calls with `Bun.spawn` or `Bun.$` for performance.
- [ ] Verify `terminal-kit` lifecycle under Bun's event loop.
- [ ] Scaffold `bun build --compile` script.

## 2. Sniper Selection Logic (TUI)
- [ ] **Search Mode**: Implement `/` input buffer. On `change`, search Model A and `term.moveTo` the first match.
- [ ] **Freeze Mode**: On `Enter`, store current `matchIndex`. Enable `n/p` to cycle `allMatches[]`.
- [ ] **Magnetic Snap**: On `Enter` (again), use the Manhattan distance formula:
  `D = (match.y - token.y) * 100 + (match.x - token.x)`
  Snap focus to the token with the smallest positive `D` where `type` is `flag` or `arg`.

## 3. Local File Resolution (Clojure Core)
- [ ] Update argument parser to check `(fs/exists? arg)`.
- [ ] If path exists, use `man -l path`.
- [ ] If file ends in `.md`, add `pandoc -s -t man path | man -l -` to the pipeline.

## 4. Deep Search Router (Clojure Core)
- [ ] Implement `-K` flag logic.
- [ ] If `man -K --names-only` returns > 1 result, output a YAML list of commands.
- [ ] If 1 result, proceed to standard Discovery flow.

## 5. Deployment Prototype
- [ ] Create `scripts/build.sh` that triggers:
    - `lein native-image` (Clojure)
    - `bun build --compile ./src/index.ts --outfile ./bin/mandy-ui`
- [ ] Draft `install.sh` for binary distribution.
