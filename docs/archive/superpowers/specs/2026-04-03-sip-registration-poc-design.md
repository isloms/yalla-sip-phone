# SIP Registration PoC - Design Spec

## Goal

Prove that a Kotlin Compose Desktop app can connect to Oktell SIP server via UDP and successfully register. This is a feasibility PoC for building a custom operator softphone.

## Success Criteria

- App launches, operator enters SIP credentials, clicks Connect
- App sends SIP REGISTER over UDP to Oktell
- App handles 401 challenge with Digest Authentication
- App shows "Registered" on 200 OK or clear error on failure
- App sends UNREGISTER (Expires:0) on disconnect/close

## Non-Goals (explicitly out of scope)

- Audio/media (RTP) - no calls, no voice
- Re-registration timer - demo is short, Expires is 3600s
- Credential persistence ("Remember Me")
- Dark theme
- System tray
- Desktop menu bar
- Full RFC 3261 compliance

## Test Environment

- Server: `192.168.0.22`
- Port: `5060`
- Transport: `UDP`
- User: `102`
- Password: `1234qwerQQ`
- Platform: Oktell call center PBX

---

## Architecture

```
┌─────────────────────────────────────────────┐
│  UI Layer (Compose Desktop + Material 3)    │
│  ├── screen/MainScreen.kt                   │
│  └── component/                             │
│      ├── SipCredentialsForm.kt              │
│      ├── ConnectionStatusCard.kt            │
│      └── ConnectButton.kt                   │
├─────────────────────────────────────────────┤
│  Domain                                     │
│  ├── SipCredentials.kt                      │
│  └── ConnectionState.kt (sealed class)      │
├─────────────────────────────────────────────┤
│  SIP Layer                                  │
│  ├── SipMessage.kt    (build/parse)         │
│  ├── SipTransport.kt  (UDP socket)          │
│  ├── DigestAuth.kt    (MD5 auth)            │
│  └── SipClient.kt     (REGISTER flow)       │
├─────────────────────────────────────────────┤
│  App.kt + Main.kt (entry, theme, wiring)    │
└─────────────────────────────────────────────┘
```

### Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Compose Desktop | 1.7.3 | UI framework |
| Material 3 | (bundled) | UI components |
| Material Icons Extended | (bundled) | Icons |
| kotlinx-coroutines | 1.10.1 | Async UDP |
| JDK DatagramSocket | (built-in) | UDP transport |
| JDK MessageDigest | (built-in) | MD5 for Digest Auth |

**External dependency count: 1** (coroutines). Everything else is built-in.

### No DI Framework

Manual constructor injection in Main.kt:

```kotlin
val transport = SipTransport()
val auth = DigestAuth()
val client = SipClient(transport, auth)
// Pass client to UI via composition
```

---

## UI Design

### Window

- Size: 420x560dp, centered, resizable
- Min size: 380x480dp
- Title: "Yalla SIP Phone"

### Single Screen, 4 States

No navigation library. One screen with state-driven UI:

```kotlin
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Registering : ConnectionState()
    data class Registered(val server: String, val expiresIn: Int) : ConnectionState()
    data class Failed(val message: String, val isRetryable: Boolean) : ConnectionState()
}
```

### Form Fields

| Field | Component | Validation |
|-------|-----------|------------|
| SIP Server | OutlinedTextField + Icons.Filled.Dns | Non-empty |
| Port | OutlinedTextField (120dp, digits only, max 5 chars) | 1-65535 |
| Username | OutlinedTextField + Icons.Filled.Person | Non-empty |
| Password | OutlinedTextField + visibility toggle | Non-empty |

- Validation: on submit, clear error on edit
- All fields disabled during Registering state (alpha 0.6f)
- Enter key triggers Connect
- Tab order follows declaration order
- Auto-focus first field on launch

### Buttons

| State | Primary | Secondary |
|-------|---------|-----------|
| Idle | "Connect" (FilledButton) | — |
| Registering | "Connecting..." + spinner (disabled) | "Cancel" (OutlinedButton) |
| Registered | — | "Disconnect" (OutlinedButton) |
| Failed | "Retry" (FilledButton) | — |

### Status Card

Appears below form with `AnimatedVisibility(fadeIn + slideInVertically)`:

| State | Color | Icon | Text |
|-------|-------|------|------|
| Registering | secondaryContainer | CircularProgressIndicator | "Registering with server..." |
| Registered | Custom green tonal | CheckCircle | "Registered - 192.168.0.22:5060" |
| Failed | errorContainer | Error | Specific error message |

### Material 3 Theme

Light only. Professional blue color scheme:

```kotlin
lightColorScheme(
    primary = Color(0xFF1A5276),
    primaryContainer = Color(0xFFD4E6F1),
    // ... standard M3 derivations from seed
)
```

Custom success colors for Registered state:
```kotlin
val successContainer = Color(0xFFD4EDDA)
val onSuccessContainer = Color(0xFF155724)
```

### Animations

- Status card: `fadeIn(300ms) + slideInVertically`
- Form disabled: `animateFloatAsState` for alpha
- Button content: `AnimatedContent` for text/spinner transition
- Status card color: `animateColorAsState`

---

## SIP Layer

### SipMessage.kt

Builds SIP REGISTER requests and parses responses.

**Request building:**
```
REGISTER sip:<server> SIP/2.0\r\n
Via: SIP/2.0/UDP <localIp>:<localPort>;branch=z9hG4bK<random>;rport\r\n
Max-Forwards: 70\r\n
From: <sip:<user>@<server>>;tag=<random>\r\n
To: <sip:<user>@<server>>\r\n
Call-ID: <random>@<localIp>\r\n
CSeq: <n> REGISTER\r\n
Contact: <sip:<user>@<localIp>:<localPort>;transport=udp>\r\n
Expires: 3600\r\n
User-Agent: YallaSipPhone/1.0\r\n
Content-Length: 0\r\n
\r\n
```

**Response parsing:**
- Status line: `SIP/2.0 <code> <reason>`
- Headers: key-value pairs split on first `:`
- Extract: status code, WWW-Authenticate params, Contact expires

**Critical rules:**
- All lines end with `\r\n` (not `\n`)
- Message ends with blank line (`\r\n\r\n`)
- Branch must start with `z9hG4bK` magic cookie
- New branch for every new request
- CSeq increments per request within same Call-ID

### SipTransport.kt

UDP socket wrapper with coroutine support.

```kotlin
class SipTransport {
    private var socket: DatagramSocket? = null

    suspend fun open(localPort: Int = 0)     // opens socket
    suspend fun send(message: String, host: String, port: Int)
    suspend fun receive(timeoutMs: Long = 5000): String?
    fun close()                               // closes socket, releases port
}
```

- All operations on `Dispatchers.IO`
- Buffer size: 4096 bytes
- Socket timeout via `soTimeout`
- `close()` called on app exit (prevents port leak)

### DigestAuth.kt

Computes SIP Digest Authentication response (RFC 2617).

```kotlin
object DigestAuth {
    fun computeResponse(
        username: String, realm: String, password: String,
        nonce: String, method: String, uri: String,
        qop: String? = null, nc: String? = null, cnonce: String? = null
    ): String

    fun parseChallenge(wwwAuthenticate: String): DigestChallenge

    fun buildAuthorizationHeader(
        username: String, challenge: DigestChallenge,
        method: String, uri: String, response: String
    ): String
}

data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val algorithm: String = "MD5",
    val qop: String? = null,
    val opaque: String? = null
)
```

- MD5 via `java.security.MessageDigest.getInstance("MD5")`
- New MessageDigest instance per call (cheap, thread-safe)
- Hex output: lowercase

### SipClient.kt

Orchestrates the REGISTER flow with state machine.

**Internal states:**
```
Idle → Registering (send REGISTER, await response)
     → handle 401 (parse challenge, compute auth, send REGISTER+Auth)
     → handle 200 OK → Registered
     → handle 403/timeout → Failed
Registered → Unregistering (send REGISTER Expires:0) → Idle
```

**Retry logic (simplified RFC 3261):**
- 3 attempts per REGISTER
- Delays: 500ms, 1000ms, 2000ms (exponential)
- Total timeout: ~5s per phase
- Same message retransmitted (same branch, same CSeq)

**Cancel support:**
- Coroutine-based: `Job.cancel()` stops the flow
- Socket closed on cancel

**UNREGISTER flow:**
- Send REGISTER with `Expires: 0`
- May need auth (401 challenge again)
- Best-effort: if it fails, server cleans up on Expires timeout

**Public API:**
```kotlin
class SipClient(private val transport: SipTransport, private val auth: DigestAuth) {
    val state: StateFlow<ConnectionState>

    suspend fun register(credentials: SipCredentials)
    suspend fun unregister()
    fun cancel()
}
```

---

## Error Handling

| Error | SIP Response | User Message | Retryable |
|-------|-------------|--------------|-----------|
| Server unreachable | Timeout | "Server unreachable: check address and port" | Yes |
| Wrong credentials | 403 Forbidden | "Authentication failed: check username and password" | Yes (edit creds) |
| Interval too short | 423 | Auto-retry with Min-Expires (transparent) | Automatic |
| Server error | 5xx | "Server error: try again later" | Yes |
| Network error | Exception | "Network error: check connection" | Yes |
| UDP packet loss | Timeout (partial) | Transparent retry, then timeout error | Automatic then Yes |

**Rule:** User sees only actionable errors. Auto-fixable issues (423, UDP retry) are handled silently.

---

## File Structure

```
yalla-sip-phone/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .gitignore
└── src/
    ├── main/kotlin/uz/yalla/sipphone/
    │   ├── Main.kt
    │   ├── App.kt
    │   ├── ui/
    │   │   ├── theme/Theme.kt
    │   │   ├── screen/MainScreen.kt
    │   │   └── component/
    │   │       ├── SipCredentialsForm.kt
    │   │       ├── ConnectionStatusCard.kt
    │   │       └── ConnectButton.kt
    │   ├── domain/
    │   │   ├── SipCredentials.kt
    │   │   └── ConnectionState.kt
    │   └── sip/
    │       ├── SipMessage.kt
    │       ├── SipTransport.kt
    │       ├── DigestAuth.kt
    │       └── SipClient.kt
    └── test/kotlin/uz/yalla/sipphone/
        └── sip/
            ├── DigestAuthTest.kt
            ├── SipMessageTest.kt
            └── SipClientTest.kt (with mock UDP)
```

**Total: 14 source files.** Clean, each with one responsibility.

---

## Lifecycle & Cleanup

```kotlin
Window(onCloseRequest = {
    // 1. Send UNREGISTER (best-effort)
    // 2. Close UDP socket (prevents port leak)
    // 3. Exit
    runBlocking(Dispatchers.IO) {
        sipClient.unregister()
    }
    sipClient.close()
    exitApplication()
})
```

**Critical:** UDP socket MUST close on app exit. If not, port stays bound and next run crashes.

---

## Oktell-Specific Notes

- Use `User-Agent: YallaSipPhone/1.0` header (Oktell may filter unknown UAs)
- Parse realm from 401 WWW-Authenticate, never hardcode
- Use `rport` in Via header for NAT traversal
- Port default: 5060 (standard SIP)
- Oktell may use IP as SIP domain (e.g., `sip:102@192.168.0.22`)
- Expect short timeout behavior: if no traffic, Oktell may drop registration early

---

## Future Expansion Path (not in PoC)

If PoC succeeds, production path:

1. **pjsip + JNI** for full SIP/media stack (calls, audio)
2. **Decompose** for multi-screen navigation
3. **multiplatform-settings** for credential persistence
4. **Dark theme** support
5. **Re-registration** timer
6. **Call UI** (dial pad, incoming call, hold, transfer)
7. **Koin** for DI (when class count grows)
8. **System tray** for background operation
