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

`yalla-update-bootstrap.exe` (171 KB, PE32+ x86-64 Windows) is committed to the repo so dev machines can run `./gradlew packageMsi` without .NET installed. CI rebuilds it from source on tag. If you change `bootstrapper/Program.cs`, rebuild and re-commit:

```bash
cd bootstrapper
DOTNET_ROOT=/opt/homebrew/opt/dotnet/libexec \
  /opt/homebrew/opt/dotnet/libexec/dotnet publish \
  -c Release -r win-x64 --self-contained=false -p:PublishSingleFile=true
cp bin/Release/net8.0/win-x64/publish/yalla-update-bootstrap.exe \
   ../src/main/resources/bootstrapper/yalla-update-bootstrap.exe
```

The `net8.0` TFM (not `net8.0-windows`) lets macOS/Linux cross-compile to `win-x64`; Program.cs only uses cross-platform `System.*` APIs.
