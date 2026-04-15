---
name: branch
description: Create a new git branch following the project naming convention and switch to it. Use when Islom wants to start new work. Triggers include phrases like "new branch", "create branch", "start a feature", "checkout branch", "branch for".
allowed-tools: Read, Bash, Grep
---

# Create Branch

Create a new feature/fix/refactor/chore branch with a consistent name.

## Process

### 1. Verify Clean Starting State

```bash
git status                         # must be clean
git branch --show-current          # should be main (or dev if you use it)
git pull origin main               # latest before branching
```

If dirty: stop and tell Islom to commit or stash.

### 2. Determine Branch Name

Branch prefix by type:
- `feature/<slug>` — new feature or capability
- `fix/<slug>` — bug fix
- `refactor/<slug>` — non-behavioral cleanup
- `chore/<slug>` — build, deps, tooling
- `docs/<slug>` — documentation only

Slug rules:
- lowercase
- kebab-case
- short but descriptive (3-5 words max)
- no issue numbers in the slug (use commit body instead)

Examples:
- `feature/multi-account-reconnect-backoff`
- `fix/audio-sdp-lan-address`
- `refactor/di-module-split`
- `chore/bump-kotlin-2-3-0`

### 3. Create and Switch

```bash
git checkout -b <branch-name>
```

### 4. Initial Commit (Optional)

If Islom wants to start with an empty placeholder (e.g., to open a draft PR immediately):

```bash
git commit --allow-empty -m "chore: start <slug> branch"
```

Otherwise, wait for real changes before first commit.

### 5. Report

Print:
- Branch name
- Base branch and its SHA
- Next suggested action: "start implementing the feature, then `commit` when ready"

## Rules

- **Never branch from a dirty tree** — stash or commit first
- **Never branch from an outdated base** — pull first
- **One branch = one logical unit of work** — don't stack unrelated changes
