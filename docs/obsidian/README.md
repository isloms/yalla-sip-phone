---
title: "yalla-sip-phone"
last_verified_sha: TBD
last_updated: 2026-04-15
last_author: claude
status: draft
tags: [project, entry]
---

# yalla-sip-phone

**Desktop VoIP softphone** for Ildam call center operators. Kotlin Compose Desktop + PJSIP (via SWIG-generated Kotlin/JNI bindings). Connects to Oktell and Asterisk PBX.

This is the Obsidian vault for `yalla-sip-phone`. The existing `docs/architecture.md`, `docs/pjsip-guide.md`, `docs/testing.md`, `docs/js-bridge-api.md`, and `docs/windows-build.md` are the stable long-form reference — this vault holds new decisions, session logs, and cross-links.

## Navigation

- [[00-overview]] — what the softphone is, who uses it, status, current blockers
- [[01-architecture/README|Architecture]] — DI topology, PJSIP integration layer, threading model
- [[02-patterns/README|Patterns]] — Compose Desktop, PJSIP lifecycle, JCEF bridge
- [[03-decisions/README|Decisions]] — ADRs
- [[04-features/README|Features]] — multi-SIP, login, call UI, settings
- [[05-operations/README|Operations]] — build, release (cert-free), distribution
- [[06-sessions/README|Sessions]] — auto-maintained session logs

## Important Existing Docs (outside this vault)

- `docs/architecture.md` — module map, up to date
- `docs/pjsip-guide.md` — SWIG/threading rules, up to date
- `docs/testing.md` — test framework + counts, up to date
- `docs/js-bridge-api.md` — JCEF bridge API reference
- `docs/windows-build.md` — pjsip Windows compilation
- `docs/planned/auto-update.md` — future spec
- `docs/archive/` — historical plans/audits (do not delete)

## Related

- Source: `~/Ildam/yalla/yalla-sip-phone/` (personal fork: `isloms/yalla-sip-phone`)
- Upstream pjsip source: `~/Ildam/pjproject/`
- Parent meta-vault: `~/Ildam-Brain/projects/yalla-sip-phone`
