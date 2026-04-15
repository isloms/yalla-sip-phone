---
name: publish-release
description: Publish a yalla-sip-phone release using the cert-free distribution flow. Build artifact, compute hashes, upload to LAN distribution, update manifest. Use when Islom wants to ship a new version to operators. Triggers include phrases like "release", "publish release", "ship it", "push new version", "release 1.2.0".
allowed-tools: Read, Bash, Glob, Grep, Edit
---

# Publish Release

Release yalla-sip-phone to Ildam operators. **No code signing** — this is a product-level policy. See `memory/product_stance_no_code_signing.md`.

## Cert-Free Distribution Flow

1. Build unsigned artifact (`build-desktop` skill)
2. Compute SHA256 hash
3. Upload to LAN-only distribution endpoint
4. Update hash manifest pinned in repo
5. Operators download via allow-listed URL; installer strips Mark-of-the-Web after SHA256 verify
6. Tag release in git

## Preconditions

- Working tree clean
- On `main` branch, up to date with origin
- Previous release version known (from git tag `v*`)
- New version number decided (SemVer)
- `gradle.properties` version matches the new version
- Full build and tests pass
- Manual smoke test done on a local copy (Islom confirms)

## Process

### 1. Build Artifact

Invoke `build-desktop` skill (or run `./gradlew packageDistributionForCurrentOS` directly). Note the file path and SHA256.

### 2. Update Version

Bump version in `gradle.properties` (or wherever the version lives) if not already done.

```bash
git add gradle.properties
git commit -m "chore: release v<version>"
```

### 3. Upload to LAN Distribution

The upload target is on Ildam's internal network. Specific command depends on infra — ask Islom if unclear. Typical:

```bash
scp build/compose/binaries/main/dmg/yalla-sip-phone-<version>.dmg \
    islom@distribution.ildam.local:/srv/releases/
```

### 4. Update Hash Manifest

The hash manifest pins legitimate downloads. Update it with the new hash:

```bash
# Example manifest format (adjust to real layout)
cat >> docs/releases/manifest.json <<EOF
{
  "version": "<version>",
  "date": "$(date -u +%Y-%m-%d)",
  "platforms": {
    "macos": {
      "url": "https://distribution.ildam.local/yalla-sip-phone-<version>.dmg",
      "sha256": "<hash>"
    },
    "windows": { ... },
    "linux": { ... }
  }
}
EOF

git add docs/releases/manifest.json
git commit -m "chore: add v<version> to release manifest"
```

### 5. Tag the Release

```bash
git tag v<version>
git push origin main v<version>
```

### 6. Notify Operators

Post the release note (in the internal channel the team uses) with:
- Version number
- What changed (one-liner per commit since last tag)
- Download URL
- SHA256 hash for verification
- Known issues (if any)

### 7. Update Obsidian Vault

Create a release entry in `docs/obsidian/05-operations/releases.md` with version, date, commits, artifact paths, hashes. This is part of CLOSE — the `update-obsidian-vault` skill will handle it if invoked.

## Important

- **SmartScreen warning is expected** on Windows first install — document in release notes
- **Gatekeeper warning is expected** on macOS first install — document
- **Month-1 AV false-positive friction is known** — don't panic, don't buy a cert, just tell operators to whitelist in their AV
- **Never, ever, imply a cert is coming** — policy stance: no cert ever, not just "not yet"

## Non-goals

- Do NOT sign the binary (policy)
- Do NOT upload to public CDNs or GitHub Releases (LAN-only for operators)
- Do NOT release without manual smoke test — we cannot verify audio via CI due to the Asterisk NAT issue
