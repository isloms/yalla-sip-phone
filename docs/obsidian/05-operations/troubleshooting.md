---
last_verified_sha: bfb118c
last_updated: 2026-04-16
last_author: claude
status: current
---

# Troubleshooting

## MSI Auto-Update Failures (msiexec 1603)

See [[06-sessions/2026-04-16-msi-update-debugging]] for the full debugging story.

### Cross-context upgrade (Error 1316)

**Symptom:** msiexec log shows `Error 1316. The specified account already exists.` and `FindRelatedProducts: current install is per-machine. Related install for product ... is per-user. Skipping...`

**Cause:** Windows Installer cannot upgrade across installation contexts. If the old version was installed per-machine (HKLM) and the new one tries per-user (HKCU) or vice versa, `ProcessComponents` fails because components are registered in the other context.

**Fix:** Never pass `ALLUSERS=1` in the bootstrapper. Let the MSI default to per-user (set by jpackage `perUserInstall = true`). If someone installed per-machine by running the MSI as admin, they must manually uninstall first.

### Pending reboot poisons installs

**Symptom:** msiexec log shows `MsiSystemRebootPending = 1` and installs fail even though nothing seems wrong.

**Cause:** A prior failed install left `PendingFileRenameOperations` in the registry.

**Fix:** Reboot the machine. Check with: `reg query "HKLM\SYSTEM\CurrentControlSet\Control\Session Manager" /v PendingFileRenameOperations`

### Bootstrapper fix doesn't take effect

**Symptom:** Fixed the bootstrapper code, rebuilt, but the update still uses old behavior.

**Cause:** The INSTALLED app copies ITS OWN bootstrapper (from `app/resources/`) to `%TEMP%` before launching it. The update MSI's bootstrapper is irrelevant — it's the installed version's binary that runs.

**Fix:** Both the installed version and the update must be built from the same codebase containing the fix. CI-built versions with old bootstrapper binaries cannot self-update correctly.

### File locks during MSI install

**Symptom:** msiexec 1603 with Restart Manager timeouts, files "in use".

**Cause:** JCEF child processes (`jcef_helper.exe`) or the bootstrapper itself hold file locks inside the install directory.

**Fixes applied:**
1. Bootstrapper copies itself to `%TEMP%` before launch (doesn't lock install tree)
2. `onBeforeExit` callback does graceful JCEF/PJSIP shutdown before `exitProcess(0)`
3. Bootstrapper kills orphaned `jcef_helper` and `YallaSipPhone` processes after parent exit
4. Bootstrapper closes its own log file before running msiexec (log lives inside install tree)
