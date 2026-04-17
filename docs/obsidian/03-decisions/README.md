---
title: "Decisions Index"
last_verified_sha: 4bff1ba
last_updated: 2026-04-17
last_author: claude
status: current
tags: [decisions, index, adr]
---

# Architecture Decision Records

New ADRs go here. Historical decisions are recorded in individual files named `YYYY-MM-DD-<slug>.md`.

## Format

```markdown
---
title: "Descriptive title"
date: YYYY-MM-DD
status: proposed | accepted | superseded-by-<slug> | deprecated
supersedes: (if any)
last_verified_sha: <HEAD at decision time>
tags: [decision, adr]
---

## Context
## Decision
## Alternatives Considered
## Consequences
## Review Date
```

## Standing Policies (not session ADRs)

These are product-level decisions that affect all sessions:

- **No code signing** — see `~/.claude/projects/-Users-macbookpro/memory/product_stance_no_code_signing.md`
- **Abandoned: callback marshaling to pjDispatcher via launch** — the current design runs callbacks synchronously because SWIG pointers invalidate. See `rules/pjsip-threading.md`.

## Session ADRs

- [[2026-04-17-sip-clean-code-decomposition]] — Decompose SIP-layer god-classes into six focused single-responsibility components (accepted, 2026-04-17)
