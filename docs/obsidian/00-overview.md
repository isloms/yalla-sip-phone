---
title: "Overview"
last_verified_sha: TBD
last_updated: 2026-04-15
last_author: claude
status: draft
tags: [overview]
---

# yalla-sip-phone — Overview

## What It Is

Desktop VoIP softphone for Ildam's call center operators. Connects to Asterisk PBX (primary test) and Oktell (production) via PJSIP. Supports multiple SIP accounts per operator.

## Who Uses It

Internal Ildam operators. LAN distribution only — not a public product.

## Tech Stack

- **UI**: Kotlin Compose Multiplatform (Desktop)
- **SIP stack**: PJSIP (C, compiled from `~/Ildam/pjproject/`) via SWIG Kotlin bindings
- **Networking**: Ktor CIO (for backend auth API, separate from SIP)
- **DI**: Koin, split into 7 modules (see [[01-architecture/README|Architecture]])
- **Auth flow**: JCEF embedded browser (OAuth)
- **Packaging**: Compose Desktop's `packageDistributionForCurrentOS` (dmg/msi/deb)

## Status

- **Active development**: feature branch `feature/manual-multi-sip-testing`, 249 commits in 9 days around Apr 4-13, 2026
- **Test coverage**: 167 test methods, ~4,366 lines of test code
- **Build state**: Android/iOS doesn't apply (desktop-only), build passes for macOS / Windows / Linux distributions
- **Audio state**: blocked by Asterisk NAT config (server-side issue, not client code)

## Current Blocker — READ BEFORE "FIXING" AUDIO

**Asterisk SDP advertises public IP (87.237.239.18) instead of LAN (192.168.30.103).**

Fix is server-side: Asterisk admin must add `localnet=192.168.30.0/24` to pjsip config.

**Our conference-bridge routing is verified correct.** Do not "fix" it.

## No Code Signing

Ildam policy: this product ships without Authenticode, Apple notarization, or any paid code-signing certs. See `memory/product_stance_no_code_signing.md`. Cert-free mitigations: MOTW stripping, SHA256 hash verify, LAN-only distribution.

## Outstanding Work

1. Audio end-to-end testing (blocked on server-side Asterisk fix)
2. Auto-update mechanism — spec exists at `docs/planned/auto-update.md`, not yet implemented
3. `YallaDropdownWindow` anchored DialogWindow wrapper (ui-layer-rewrite leftover)
4. `feature/manual-multi-sip-testing` branch needs merge to main

## Servers

| Server | Host | Port | Role |
|--------|------|------|------|
| Asterisk/Issabel | 192.168.30.103 | 5060 | Primary test PBX (public: 87.237.239.18) |
| Oktell | 192.168.0.22 | 5060 | Production PBX |

Test extensions: `101`, `102`, `103`.
