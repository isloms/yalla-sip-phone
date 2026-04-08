# YallaSipPhone — Windows MSI Build Guide

## Prerequisites

### 1. Install JDK 21

Download and install JBR (JetBrains Runtime) 21 or any JDK 21+:

```powershell
winget install Microsoft.OpenJDK.21
```

Verify:
```powershell
java -version
# openjdk version "21.x.x"
```

Set `JAVA_HOME` if not set automatically:
```powershell
# Check
echo $env:JAVA_HOME

# Set (PowerShell, adjust path)
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Microsoft\jdk-21", "User")
```

### 2. Install WiX Toolset v3

WiX is required by Gradle to create `.msi` installers.

```powershell
winget install WixToolset.WiX --version 3.14.1
```

Or download manually: https://github.com/wixtoolset/wix3/releases

After install, verify `candle.exe` and `light.exe` are in PATH:
```powershell
candle.exe -?
# Should print WiX Candle version info
```

If not in PATH, add WiX bin directory:
```powershell
# Usually at:
# C:\Program Files (x86)\WiX Toolset v3.14\bin
[System.Environment]::SetEnvironmentVariable("PATH", $env:PATH + ";C:\Program Files (x86)\WiX Toolset v3.14\bin", "User")
```

### 3. Install Git

```powershell
winget install Git.Git
```

### 4. Install Visual Studio 2022 Build Tools

Required for compiling pjsip native library.

```powershell
winget install Microsoft.VisualStudio.2022.BuildTools
```

During install, select:
- **"Desktop development with C++"** workload
- Make sure **"MSVC v143"** and **"Windows 11 SDK"** are checked

---

## Step 1: Build pjsip for Windows (pjsua2.dll)

This is the most critical step. pjsip must be compiled natively on Windows.

### 1.1 Clone pjproject

```powershell
cd C:\dev
git clone https://github.com/pjsip/pjproject.git
cd pjproject
git checkout 2.16  # Match the version used on macOS
```

### 1.2 Configure pjsip

Create `pjlib/include/pj/config_site.h`:
```c
#define PJ_HAS_SSL_SOCK 0
#define PJMEDIA_HAS_VIDEO 0
#define PJMEDIA_AUDIO_DEV_HAS_WMME 1
```

### 1.3 Build with Visual Studio

Open **"x64 Native Tools Command Prompt for VS 2022"** (find in Start menu):

```cmd
cd C:\dev\pjproject

:: Configure for 64-bit
set PJDIR=C:\dev\pjproject
set TARGET_NAME=x86_64-x64-vc17-release

:: Build using MSBuild
cd pjproject-vs14.sln
msbuild pjproject-vs14.sln /p:Configuration=Release /p:Platform=x64

:: Or use CMake (recommended):
mkdir build && cd build
cmake .. -G "Visual Studio 17 2022" -A x64 -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release
```

### 1.4 Build SWIG Java Bindings

```cmd
cd C:\dev\pjproject\pjsip-apps\src\swig\java

:: Edit Makefile or build manually:
:: Make sure JAVA_HOME points to JDK 21
swig -java -package org.pjsip.pjsua2 -outdir java/output -o pjsua2_wrap.cpp pjsua2.i

:: Compile the JNI wrapper
cl /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" /I"..\..\..\..\pjlib\include" ^
   /I"..\..\..\..\pjlib-util\include" /I"..\..\..\..\pjmedia\include" ^
   /I"..\..\..\..\pjsip\include" /I"..\..\..\..\pjnath\include" ^
   pjsua2_wrap.cpp /link /DLL /OUT:pjsua2.dll ^
   ..\..\..\..\lib\*pjsua2*.lib ..\..\..\..\lib\*pjsip*.lib ^
   ..\..\..\..\lib\*pjmedia*.lib ..\..\..\..\lib\*pjlib*.lib ^
   ..\..\..\..\lib\*pjnath*.lib ws2_32.lib ole32.lib winmm.lib
```

### 1.5 Verify Output

You should have:
- `pjsua2.dll` — native JNI library (~3-5 MB)
- `pjsua2.jar` — Java bindings (already exists in our project, reuse it)

Test the DLL:
```powershell
# Should show exports
dumpbin /exports pjsua2.dll | Select-String "Java_org_pjsip"
```

---

## Step 2: Clone and Setup the Project

```powershell
cd C:\dev
git clone https://github.com/RoyalTaxi/yalla-sip-phone.git
cd yalla-sip-phone
```

### 2.1 Copy pjsua2.dll

```powershell
copy C:\dev\pjproject\build\Release\pjsua2.dll libs\pjsua2.dll
```

Verify `libs/` folder has:
```
libs/
├── pjsua2.jar      (already in repo)
├── pjsua2.dll      (just copied)
```

Note: `libpjsua2.jnilib` and `libpjsua2.dylib` are macOS files — they won't interfere on Windows.

---

## Step 3: Build MSI

```powershell
.\gradlew.bat packageMsi --no-daemon
```

Expected output:
```
The distribution is written to
build\compose\binaries\main\msi\YallaSipPhone-1.0.0.msi

BUILD SUCCESSFUL
```

### Troubleshooting Build

**"WiX not found":**
```powershell
# Ensure WiX is in PATH
$env:PATH += ";C:\Program Files (x86)\WiX Toolset v3.14\bin"
.\gradlew.bat packageMsi --no-daemon
```

**"JAVA_HOME not set":**
```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21"
.\gradlew.bat packageMsi --no-daemon
```

**Compile errors about pjsua2:**
The app compiles fine without `pjsua2.dll` — it only needs it at runtime. If `pjsua2.jar` is in `libs/`, the build will succeed.

---

## Step 4: Install and Test

### 4.1 Install MSI

Double-click `YallaSipPhone-1.0.0.msi` or:
```powershell
msiexec /i build\compose\binaries\main\msi\YallaSipPhone-1.0.0.msi
```

### 4.2 Run

Find "YallaSipPhone" in Start Menu and launch.

### 4.3 Verify

1. App opens with Registration screen
2. Enter Oktell credentials:
   - Server: `192.168.0.22`
   - Port: `5060`
   - Username: `102` (or your extension)
   - Password: your password
3. Click Connect
4. Should show "Registered" and switch to Dialer screen
5. Try making/receiving a call

### 4.4 Troubleshooting Runtime

**"Failed to initialize SIP engine":**
- `pjsua2.dll` not found — check it's in the app's `libs/` directory
- Wrong architecture — make sure DLL is 64-bit (x64), matching the JDK

**No audio:**
- Check Windows Sound Settings → make sure microphone is not muted
- Check app has microphone permission in Windows Settings → Privacy → Microphone
- Try: Settings → Sound → Input → make sure a device is selected

**"UnsatisfiedLinkError: pjsua2.dll":**
- Missing Visual C++ Runtime — install:
  ```powershell
  winget install Microsoft.VCRedist.2015+.x64
  ```

**Registration fails:**
- Check firewall — allow UDP port 5060
- Check network — must be on same LAN as Oktell (192.168.0.x subnet)

---

## Quick Reference

| Step | Command | Time |
|------|---------|------|
| Install JDK | `winget install Microsoft.OpenJDK.21` | 2 min |
| Install WiX | `winget install WixToolset.WiX --version 3.14.1` | 1 min |
| Install VS Build Tools | `winget install Microsoft.VisualStudio.2022.BuildTools` | 10 min |
| Clone pjproject | `git clone` + checkout | 2 min |
| Build pjsip | CMake + build | 10-15 min |
| Build SWIG bindings | Compile JNI wrapper | 5 min |
| Clone app | `git clone` | 1 min |
| Copy DLL | `copy pjsua2.dll libs\` | 1 sec |
| Build MSI | `.\gradlew.bat packageMsi` | 1-2 min |
| **Total** | | **~35 min** |

---

## Notes

- pjsua2.jar is cross-platform — same file works on macOS, Windows, Linux
- pjsua2.dll is Windows-only — must be compiled on Windows
- The app auto-detects OS and loads the correct native library (.jnilib / .dll / .so)
- Windows does NOT require special microphone permissions for desktop apps
- Firewall may need UDP 5060 allowed for SIP
