# Auto-Update Mechanism (Planned)

**Status:** Planned — not yet implemented
**Priority:** Critical for production, but after packaging works

## Flow
```
App start → GET https://update.yalla.uz/version.json → Compare →
  New version? → Background download MSI → Notify operator →
    Operator clicks "Update" → msiexec /quiet → App restart
```

## version.json (static file on server)
```json
{
  "version": "1.2.0",
  "windows": {
    "url": "https://update.yalla.uz/releases/YallaSipPhone-1.2.0.msi",
    "sha256": "abc123...",
    "size": 358400000
  },
  "macos": {
    "url": "https://update.yalla.uz/releases/YallaSipPhone-1.2.0.dmg",
    "sha256": "def456...",
    "size": 345600000
  },
  "releaseNotes": "Bug fixes, performance improvements",
  "mandatory": false
}
```

## Rules
- Check on app start + every 1 hour
- Background download to %TEMP%
- SHA256 verification before install
- NEVER interrupt active call — wait for call to end
- mandatory=true → 5 min countdown, auto install
- Windows: `msiexec /i /quiet /norestart`
- Toolbar badge: "Update available (v1.2.0)"

## Server
Simple static file hosting (S3, nginx, internal server). No backend needed.
