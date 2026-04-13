# bootstrapper/

This directory is where the built `yalla-update-bootstrap.exe` is dropped so Compose Desktop's `nativeDistributions` picks it up and ships it inside the MSI.

Source lives at `bootstrapper/` (repo root). Build with:

```bash
cd ../../../bootstrapper
dotnet publish -c Release -r win-x64 --self-contained=false -p:PublishSingleFile=true
cp bin/Release/net8.0-windows/win-x64/publish/yalla-update-bootstrap.exe \
   ../src/main/resources/bootstrapper/yalla-update-bootstrap.exe
```

Build on Windows (or a Windows CI runner); `net8.0-windows` target does not cross-compile from macOS/Linux without extra workloads.

The committed `.exe` is intentionally gitignored — CI produces it. On dev machines, run the build step above before `./gradlew packageMsi`.
