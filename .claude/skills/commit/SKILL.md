---
name: commit
description: Create a git commit following conventional commit format with Claude co-author attribution. Stages relevant files (never `git add -A`) and writes a descriptive message focused on the WHY. Use when Islom wants to commit changes. Triggers include phrases like "commit this", "commit the changes", "make a commit", "git commit".
allowed-tools: Read, Bash, Glob, Grep
---

# Commit

Create a clean, conventional commit for yalla-sip-phone.

## Process

### 1. See What's Changed

```bash
git status
git diff                          # unstaged changes
git diff --staged                 # staged changes
git log -5 --oneline              # recent style
```

### 2. Stage Intentionally

**Never `git add -A` or `git add .`** — always list files explicitly. This prevents accidentally committing:
- Secret files (`.env`, local configs)
- Build artifacts
- IDE files
- `.obsidian/` from the docs/obsidian vault (should already be gitignored, but double-check)

```bash
git add src/main/kotlin/... src/test/kotlin/... docs/obsidian/06-sessions/...
```

### 3. Choose Type and Scope

Conventional commit types:
- `feat` — new feature or capability
- `fix` — bug fix
- `refactor` — no behavior change
- `chore` — tooling, build, deps
- `docs` — documentation only
- `test` — test-only changes
- `perf` — performance improvement

Scope (pick one per commit):
- `sip` — PJSIP integration, engine, account manager
- `auth` — auth flow, token, login
- `ui` — Compose Desktop UI
- `webview` — JCEF browser integration
- `network` — Ktor, HTTP client
- `di` — Koin DI modules
- `build` — Gradle, convention plugins
- `test` — test infra
- `docs` — markdown docs / Obsidian vault

### 4. Write the Message

Format:
```
type(scope): short imperative summary (max 70 chars)

Optional body explaining the WHY, not the what. Reference issues or
session logs if relevant. Break long lines at 72 chars.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
```

**Focus on WHY**, not WHAT. The diff shows WHAT. The message should explain motivation, trade-offs, or constraints that aren't obvious.

### 5. Commit

Use a HEREDOC for multi-line messages to preserve formatting:

```bash
git commit -m "$(cat <<'EOF'
fix(sip): snapshot SWIG params before dispatching to Kotlin layer

The onRegState callback was using prm.code after the C callback
returned, causing intermittent SIGSEGV under high registration
churn. Copy all fields synchronously before any suspension.

See rules/pjsip-threading.md "SWIG Pointer Invalidation" section.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### 6. Verify

```bash
git log -1                        # confirm the commit
git show --stat HEAD              # see what was committed
```

## Do Not

- **Never amend** a previous commit without Islom's explicit request (destroys work, especially if a pre-commit hook failed and the commit didn't actually happen)
- **Never use `--no-verify`** to bypass hooks — if a hook fails, fix the underlying issue
- **Never use `git add -A`** or `git add .`
- **Never commit secrets** (`.env`, `credentials.json`, API keys, tokens)

## Pre-Commit Hook Failure

If a pre-commit hook (ktlint, test, etc.) fails:
1. Read the failure message
2. Fix the underlying issue
3. Re-stage the fix
4. Create a NEW commit (NOT `--amend`) — the previous commit never happened because the hook blocked it

## Post-Commit

After committing, update session log in `docs/obsidian/06-sessions/` with the commit SHA. The `update-obsidian-vault` skill handles this at CLOSE phase if you haven't done it manually.
