# Auto-Update Backend Integration Guide

**For:** Backend team implementing the server side of yalla-sip-phone auto-update
**From:** yalla-sip-phone client team
**Client version:** 1.0.0 (first release with updater wired in)
**Status:** Ready to implement — client is merged to `main`

---

## 1. Overview — what this feature does

yalla-sip-phone is a Compose Desktop Windows app installed on Ildam call-center operator workstations. Today, every new release requires IT to re-install the MSI on every machine by hand. This is slow and error-prone.

Auto-update closes that loop: the running app polls a single backend endpoint once an hour, downloads the new MSI over HTTPS when one is available, verifies it, waits for the operator to finish any active SIP call, and installs the new version via a background helper.

**You (the backend team) implement:** one REST endpoint + one file-hosting location. No business logic, no database tables, no per-user state. The client does everything else.

**The single hard rule:** the API response you design must never brick a client. Clients in the field will read whatever you return for years. If the contract breaks, operators stop getting updates until IT reinstalls manually.

---

## 2. The contract — one endpoint + one file

### 2.1 Endpoint

```
GET /api/v1/app-updates/latest
```

Client sends these headers on every call. They are metadata — you log them, optionally use them for rollout gating, but they are NOT part of the URL or the cache key unless you explicitly `Vary` on them:

| Header | Example | Meaning |
|---|---|---|
| `X-App-Version` | `1.2.0` | Client's currently-installed version |
| `X-App-Platform` | `windows` | Always `windows` for MVP. Reserved for future macos/linux. |
| `X-App-Channel` | `stable` or `beta` | Which release channel the client is on |
| `X-Install-Id` | `c1f8b4e2-3d4a-4f11-9e12-a7b6c5d4e3f2` | Stable anonymous UUID generated once per machine, persists across upgrades. Use this as the join key for "who is on what version" queries — NOT hostname, NOT username. |
| `User-Agent` | `YallaSipPhone/1.2.0 (windows)` | Standard |

No JWT auth is required for MVP. The endpoint is public on the intranet. If you want to gate it behind your existing auth for audit/rate-limiting, tell us and the client will forward its existing JWT — it already has one after operator login.

### 2.2 Response — always HTTP 200 OK, JSON envelope

**Do not use 204.** Always return 200 with a JSON body. The envelope shape lets you add fields over time without breaking clients in the field.

#### 2.2.1 Case A — client is current (no update)

```json
{
  "updateAvailable": false
}
```

#### 2.2.2 Case B — update is available

```json
{
  "updateAvailable": true,
  "release": {
    "version": "1.2.0",
    "minSupportedVersion": "1.0.0",
    "releaseNotes": "Bug fixes and stability improvements.",
    "installer": {
      "url": "https://downloads.yalla.uz/releases/YallaSipPhone-1.2.0.msi",
      "sha256": "abc123def456...",
      "size": 358400000
    }
  }
}
```

### 2.3 Field rules — STRICT, no exceptions

The client validates every field before trusting it. Malformed manifests are treated as "no update" and logged — they do not crash the app, but they DO mean the operator is stranded on the old version. Keep these invariants:

| Field | Type | Required | Rules |
|---|---|---|---|
| `updateAvailable` | boolean | yes | `false` ⇒ `release` may be omitted |
| `release.version` | string | yes when `updateAvailable=true` | **Strict semver `MAJOR.MINOR.PATCH`**, all digits, no `v` prefix, no `-beta.1` suffix, no build metadata. The client's semver comparator is intentionally strict — `1.2.0`, `2.0.3`, `10.11.12` all work; `v1.2`, `1.2.0-rc.1`, `1.2` do not. |
| `release.minSupportedVersion` | string | yes when `updateAvailable=true` | Same semver format. MUST be `≤ release.version`. Floor version — if the client's current version is strictly less, the update will be presented as required (no "Later" button). |
| `release.releaseNotes` | string | no | Plain text. No HTML, no markdown rendering, no line-length limit beyond reason. Keep it short — operators won't read more than one sentence. |
| `release.installer.url` | string | yes | **Absolute HTTPS URL**, `http://` is rejected. The host MUST be in the client's pinned allow-list (see §2.4). Currently the allow-list is `downloads.yalla.uz`, `updates.yalla.local` — if you host elsewhere, tell us and we'll add it (it is pinned in source, so a client release is required). |
| `release.installer.sha256` | string | yes | **Exactly 64 lowercase hex chars** (`^[0-9a-f]{64}$`). SHA256 of the MSI file's raw bytes. This is the ONLY byte-integrity check we have — no code signing, no GPG, no manifest signing — so correctness here is security-critical. |
| `release.installer.size` | number | yes | File size in bytes. `1 ≤ size < 2 GiB (2147483648)`. JSON number (not string). |

**Anything outside these rules → the client treats the manifest as malformed and skips this poll cycle.** Log the validation error server-side so we can catch regressions early.

### 2.4 Download-host allow-list — pinned in client source

The `installer.url` host is checked against a hard-coded list in the Kotlin source before the client ever opens the download. This is our main defense against a compromised backend serving a URL pointing at an attacker-controlled host.

**Current allow-list** (`src/main/kotlin/uz/yalla/sipphone/domain/update/UpdateManifest.kt`):

```kotlin
internal val UPDATE_URL_ALLOWLIST: List<String> = listOf(
    "downloads.yalla.uz",
    "updates.yalla.local",
)
```

If you host at a different URL, **tell us before the first release**. Adding a host requires a client rebuild + full fleet re-deploy.

### 2.5 Response headers — strongly recommended

```
Content-Type: application/json
Cache-Control: private, max-age=60, must-revalidate
Vary: X-App-Version, X-App-Platform, X-App-Channel
```

`max-age=60` lets you push an emergency release within a minute; longer is fine too. `Vary` matters if you put anything in front of this endpoint (CDN, reverse proxy, nginx) that caches on headers — otherwise a macOS client in the future could get a Windows MSI URL. ETag + `If-None-Match` + `304 Not Modified` is optional but welcome.

### 2.6 Forward-compat rules — MUST be obeyed forever

These are absolute. Once a version of the client is in the field, it will read this endpoint for years. Break them, and the fleet stops updating:

1. **Never remove an existing field.**
2. **Never change a field's type or semantics.** If you need different semantics, add a new field with a new name.
3. **Never repurpose an old field name for new meaning.** Old clients will misinterpret it.
4. **Clients will silently ignore unknown fields.** This is how you add new functionality — add new fields with safe defaults.
5. **`/api/v1/` lives forever.** A `/api/v2/` endpoint is only legitimate for a hard-breaking change, and even then, `/v1/` must keep working because old clients only know `/v1/`.

If in doubt, ADD a field, never MODIFY one.

---

## 3. Status codes — when to return what

| Code | When | Client behavior |
|---|---|---|
| `200` | Always, for both "update available" and "no update" | Normal flow |
| `4xx` | Client sent a bad request (should never happen under correct spec) | Client logs, gives up for this cycle, retries next hour |
| `5xx` | Your backend is broken | Client silently fails this cycle, retries with exponential backoff (30 s → 60 s → 2 min → ... → 30 min), resumes normal hourly polling on success |

**Never return `204 No Content` for "no update".** The client is coded to expect a 200 JSON envelope; a 204 counts as a malformed response and gets logged as an error even if the behavior is the same.

---

## 4. Hosting the MSI — where the actual installer file lives

The `installer.url` in your manifest response points at the binary. Where that file actually lives is your decision — the client doesn't care as long as it's:

- **HTTPS** with a certificate the OS trust store accepts (no self-signed certs — the client uses default TLS, not cert pinning).
- **Reachable from the operator LAN**. Current deployment is LAN-only; operators don't go to the public internet to fetch updates.
- **On a host from the allow-list** (§2.4).
- **Supports HTTP `Range` requests** (`Range: bytes=1048576-` → `206 Partial Content`). The client resumes interrupted downloads this way. An MSI is ~140–360 MB; a flaky LAN connection WILL drop it mid-transfer. If the hosting server does not honor `Range`, the client falls back to starting over from byte 0 every retry, which is painful on slow links but still correct.
- **Serves the file with a correct `Content-Length` header** if possible. (The client does not trust it for integrity — SHA256 is the only integrity source — but it's used for the progress bar.)

### 4.1 Common choices

| Option | Good for | Notes |
|---|---|---|
| nginx static file server | Simplest | Supports Range out of the box. `autoindex off;`, `sendfile on;`. 5 min of config. |
| nginx + MSI directory | Very common | `alias /var/yalla/releases/;` |
| S3-compatible object storage (MinIO, DO Spaces, Cloudflare R2) | If you already have it | Range supported by default. Put a CDN in front if public access becomes a problem later. |
| Your existing API server, same host | OK if your framework streams files efficiently | Use sendfile / zero-copy. Don't buffer 300 MB in memory. |
| GitHub Releases / GitLab packages | **Avoid** — requires public auth tokens baked into the client, which leaks, or gets rate-limited |

**The client will NOT download from the same URL twice unless the sha256 differs** — so you do not need to worry about cache eviction. Once an operator has `YallaSipPhone-1.2.0.msi` validated and staged, they won't fetch it again. A new release with a new version → new URL → fresh download.

### 4.2 File naming convention (client does not depend on this)

We suggest `YallaSipPhone-<version>.msi` (e.g. `YallaSipPhone-1.2.0.msi`) so humans can recognise them in directory listings. The client reads `installer.url` verbatim — you can name the files anything.

### 4.3 Atomic publish — critical

When you publish a new release, the order of writes matters:

1. Upload the new `.msi` file to its final URL, fully.
2. Compute its SHA256 after the upload completes, not from a local copy (detects mid-upload corruption).
3. **Only after the MSI file is fully uploaded and SHA256 verified**, update the manifest endpoint to return the new `release` object.

If you update the manifest first and the MSI upload fails halfway, every client polling during that window will get a 404 or a truncated file → SHA mismatch → `Failed(VERIFY)` state → retry next cycle. Harmless but noisy in logs.

**Two-phase publish example (admin CLI or script):**

```bash
# Stage
scp YallaSipPhone-1.2.0.msi backend:/var/yalla/releases/staging/
ssh backend 'sha256sum /var/yalla/releases/staging/YallaSipPhone-1.2.0.msi'
# → verify it matches what your CI produced

# Promote atomically
ssh backend 'mv /var/yalla/releases/staging/YallaSipPhone-1.2.0.msi \
                 /var/yalla/releases/YallaSipPhone-1.2.0.msi'

# Update manifest (last write, always)
curl -X POST https://api.yalla.uz/admin/app-updates \
     -H 'Authorization: Bearer <your admin token>' \
     -d '{"channel":"stable","version":"1.2.0","sha256":"...","size":123456,"url":"https://downloads.yalla.uz/releases/YallaSipPhone-1.2.0.msi"}'
```

Or whatever admin API you wire up for your own ops. The client doesn't care how you publish — only that the MSI at `url` matches the SHA256 in the manifest at the moment the client reads both.

---

## 5. Storage model — how you remember "what is the latest release"

Trivial. You need exactly one record per channel:

```
table app_updates_current
  channel        TEXT PRIMARY KEY  -- "stable" or "beta"
  version        TEXT NOT NULL
  min_supported  TEXT NOT NULL
  release_notes  TEXT
  installer_url  TEXT NOT NULL
  installer_sha  TEXT NOT NULL
  installer_size BIGINT NOT NULL
  published_at   TIMESTAMPTZ NOT NULL
```

Your admin CLI / script upserts a row. Your endpoint reads one row. A publish is one SQL `UPDATE`.

If you don't even want a database, a JSON file on disk works fine — the client hits the endpoint hourly per operator × ~50 operators = one request per minute aggregate. Zero load.

```
/etc/yalla/app-updates/
├── stable.json
└── beta.json
```

Then the endpoint handler is `cat /etc/yalla/app-updates/${channel}.json`.

---

## 6. Channels — stable vs beta

Two release channels:

- **`stable`** — default. Every operator is on this unless they've been manually switched. This is what the fleet sees.
- **`beta`** — for Islom + a small pilot cohort (0–3 operators). Switched via a hidden `Ctrl+Shift+Alt+B` keyboard shortcut in the app; the client sends `X-App-Channel: beta` in the header.

You return the appropriate manifest for the channel the client asked for. That's it. No auth, no feature flags, no gradual rollouts — just two independent "latest release" pointers.

Typical flow for a release:
1. Publish `1.2.0` to `beta`. Islom runs it on his machine, beta operators run it on theirs.
2. Wait 1–3 days for real-world validation.
3. Copy the same MSI URL into `stable`. Full fleet picks it up within an hour.

The client will happily switch channels at runtime (no restart needed) and pick up the channel's latest release on the next poll.

---

## 7. Observability — you already have all the telemetry you need

Every client sends a log-friendly header set on every poll (§2.1). If you log the request line, you automatically know:

- Who polled (`X-Install-Id`)
- What version they're on (`X-App-Version`)
- What channel (`X-App-Channel`)
- What time

A single SQL query answers "which machines are on which version?":

```sql
SELECT x_app_version, COUNT(DISTINCT x_install_id)
FROM update_poll_log
WHERE ts > NOW() - INTERVAL '1 hour'
GROUP BY x_app_version
ORDER BY x_app_version DESC;
```

Catch "this machine hasn't polled in a week" (might be powered off, or might have a broken updater):

```sql
SELECT x_install_id, MAX(ts) AS last_seen, MAX(x_app_version) AS last_version
FROM update_poll_log
GROUP BY x_install_id
HAVING MAX(ts) < NOW() - INTERVAL '2 days';
```

No client-side telemetry SDK, no events pipeline. The access log **is** the telemetry.

**Recommendation:** log the 5 headers + URL + timestamp + status code as structured JSON, one line per request. Rotate daily. That's the entire observability layer for auto-update.

---

## 8. Security posture — what we rely on, what we don't

### What protects the fleet today

| Control | What it prevents |
|---|---|
| HTTPS on the manifest endpoint and MSI download | Passive network snooping, casual tampering |
| SHA256 in the manifest | Byte integrity of the MSI against corruption and a casual CDN hijack |
| Download-host allow-list (pinned in client source) | A compromised manifest endpoint can't redirect clients to an attacker's host |
| Intranet-only deployment | Eliminates random external attackers; limits the attack surface to people already on the Ildam LAN |
| Client-side semver comparison + downgrade refusal | Attacker can't push an older vulnerable version as "latest" |
| `minSupportedVersion` floor | Lets you force operators off a known-bad older version |

### What does NOT protect the fleet (documented security debt)

- **No code signing (Authenticode).** The MSI is unsigned. Operators see SmartScreen "Unknown publisher" warnings on first install. This is a product stance — Ildam does not buy code-signing certificates.
- **No manifest signing (minisign/GPG).** If your backend or the database row for `app_updates_current` is compromised, an attacker can point clients at any MSI within the allow-list. The allow-list + HTTPS limits blast radius, but a determined attacker with backend access owns the fleet.
- **No TLS certificate pinning.** We trust the OS certificate store. Fine for intranet HTTPS, might need revisiting if the service ever moves to public internet.

Treat the backend with care accordingly — access to the "what is the latest release?" row is effectively access to the fleet. Keep the admin API behind your existing internal auth.

---

## 9. Rollback — how to un-publish a broken release

There is no client-side rollback mechanism. The client refuses downgrades (`if newVersion ≤ currentVersion → NoUpdate`) to prevent replay/downgrade attacks. Rollback is implemented as **forward-roll**: publish a new version number that contains the old code.

**Scenario:** `1.2.0` is broken. 30% of operators have already auto-installed it.

**Procedure:**
1. Build `1.2.1` from the `1.1.9` source tree (or a minimal hotfix branch).
2. Version the MSI as `1.2.1`.
3. Publish `1.2.1` to whichever channel is broken.
4. Within ~1 hour, every operator auto-updates from the broken `1.2.0` to the working `1.2.1`.

This means **keep every MSI on disk** — you need the previous known-good binaries available to re-publish. Suggest keeping the last 5 MSIs per channel in `releases/archive/`.

**Speed optimization:** if you don't want to wait up to 1 hour for operators to catch the fix, you can drop the `Cache-Control` max-age to `5` temporarily during an incident. Polling will happen more often and pressure on the server is trivial.

---

## 10. The complete release-day runbook (for reference)

1. Client CI or developer runs `./gradlew packageMsi` on a Windows machine → produces `build/compose/binaries/main/msi/YallaSipPhone-<version>.msi`.
2. Compute SHA256 of that file.
3. scp/upload the MSI to your hosting location (staging dir first).
4. Verify SHA256 matches (detects upload corruption).
5. Move/rename the staging MSI to its final filename under the final directory.
6. Call your admin endpoint to upsert the `stable` or `beta` row with the new `version`, `sha256`, `size`, `url`, `release_notes`, `min_supported_version`.
7. Within a minute, the first operator polling the manifest endpoint sees the new release. Within an hour, every operator is on it.

---

## 11. What you need to decide with us

These are the open questions we need your answer to. None of them block client development (we've shipped the client with placeholders), but all of them need answers before the first real release.

| # | Question | Context | Who decides |
|---|---|---|---|
| 1 | **What URL does the manifest endpoint live at?** | Must be reachable from operator LAN. `https://api.yalla.uz/api/v1/app-updates/latest`? `https://backend.yalla.local/...`? | Backend team |
| 2 | **What host serves the MSI binary?** | Must be HTTPS, Range-capable, on the allow-list. The client allow-list currently pins `downloads.yalla.uz` and `updates.yalla.local` — we'll ship a client release to add whatever you choose if it's neither of those. | Backend team |
| 3 | **Does the manifest endpoint require JWT auth?** | Default is no (public endpoint). If your policy says "nothing unauthenticated", say so and the client will forward its operator JWT. | Backend team |
| 4 | **Who publishes releases — a human or CI?** | Doesn't affect the client. Informs what admin API / script you build on your side. | Shared |
| 5 | **Request logging — is your access log already structured enough to answer "who is on what version" via SQL?** | If yes, we're done. If no, add JSON logging with the 5 client headers + URL + timestamp + status. | Backend team |
| 6 | **Storage choice: database table, JSON files on disk, or something else?** | Any of these is fine. Just tell us so we know what the ops story looks like. | Backend team |
| 7 | **How do we wire up beta-channel operators for early-access testing?** | Client has a hidden `Ctrl+Shift+Alt+B` toggle — pilot operators switch by hand. No backend work needed unless you want it in a user record. | Shared |

---

## 12. Minimum pseudo-implementation (reference only)

In whatever language/framework you use. This is the entire server-side feature:

```python
# manifest.py — reference, not required

import json
from flask import Flask, jsonify, request

app = Flask(__name__)

# Store: one JSON file per channel, atomic writes via rename.
def load(channel):
    with open(f"/etc/yalla/app-updates/{channel}.json") as f:
        return json.load(f)

@app.route("/api/v1/app-updates/latest")
def latest():
    channel = request.headers.get("X-App-Channel", "stable")
    if channel not in ("stable", "beta"):
        return jsonify(updateAvailable=False), 200

    current = load(channel)  # {"version": "1.2.0", "minSupportedVersion": "1.0.0", ...}
    if current is None:
        return jsonify(updateAvailable=False), 200

    return jsonify({
        "updateAvailable": True,
        "release": {
            "version": current["version"],
            "minSupportedVersion": current["minSupportedVersion"],
            "releaseNotes": current.get("releaseNotes", ""),
            "installer": {
                "url": current["installerUrl"],
                "sha256": current["installerSha256"],
                "size": current["installerSize"],
            },
        },
    }), 200, {
        "Cache-Control": "private, max-age=60, must-revalidate",
        "Vary": "X-App-Version, X-App-Platform, X-App-Channel",
    }
```

That's the whole endpoint. Adapt to your framework — nothing here is exotic.

---

## 13. Testing the integration before first release

### 13.1 Smoke test the manifest endpoint

```bash
curl -s https://api.yalla.uz/api/v1/app-updates/latest \
     -H "X-App-Version: 0.0.0" \
     -H "X-App-Platform: windows" \
     -H "X-App-Channel: stable" \
     -H "X-Install-Id: test-install-id" \
     -H "User-Agent: curl-smoke-test" \
     | jq
```

Expected:

```json
{
  "updateAvailable": true,
  "release": {
    "version": "1.0.0",
    "minSupportedVersion": "1.0.0",
    "releaseNotes": "Initial release",
    "installer": {
      "url": "https://downloads.yalla.uz/releases/YallaSipPhone-1.0.0.msi",
      "sha256": "<64 lowercase hex>",
      "size": 140000000
    }
  }
}
```

### 13.2 Smoke test the MSI download with Range

```bash
# Full
curl -OJ "https://downloads.yalla.uz/releases/YallaSipPhone-1.0.0.msi"

# Range (simulates resume)
curl -v -H "Range: bytes=1048576-" \
     "https://downloads.yalla.uz/releases/YallaSipPhone-1.0.0.msi" \
     -o /dev/null 2>&1 | grep -i "206\|content-range"
```

You must see `HTTP/1.1 206 Partial Content` and a `Content-Range:` response header. If not, the server does not support ranges and the client will re-download from scratch on every network blip — fix this at the hosting layer (nginx, S3, whatever).

### 13.3 "no update" response

Same curl as §13.1 but with the current version in `X-App-Version`:

```bash
curl ... -H "X-App-Version: 1.0.0" ... | jq
# Expected: {"updateAvailable": false}
```

### 13.4 Client-side end-to-end test

Once the endpoint is live, Islom runs the app with the channel toggled to `beta`, points it at a `beta` release one minor version higher than `1.0.0`, and watches the badge, dialog, download, verify, install, relaunch flow.

Test checklist:

- [ ] Badge appears in toolbar within ≤ 1 h of publishing (or immediately on app start)
- [ ] Dialog shows version + release notes in Uzbek/Russian depending on locale
- [ ] Progress bar tracks download
- [ ] SHA256 matches and updater reaches `ReadyToInstall`
- [ ] Clicking "Install" on an idle operator → app exits → new exe launches
- [ ] Clicking "Install" mid-call → banner says "wait for the call to end" → installs the moment the call goes idle
- [ ] Back-to-back check at `beta` rollout time doesn't disturb a running call

---

## 14. Cheat sheet — the whole contract on one screen

```
GET /api/v1/app-updates/latest
  X-App-Version:  <client semver>
  X-App-Platform: windows
  X-App-Channel:  stable | beta
  X-Install-Id:   <client UUID>
  User-Agent:     YallaSipPhone/<semver> (windows)

--- 200 OK always ---

{ "updateAvailable": false }

  OR

{
  "updateAvailable": true,
  "release": {
    "version":             "<strict semver>",
    "minSupportedVersion": "<strict semver, ≤ version>",
    "releaseNotes":        "plain text",
    "installer": {
      "url":    "https://<allowlisted host>/.../YallaSipPhone-<version>.msi",
      "sha256": "<64 lowercase hex>",
      "size":   <positive integer, < 2 GiB>
    }
  }
}

Headers:
  Content-Type: application/json
  Cache-Control: private, max-age=60, must-revalidate
  Vary: X-App-Version, X-App-Platform, X-App-Channel
```

---

## 15. References in the client codebase

Pointers for the client-side code that consumes this contract — if you want to cross-check expected behaviour during integration:

- `docs/superpowers/specs/2026-04-13-auto-update-design.md` — full design spec (the long version of this doc)
- `src/main/kotlin/uz/yalla/sipphone/domain/update/UpdateManifest.kt` — DTO + `ManifestValidator` (all validation rules are expressed here; match these exactly)
- `src/main/kotlin/uz/yalla/sipphone/data/update/UpdateApi.kt` — HTTP call, header construction, result mapping
- `src/main/kotlin/uz/yalla/sipphone/data/update/UpdateDownloader.kt` — resumable download with Range + sidecar meta
- `src/main/kotlin/uz/yalla/sipphone/data/update/UpdateManager.kt` — full state machine
- `src/test/kotlin/uz/yalla/sipphone/data/update/UpdateApiTest.kt` — what the client considers a valid/invalid response (ktor-client-mock cases you can mirror for a backend contract test)

---

## 16. Questions? ping Islom

All ambiguities, all "does the client do X?" questions, all edge cases — ping the client team. The client is the source of truth for what this contract expects; we'll happily write you more tests, more docs, or more sample requests.

Good luck, and happy shipping.
