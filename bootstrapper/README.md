# yalla-update-bootstrap

C# .NET 8 Windows helper that runs `msiexec` to install the yalla-sip-phone MSI after the main app exits.

## Why

Windows file locks from JCEF (`libcef.dll`, `jcef_helper.exe`, `jcef-cache` lock) and pjsip (`libpjsua2.dll` mapped into the JVM address space) prevent an in-place MSI upgrade while the parent process is alive. Windows RestartManager cannot reliably release these within its ~30 s shutdown window. The bootstrapper waits for the parent PID to actually die, runs msiexec, and relaunches the new app. Full context in `docs/superpowers/specs/2026-04-13-auto-update-design.md` §11.

## Build

```bash
cd bootstrapper
dotnet publish -c Release -r win-x64 --self-contained=false -p:PublishSingleFile=true
cp bin/Release/net8.0-windows/win-x64/publish/yalla-update-bootstrap.exe \
   ../src/main/resources/bootstrapper/yalla-update-bootstrap.exe
```

The resulting single-file exe is committed to `src/main/resources/bootstrapper/` so the Kotlin/Gradle build works without a .NET toolchain on the dev machine.

## CLI

```
yalla-update-bootstrap \
  --msi <absolute-path-to-downloaded.msi> \
  --install-dir <absolute-path-to-current-install> \
  --parent-pid <pid-of-yalla.exe> \
  --expected-sha256 <hex64> \
  --log <absolute-path-to-log-file>
```

All args are required.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Install succeeded (or succeeded with reboot requested — `3010`) |
| `1` | Fatal exception (see log) |
| `2` | MSI file missing |
| `3` | SHA256 mismatch — MSI was tampered with or corrupted between verify-time and bootstrap-time |
| `4` | Failed to start msiexec |
| `64` | Bad CLI usage |
| other | msiexec's own exit code (e.g. `1602` user cancelled, `1618` another install in progress, `1603` fatal) |

## Behaviour

1. Parse args + open log file.
2. Re-verify SHA256 of the MSI (defense in depth).
3. Wait for parent process exit (60 s max).
4. Sleep 3 s for file locks to release.
5. Strip `Zone.Identifier` alternate data stream (cert-free SmartScreen mitigation).
6. Quarantine current install to `backup/<timestamp>/`.
7. Run `msiexec /i <msi> /qn /norestart REBOOT=ReallySuppress /L*v <log>`.
8. On success (`0` or `3010`): delete backup, relaunch new `YallaSipPhone.exe`, exit 0.
9. On user cancel / conflicting install: relaunch old exe without touching backup.
10. On any other failure: restore the backup into install dir, relaunch old exe, propagate exit code.
