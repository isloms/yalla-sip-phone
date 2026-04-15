---
title: "Patterns Index"
last_verified_sha: TBD
last_updated: 2026-04-15
last_author: claude
status: draft
tags: [patterns, index]
---

# Patterns

Recurring patterns in yalla-sip-phone. Many of these mirror rules in `.claude/rules/`.

## Expected Pages

- `pjsip-wrapper-lifecycle.md` — AtomicBoolean deleted, compareAndSet destroy, pre/post dispatcher check
- `swig-callback-snapshot.md` — how to safely escape SWIG callback params
- `compose-popup-vs-dialog.md` — when to use Popup vs DropdownMenu vs DialogWindow
- `stringresources-i18n.md` — Uzbek/Russian localization pattern
- `koin-module-split.md` — how the 7-module DI split is organized
- `multi-sip-account-management.md` — SipAccountManager pattern with exponential-backoff reconnect
