// yalla-update-bootstrap
//
// Windows helper that replaces the running yalla-sip-phone installation via
// msiexec after the main app exits. See spec §11.
//
// Why a separate process: JCEF holds native locks (libcef.dll, jcef_helper.exe,
// jcef-cache lock) and pjsip (libpjsua2.dll) is mapped into the JVM address
// space. Windows RestartManager cannot reliably release these within its
// ~30s window, and spawning msiexec from inside the running JVM produces a
// recursive process tree where msiexec's own parent is killed while it's
// running. The bootstrapper waits for the parent PID to actually die, then
// runs msiexec, then relaunches the new exe.
using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace YallaUpdateBootstrap;

internal static class Program
{
    private static StreamWriter? _log;

    private static int Main(string[] args)
    {
        var opts = ParseArgs(args);
        if (opts == null)
        {
            Console.Error.WriteLine(
                "Usage: yalla-update-bootstrap --msi <path> --install-dir <dir> " +
                "--parent-pid <pid> --expected-sha256 <hex> --log <path>");
            return 64;
        }

        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(opts.LogPath) ?? ".");
            _log = new StreamWriter(opts.LogPath, append: true) { AutoFlush = true };
            Log($"=== bootstrapper start {DateTime.Now:O} ===");
            Log($"msi={opts.MsiPath}, installDir={opts.InstallDir}, parentPid={opts.ParentPid}");

            if (!File.Exists(opts.MsiPath))
            {
                Log($"ERROR: MSI missing: {opts.MsiPath}");
                return 2;
            }

            Log("Re-verifying SHA256...");
            var actual = ComputeSha256(opts.MsiPath);
            if (!string.Equals(actual, opts.ExpectedSha256, StringComparison.OrdinalIgnoreCase))
            {
                Log($"ERROR: SHA256 mismatch. expected={opts.ExpectedSha256}, actual={actual}");
                return 3;
            }

            Log($"Waiting for parent pid {opts.ParentPid} to exit...");
            WaitForParentExit(opts.ParentPid);
            Log("Parent exited. Waiting 3s for file locks to release...");
            Thread.Sleep(3000);

            KillOrphanedProcesses();
            Thread.Sleep(2000);

            StripMarkOfTheWeb(opts.MsiPath);

            var backupDir = Path.Combine(
                Path.GetDirectoryName(opts.InstallDir) ?? opts.InstallDir,
                "backup",
                DateTime.Now.ToString("yyyyMMdd-HHmmss"));
            Log($"Quarantining old install to {backupDir}");
            try
            {
                if (Directory.Exists(opts.InstallDir))
                {
                    CopyDirectory(opts.InstallDir, backupDir);
                }
            }
            catch (Exception ex)
            {
                Log($"WARN: quarantine copy failed: {ex.Message}");
            }

            Log("Running msiexec...");
            var msiLog = Path.Combine(Path.GetTempPath(), "yalla-update-msiexec.log");
            var psi = new ProcessStartInfo("msiexec.exe")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            psi.ArgumentList.Add("/i");
            psi.ArgumentList.Add(opts.MsiPath);
            psi.ArgumentList.Add("/qn");
            psi.ArgumentList.Add("/norestart");
            psi.ArgumentList.Add("REBOOT=ReallySuppress");
            psi.ArgumentList.Add("/L*v");
            psi.ArgumentList.Add(msiLog);

            // Release install.log before msiexec — it lives inside the install
            // tree and msiexec needs exclusive access to the entire directory.
            // Without this, msiexec hits error 1306 and exits 1603.
            _log?.Flush();
            _log?.Close();
            _log = null;

            var proc = Process.Start(psi);
            if (proc == null)
            {
                _log = new StreamWriter(opts.LogPath, append: true) { AutoFlush = true };
                Log("ERROR: failed to start msiexec");
                TryRestore(backupDir, opts.InstallDir);
                return 4;
            }
            proc.WaitForExit();
            var exit = proc.ExitCode;

            _log = new StreamWriter(opts.LogPath, append: true) { AutoFlush = true };
            Log($"msiexec exit: {exit}");

            // Success codes: 0 OK, 3010 success + reboot required
            if (exit == 0 || exit == 3010)
            {
                Log("Install success. Cleaning backup.");
                TryDeleteDir(backupDir);
                LaunchApp(opts.InstallDir);
                return 0;
            }

            // User cancel / conflicting install — don't restore, relaunch old
            if (exit == 1602 || exit == 1618)
            {
                Log("User cancelled or another install in progress; relaunching old exe.");
                LaunchApp(opts.InstallDir);
                return exit;
            }

            // Anything else: restore quarantine and relaunch
            Log("Install failed; restoring quarantine.");
            TryRestore(backupDir, opts.InstallDir);
            LaunchApp(opts.InstallDir);
            return exit;
        }
        catch (Exception ex)
        {
            Log("FATAL: " + ex);
            return 1;
        }
        finally
        {
            _log?.Flush();
            _log?.Close();
        }
    }

    // ----- helpers -----

    private record Options(string MsiPath, string InstallDir, int ParentPid, string ExpectedSha256, string LogPath);

    private static Options? ParseArgs(string[] args)
    {
        string? msi = null, installDir = null, sha = null, log = null;
        int pid = 0;
        for (int i = 0; i < args.Length; i++)
        {
            var a = args[i];
            if (a == "--msi" && i + 1 < args.Length) msi = args[++i];
            else if (a == "--install-dir" && i + 1 < args.Length) installDir = args[++i];
            else if (a == "--parent-pid" && i + 1 < args.Length) int.TryParse(args[++i], out pid);
            else if (a == "--expected-sha256" && i + 1 < args.Length) sha = args[++i];
            else if (a == "--log" && i + 1 < args.Length) log = args[++i];
        }
        if (msi == null || installDir == null || sha == null || log == null || pid == 0) return null;
        return new Options(msi, installDir, pid, sha, log);
    }

    private static void Log(string msg) => _log?.WriteLine($"[{DateTime.Now:HH:mm:ss}] {msg}");

    private static string ComputeSha256(string path)
    {
        using var sha = SHA256.Create();
        using var stream = File.OpenRead(path);
        var hash = sha.ComputeHash(stream);
        var sb = new StringBuilder(hash.Length * 2);
        foreach (var b in hash) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }

    private static void WaitForParentExit(int parentPid)
    {
        try
        {
            var proc = Process.GetProcessById(parentPid);
            if (!proc.WaitForExit(60_000))
            {
                Log("WARN: parent did not exit within 60s; continuing anyway");
            }
        }
        catch (ArgumentException)
        {
            // Process already gone — fine.
        }
    }

    private static void KillOrphanedProcesses()
    {
        foreach (var name in new[] { "jcef_helper", "YallaSipPhone" })
        {
            try
            {
                foreach (var proc in Process.GetProcessesByName(name))
                {
                    try
                    {
                        proc.Kill(true);
                        proc.WaitForExit(5000);
                        Log($"Killed orphaned {name} pid={proc.Id}");
                    }
                    catch (Exception ex)
                    {
                        Log($"WARN: could not kill {name} pid={proc.Id}: {ex.Message}");
                    }
                }
            }
            catch (Exception ex)
            {
                Log($"WARN: process scan for {name} failed: {ex.Message}");
            }
        }
    }

    private static void StripMarkOfTheWeb(string path)
    {
        var ads = path + ":Zone.Identifier";
        try
        {
            if (File.Exists(ads))
            {
                File.Delete(ads);
                Log("Stripped Zone.Identifier");
            }
        }
        catch (Exception ex)
        {
            Log($"WARN: strip MOTW failed: {ex.Message}");
        }
    }

    private static void CopyDirectory(string source, string dest)
    {
        Directory.CreateDirectory(dest);
        foreach (var f in Directory.GetFiles(source))
        {
            try
            {
                File.Copy(f, Path.Combine(dest, Path.GetFileName(f)), overwrite: true);
            }
            catch (Exception ex)
            {
                Log($"WARN: skip {Path.GetFileName(f)}: {ex.Message}");
            }
        }
        foreach (var d in Directory.GetDirectories(source))
        {
            CopyDirectory(d, Path.Combine(dest, Path.GetFileName(d)));
        }
    }

    private static void TryRestore(string backupDir, string installDir)
    {
        try
        {
            if (Directory.Exists(backupDir))
            {
                if (Directory.Exists(installDir))
                    Directory.Delete(installDir, recursive: true);
                CopyDirectory(backupDir, installDir);
                Log("Restored backup into install dir");
            }
        }
        catch (Exception ex)
        {
            Log($"ERROR: restore failed: {ex.Message}");
        }
    }

    private static void TryDeleteDir(string dir)
    {
        try
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
        }
        catch (Exception ex)
        {
            Log($"WARN: delete {dir} failed: {ex.Message}");
        }
    }

    private static void LaunchApp(string installDir)
    {
        try
        {
            var exe = Directory.GetFiles(installDir, "YallaSipPhone.exe", SearchOption.AllDirectories).FirstOrDefault();
            if (exe != null)
            {
                Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true });
                Log($"Launched: {exe}");
            }
            else
            {
                Log("WARN: YallaSipPhone.exe not found after install");
            }
        }
        catch (Exception ex)
        {
            Log($"ERROR: launch failed: {ex.Message}");
        }
    }
}
