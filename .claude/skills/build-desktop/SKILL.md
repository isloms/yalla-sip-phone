---
name: build-desktop
description: Build a Compose Desktop distribution (dmg/msi/deb) for the current OS, or for a specified target. Use when Islom wants to produce a distributable artifact. Triggers include phrases like "build dmg", "build msi", "package the app", "build distribution", "make installer", "build release".
allowed-tools: Read, Bash, Glob, Grep
---

# Build Desktop Distribution

Build a distributable installer for yalla-sip-phone for the current OS (or a specified target).

## Process

### 1. Determine Target

- macOS: `dmg`
- Windows: `msi` (must run from Windows host)
- Linux: `deb`

If the user didn't specify, use the current OS.

### 2. Preconditions

```bash
git status                       # clean working tree
./gradlew test                   # tests pass (all 259)
```

**Note**: ktlint / detekt are NOT wired up in this project. Do not gate on `./gradlew ktlintCheck` — the task doesn't exist. If/when lint is added, re-introduce it as a precondition.

### 3. Build

```bash
# Current OS
./gradlew packageDistributionForCurrentOS

# Or specific platform
./gradlew packageDmg             # macOS
./gradlew packageMsi             # Windows (on Windows host)
./gradlew packageDeb             # Linux
```

### 4. Verify Output

```bash
ls -lh build/compose/binaries/main/dmg/*.dmg    # macOS
ls -lh build/compose/binaries/main/msi/*.msi    # Windows
ls -lh build/compose/binaries/main/deb/*.deb    # Linux
```

### 5. SHA256

Always compute and print the SHA256 of the artifact — it's part of the no-code-signing distribution flow:

```bash
shasum -a 256 build/compose/binaries/main/dmg/*.dmg
```

### 6. Report

Print:
- Target OS and artifact path
- File size
- SHA256
- Reminder: **This artifact is unsigned.** Users will see SmartScreen / Gatekeeper warnings on first install. Cert-free mitigations (MOTW strip, hash verify) happen in `publish-release`.

## Non-goals

- Do NOT publish or upload — that's `publish-release`'s job
- Do NOT skip test runs — packaging broken code is worse than not packaging
- Do NOT invoke `ktlintCheck` or `detekt` — neither is wired up
