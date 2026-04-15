---
title: "Architecture Index"
last_verified_sha: TBD
last_updated: 2026-04-15
last_author: claude
status: draft
tags: [architecture, index]
---

# Architecture

Authoritative architecture reference: [`docs/architecture.md`](../../architecture.md). This folder holds **stamped snapshots and cross-links** for specific architectural aspects.

## Expected Pages

- `di-topology.md` — the 7 Koin modules and their relationships
- `pjsip-integration-layer.md` — how PjsipEngine / PjsipAccount / PjsipCall wrap SWIG
- `threading-model.md` — pjDispatcher, callback safety, deleted gates (mirror of `rules/pjsip-threading.md`)
- `jcef-bridge.md` — how the embedded browser talks to Kotlin (cross-ref `docs/js-bridge-api.md`)
- `module-graph.md` — internal module graph (if we split further)
