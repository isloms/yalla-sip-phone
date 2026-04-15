---
name: pr
description: Prepare and create a pull request for the current branch. Analyzes commits since base, generates title and description, creates PR via gh CLI. Use when Islom wants to open a PR. Triggers include phrases like "create pr", "open pr", "make a pr", "pull request", "push pr".
allowed-tools: Read, Bash, Glob, Grep
---

# Create Pull Request

Create a PR for the current branch against the base branch (default: `main`).

## Process

### 1. Check State

```bash
git status                                        # clean working tree
git branch --show-current                         # current branch
git log main..HEAD --oneline                      # commits since divergence
git diff main...HEAD --stat                       # files changed
gh pr list --head $(git branch --show-current)    # is a PR already open?
```

If a PR is already open for this branch, stop and tell Islom — offer to update it instead.

### 2. Push If Needed

```bash
git push -u origin HEAD                           # push and set upstream
```

### 3. Analyze All Commits

Read every commit message in `git log main..HEAD`. Group by type (feat, fix, refactor, etc.). The PR description should cover ALL commits, not just the most recent one.

### 4. Generate PR Title

Max 70 chars, conventional format:
- Single-commit branch: match the commit's type/scope/subject
- Multi-commit branch: pick the dominant type, summarize scope

Examples:
- `feat(sip): multi-account registration with exponential backoff`
- `fix(audio): correct SDP address selection for LAN calls`
- `refactor(di): split DI into 7 focused modules`

### 5. Generate PR Body

```markdown
## Summary

<2-3 sentences explaining what and why — NOT what's already in commit messages>

## Changes

- <grouped bullet list of key changes>
- <another bullet>

## Testing

- [ ] Unit tests pass: `./gradlew test`
- [ ] Lint passes: `./gradlew ktlintCheck`
- [ ] Build passes: `./gradlew build`
- [ ] Manual smoke test done (login + register + test call)
- [ ] Obsidian vault updated with session log

## Screenshots

<if UI changes, include screenshots. If not, write "N/A — no UI changes">

## Notes for Reviewer

<anything non-obvious, risk areas, specific things to look at>
```

### 6. Create the PR

```bash
gh pr create --title "<title>" --body "$(cat <<'EOF'
## Summary

<body content>

...
EOF
)" --base main
```

For a draft: add `--draft`.

### 7. Report

Print the PR URL. Remind Islom to:
- Tag reviewers if appropriate
- Link to related issues
- Update the Obsidian vault session log with PR link

## Rules

- **Never force-push** to main or dev
- **Default base is `main`** unless Islom says otherwise
- **No PR without tests** — if the branch has implementation but no tests, stop and fix that first
- **Include manual smoke test checkbox** — even if Claude cannot tick it, the checkbox forces Islom to actually do it before merge (since CI can't test audio)

## Non-goals

- Do NOT merge the PR from this skill — merging is Islom's explicit action after review
- Do NOT push to main directly, ever
