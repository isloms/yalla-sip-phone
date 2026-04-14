# `MIN_SUPPORTED_VERSION` — force-upgrade floor policy

The top-level `MIN_SUPPORTED_VERSION` file at the repo root holds a single
strict-semver string (e.g. `1.0.2`). The `release.yml` CI workflow reads it
and injects it into every generated release-notes template so the backend
team knows which value to publish in the manifest.

## What the field does (client side)

Every manifest response the backend serves includes `release.minSupportedVersion`.
When a client polls and receives a manifest, `UpdateManager` compares the
client's own current version against this floor:

- `currentVersion >= minSupportedVersion` — the update is presented as a
  normal optional upgrade. The operator can click **Later** and keep
  working on the current version.
- `currentVersion < minSupportedVersion` — the update is a **force upgrade**.
  The dialog's **Later** button is hidden. The operator can only click
  **Install** — there's no way to stay on the old version.

The active-call gate (invariant I1) is still respected: even a force
upgrade waits until the call ends before running `msiexec`. Force upgrade
affects UX (no Later button), not business invariants.

## When to bump `MIN_SUPPORTED_VERSION`

**Do NOT bump** for normal patch or minor releases. If you're just shipping
a bug fix or a new feature, leave the file alone — operators should be able
to decline the update if they want.

**DO bump** when:

1. **Security fix** that must reach 100% of the fleet within a day.
   Example: pjsip TLS CVE — every operator needs the fixed client right
   now, no "Later" allowed.

2. **Backend contract change** that the old client cannot parse. Example:
   if we someday change the wrapped envelope shape, or add a required
   field, older clients will fail to parse and silently stop updating.
   Bump the floor to the first version that understands the new shape,
   so any operator still on the old client sees a forced upgrade before
   their manifest polls start failing.

3. **Known dead versions**. Example: v1.0.0 and v1.0.1 are broken at
   runtime (cannot launch JVM because pjsip native DLLs were missing
   from the MSI). Setting the floor to `1.0.2` means any operator who
   manually installs the broken versions gets an immediate forced
   upgrade when their manifest poll returns.

4. **Breaking UI change that needs retraining**. Example: if the entire
   dispatcher-panel navigation is redesigned and operators need the
   new version to do their job, force everyone.

## How to bump

```bash
# 1. Decide the new floor. This is the version BELOW which clients get
#    a forced upgrade. Usually the version you are about to tag.
echo "1.2.0" > MIN_SUPPORTED_VERSION

# 2. Commit with a clear rationale — this is the audit trail.
git add MIN_SUPPORTED_VERSION
git commit -m "chore(release): bump min-supported-version to 1.2.0 — security fix"

# 3. Tag the release as usual. CI reads the updated file and injects
#    it into the generated release notes template.
git tag -a v1.2.0 -m "..."
git push origin main v1.2.0
```

## Current value

See the `MIN_SUPPORTED_VERSION` file at the repo root.

At the time of writing: `1.0.2` — the first release with working pjsip
native library loading. v1.0.0 and v1.0.1 are marked "DO NOT USE" on
GitHub Releases.

## Why not derive it automatically from the tag

We deliberately keep this as a human decision, not a CI-computed value:

- A script cannot know whether a given bug fix warrants forcing the fleet
  or not. That's a product call.
- Bumping the floor is a visible action in git history, auditable after
  the fact ("why did every operator get force-upgraded last Tuesday?"
  → `git log -p MIN_SUPPORTED_VERSION`).
- The default behaviour (leave the file alone) is the safe one — no
  accidental forced upgrades.
