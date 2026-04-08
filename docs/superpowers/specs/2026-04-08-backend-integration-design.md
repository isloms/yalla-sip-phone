# Backend Integration — Design Spec

**Date:** 2026-04-08
**Status:** Approved
**Scope:** Replace MockAuthRepository with real backend API integration (login, me, logout)

---

## Overview

Connect Yalla SIP Phone to the real backend API. Operator enters PIN code, app authenticates via JWT, fetches SIP credentials from `/auth/me`, and registers on the SIP server. Currently using `MockAuthRepository` with hardcoded credentials.

## API Endpoints

Base URL: `http://192.168.0.98:8080/api/v1/` (hardcoded)

### POST /auth/login

Request:
```json
{"pin_code": "778899"}
```

Response (success):
```json
{
    "status": true,
    "code": 200,
    "message": "login successful",
    "result": {
        "token": "eyJhbGci...",
        "token_type": "Bearer ",
        "expire": 4929241537
    },
    "errors": null
}
```

Response (error):
```json
{
    "status": false,
    "code": 401,
    "message": "Error",
    "result": null,
    "errors": "employee not found"
}
```

### GET /auth/me (Bearer token required)

Response:
```json
{
    "status": true,
    "code": 200,
    "message": "success",
    "result": {
        "id": 1,
        "tm_user_id": 1,
        "full_name": "Sadullo Kimsanov",
        "roles": "super_admin",
        "created_at": "2026-04-02 16:24:28",
        "sips": [
            {
                "extension_number": 1003,
                "password": "demo",
                "is_active": true,
                "sip_name": "Andijon server SIp",
                "server_url": "http://test.uz",
                "server_port": 5060,
                "domain": "test.uz",
                "connection_type": "udp"
            }
        ]
    },
    "errors": null
}
```

401 response (invalid/expired token):
```json
{
    "status": false,
    "code": 401,
    "message": "invalid or expired token",
    "result": null,
    "errors": null
}
```

### POST /auth/logout (Bearer token required)

Response:
```json
{
    "status": true,
    "code": 200,
    "message": "logged out successfully",
    "result": null,
    "errors": null
}
```

## API Response Envelope

All endpoints return the same envelope:

```kotlin
@Serializable
data class ApiResponse<T>(
    val status: Boolean,            // true = success, false = error
    val code: Int,                  // HTTP status code
    val message: String? = null,
    val result: T? = null,
    val errors: JsonElement? = null, // Polymorphic: String on 401, Object on 422, null on success
)
```

Key: `status` is `Boolean` (not String). `errors` is `JsonElement?` because the API returns
different types depending on context:
- On 401 auth errors: `"employee not found"` (String)
- On 422 validation errors: `{"pin_code":"required"}` (Object/Map)
- On success: `null`

Helper to extract error message:
```kotlin
fun ApiResponse<*>.errorMessage(): String {
    return when (val e = errors) {
        is JsonPrimitive -> e.contentOrNull ?: message ?: "Unknown error"
        is JsonObject -> e.entries.joinToString { "${it.key}: ${it.value.jsonPrimitive.content}" }
        else -> message ?: "Unknown error"
    }
}
```

---

## Architecture

### New Files

```
data/
├── network/                          HTTP infrastructure (reusable)
│   ├── HttpClientFactory.kt         Ktor CIO client factory
│   ├── ApiResponse.kt               Generic response envelope DTO
│   ├── NetworkError.kt              Sealed error hierarchy
│   └── SafeRequest.kt               safeRequest<T> extension function
│
├── auth/
│   ├── TokenProvider.kt             In-memory JWT storage (interface + impl)
│   ├── AuthApi.kt                   Raw HTTP calls (login, me, logout)
│   ├── AuthRepositoryImpl.kt        Orchestrates login flow
│   ├── AuthEventBus.kt              401 → login redirect event bus
│   ├── LogoutOrchestrator.kt        Full logout sequence coordinator
│   └── dto/
│       ├── LoginRequestDto.kt       Request body DTO
│       ├── LoginResultDto.kt        Login response result DTO
│       └── MeResultDto.kt           Me response result DTO + SIP DTO

di/
│   └── NetworkModule.kt             Koin module for HttpClient, TokenProvider
```

### Modified Files

```
domain/
├── AuthRepository.kt          Add logout(), rename parameter to pinCode
├── AuthResult.kt              Add token field
├── SipCredentials.kt          Add transport field

data/auth/
├── LoginResponse.kt           DELETE (replaced by DTOs)
├── MockAuthRepository.kt      Update to match new AuthRepository interface

di/
├── AuthModule.kt              Bind AuthRepositoryImpl instead of Mock
├── AppModule.kt               Add networkModule to module list

feature/login/
├── LoginComponent.kt          manualConnect: add token="" to AuthResult

feature/main/
├── MainComponent.kt           Pass token to WebView URL as query param

navigation/
├── RootComponent.kt           Add coroutineScope, inject AuthEventBus + LogoutOrchestrator

Main.kt                        Pass AuthEventBus + LogoutOrchestrator to RootComponent
```

---

## Domain Changes

### AuthRepository

```kotlin
interface AuthRepository {
    suspend fun login(pinCode: String): Result<AuthResult>
    suspend fun logout(): Result<Unit>
}
```

### AuthResult

```kotlin
data class AuthResult(
    val token: String,
    val sipCredentials: SipCredentials,
    val dispatcherUrl: String,
    val agent: AgentInfo,
)
```

`dispatcherUrl` — static, hardcoded in `AuthRepositoryImpl`. Not from API.

### SipCredentials

```kotlin
data class SipCredentials(
    val server: String,
    val port: Int = SipConstants.DEFAULT_PORT,
    val username: String,
    val password: String,
    val transport: String = "UDP",
) {
    override fun toString(): String = "SipCredentials(server=$server, port=$port, " +
        "username=$username, password=***, transport=$transport)"
}
```

---

## Data Layer Design

### network/HttpClientFactory.kt

Creates a configured Ktor `HttpClient` with:
- **CIO engine** — pure Kotlin, no Android/OkHttp dependency
- **ContentNegotiation** — kotlinx.serialization JSON (`ignoreUnknownKeys = true`)
- **HttpTimeout** — 15s request, 10s connect
- **Logging** — debug level, via kotlin-logging
- **Auth (Bearer)** — automatic token attachment via `TokenProvider`
  - `loadTokens`: reads from `TokenProvider`
  - **NO `refreshTokens`** — 401 is handled solely by `safeRequest`. This avoids double-handling where both Auth plugin and safeRequest react to 401, causing race conditions with SessionExpired events.
  - `sendWithoutRequest`: skips auth header for `/auth/login`
- **defaultRequest** — base URL + JSON content type
- **expectSuccess = false** — we handle status codes ourselves in `safeRequest`. Ktor will NOT throw on non-2xx responses.

### network/ApiResponse.kt

Generic envelope DTO matching the backend format. See API Response Envelope section above.

### network/NetworkError.kt

```kotlin
sealed class NetworkError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object Unauthorized : NetworkError("Session expired")
    data class ClientError(val code: Int, val serverMessage: String?) : NetworkError(...)
    data class ServerError(val code: Int, val serverMessage: String?) : NetworkError(...)
    data class NoConnection(override val cause: Throwable) : NetworkError(...)
    data class ParseError(override val cause: Throwable) : NetworkError(...)
}
```

### network/SafeRequest.kt

`HttpClient.safeRequest<T>` — `inline` function with `reified T`. MUST stay `inline reified` for Ktor's generic deserialization to work.

Flow:
1. Executes the request inside try/catch
2. **CancellationException** — always rethrown, never caught (breaks structured concurrency otherwise)
3. On HTTP 401: emits `AuthEvent.SessionExpired` on `AuthEventBus`, returns `Result.failure(NetworkError.Unauthorized)`
4. On HTTP 2xx: deserializes `ApiResponse<T>`, checks `status` field:
   - `status == true`: returns `Result.success(result)`
   - `status == false`: returns `Result.failure(NetworkError.ClientError(envelope.code, envelope.errorMessage()))` — **does NOT trigger SessionExpired** even if envelope `code` is 401. Only real HTTP 401 triggers session expiry.
5. On HTTP 4xx (non-401): returns `Result.failure(NetworkError.ClientError(code, message))`
6. On HTTP 5xx: returns `Result.failure(NetworkError.ServerError(code, message))`
7. On HTTP 3xx (unlikely, defensive): returns `Result.failure(NetworkError.ClientError(code, "Unexpected redirect"))`
8. On IOException/timeout: returns `Result.failure(NetworkError.NoConnection(cause))`
9. On SerializationException: returns `Result.failure(NetworkError.ParseError(cause))`

**Critical distinction:** Envelope `code: 401` on HTTP 200 (wrong PIN) → shows error on LoginScreen.
HTTP 401 (expired token) → triggers SessionExpired → redirects to Login. These are different code paths.

### auth/TokenProvider.kt

```kotlin
interface TokenProvider {
    suspend fun getToken(): String?
    suspend fun setToken(token: String)
    suspend fun clearToken()
}

class InMemoryTokenProvider : TokenProvider {
    @Volatile private var token: String? = null
    private val mutex = Mutex()

    override suspend fun getToken(): String? = token
    override suspend fun setToken(token: String) { mutex.withLock { this.token = token } }
    override suspend fun clearToken() { mutex.withLock { token = null } }
}
```

In-memory only. No disk persistence. App restart = re-login.
`@Volatile` for cross-thread visibility on fast-path read. `Mutex` for coroutine-safe writes.

### auth/AuthApi.kt

Thin class, 3 methods:
- `login(pinCode: String): Result<LoginResultDto>` — POST /auth/login
- `me(): Result<MeResultDto>` — GET /auth/me
- `logout(): Result<Unit>` — POST /auth/logout

Each method calls `client.safeRequest<T>`. No business logic.

### auth/AuthRepositoryImpl.kt

Orchestrates the 2-step login flow:

```
login(pinCode) {
    1. authApi.login(pinCode) → LoginResultDto (JWT token)
    2. tokenProvider.setToken(token)
    3. authApi.me() → MeResultDto (user info + sips[])
       - On failure: tokenProvider.clearToken() (rollback)
    4. Find first sip where is_active == true
       - If none found: return failure
    5. Map to AuthResult(token, sipCredentials, dispatcherUrl, agent)
}

logout() {
    // Thin — only API + token. Full cleanup is LogoutOrchestrator's job.
    1. runCatching { authApi.logout() }  // best-effort, don't fail on network error
    2. tokenProvider.clearToken()
}
```

### auth/AuthEventBus.kt

```kotlin
sealed interface AuthEvent {
    data object SessionExpired : AuthEvent
}

class AuthEventBus {
    private val _events = MutableSharedFlow<AuthEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()
    fun emit(event: AuthEvent) { _events.tryEmit(event) }
}
```

`replay = 1` ensures late collectors (e.g., RootComponent starting up) still receive the latest event. `DROP_OLDEST` prevents buffer overflow on rapid 401s.

`safeRequest` emits `SessionExpired` on HTTP 401. `RootComponent` collects → calls `LogoutOrchestrator` → navigates to Login.

### auth/LogoutOrchestrator.kt

Single authority for session teardown. Both voluntary logout (user clicks Logout) and forced logout (401 SessionExpired) go through this.

```kotlin
class LogoutOrchestrator(
    private val authRepository: AuthRepository,
    private val registrationEngine: RegistrationEngine,
    private val connectionManager: ConnectionManager,
    private val tokenProvider: TokenProvider,
) {
    suspend fun logout() {
        connectionManager.stopMonitoring()
        runCatching { registrationEngine.unregister() }
        runCatching { authRepository.logout() }
        tokenProvider.clearToken()
    }
}
```

Order: stop reconnect loop → SIP unregister → API logout → clear token.
Each step is `runCatching` — partial failures don't block cleanup.

### auth/dto/ — DTO classes

Match actual API responses exactly:

```kotlin
@Serializable
data class LoginRequestDto(
    @SerialName("pin_code") val pinCode: String,
)

@Serializable
data class LoginResultDto(
    val token: String,
    @SerialName("token_type") val tokenType: String,
    val expire: Long,
)

@Serializable
data class MeResultDto(
    val id: Int,
    @SerialName("tm_user_id") val tmUserId: Int,
    @SerialName("full_name") val fullName: String,
    val roles: String,
    @SerialName("created_at") val createdAt: String,
    val sips: List<SipConnectionDto>,
)

@Serializable
data class SipConnectionDto(
    @SerialName("extension_number") val extensionNumber: Int,
    val password: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("sip_name") val sipName: String,
    @SerialName("server_url") val serverUrl: String,
    @SerialName("server_port") val serverPort: Int,
    val domain: String,
    @SerialName("connection_type") val connectionType: String,
)
```

### DTO → Domain Mapping

Extension function in `AuthRepositoryImpl` or a separate mapper file:

```kotlin
fun MeResultDto.toAuthResult(token: String, dispatcherUrl: String): AuthResult {
    val sip = sips.first { it.isActive }
    return AuthResult(
        token = token,
        sipCredentials = SipCredentials(
            server = sip.domain,        // "test.uz" — domain field, not server_url
            port = sip.serverPort,
            username = sip.extensionNumber.toString(),
            password = sip.password,
            transport = sip.connectionType.uppercase(),
        ),
        dispatcherUrl = dispatcherUrl,
        agent = AgentInfo(id = id.toString(), name = fullName),
    )
}
```

SIP registration uses `domain` field (not `server_url` which includes `http://` prefix).
Username is `extension_number` (integer → string).

---

## DI Changes

### New: NetworkModule.kt

```kotlin
val networkModule = module {
    single<TokenProvider> { InMemoryTokenProvider() }
    single { AuthEventBus() }
    single {
        createHttpClient(
            baseUrl = "http://192.168.0.98:8080/api/v1/",
            tokenProvider = get(),
            onUnauthorized = { get<AuthEventBus>().emit(AuthEvent.SessionExpired) },
        )
    }
}
```

### Updated: AuthModule.kt

```kotlin
val authModule = module {
    single { AuthApi(client = get()) }
    single<AuthRepository> { AuthRepositoryImpl(authApi = get(), tokenProvider = get()) }
    single { LogoutOrchestrator(get(), get(), get(), get()) }
    // MockAuthRepository stays in test/ for unit tests
}
```

### Updated: AppModule.kt

Add `networkModule` to the list (before `authModule`).

---

## Feature Layer Changes

### LoginComponent

Minimal change:
- `login(password)` → call stays the same (positional arg), AuthRepository handles the rest
- `manualConnect()` — add `token = ""` to `AuthResult(...)` constructor (debug flow, no real token)
- The flow stays the same: auth → register SIP → navigate

### MainComponent / WebviewPanel

WebView URL changes in `MainComponent.kt` (line 41):
```kotlin
// Before:
val dispatcherUrl: String = authResult.dispatcherUrl
// After:
val dispatcherUrl: String = if (authResult.token.isNotEmpty())
    "${authResult.dispatcherUrl}?token=${authResult.token}"
else
    authResult.dispatcherUrl
```

No changes needed in `WebviewPanel` or `MainScreen` — they just pass the URL through.

### RootComponent

Requires several changes (currently has no coroutine scope or event collection):

1. Add `coroutineScope()` from essenty (already in deps)
2. Add `AuthEventBus` and `LogoutOrchestrator` as constructor parameters
3. Add event collection in `init`:

```kotlin
class RootComponent(
    componentContext: ComponentContext,
    private val factory: ComponentFactory,
    private val authEventBus: AuthEventBus,        // NEW
    private val logoutOrchestrator: LogoutOrchestrator, // NEW
) : ComponentContext by componentContext {

    private val scope = coroutineScope()  // NEW — from essenty

    init {
        scope.launch {
            authEventBus.events.collect { event ->
                when (event) {
                    AuthEvent.SessionExpired -> {
                        logoutOrchestrator.logout()
                        currentAuthResult = null
                        navigation.replaceAll(Screen.Login)
                    }
                }
            }
        }
    }
    // ... rest unchanged
}
```

### Main.kt

Update `RootComponent` construction to pass new dependencies:
```kotlin
val authEventBus: AuthEventBus = koin.get()
val logoutOrchestrator: LogoutOrchestrator = koin.get()
RootComponent(context, factory, authEventBus, logoutOrchestrator)
```

---

## Login Screen

No UI changes. Current PIN/password field stays as-is. `ManualConnectionDialog` stays for debugging.

Only semantic change: label could say "PIN code" instead of "Password" (optional, UI rethink task).

---

## Dispatcher URL

Static, hardcoded as a constant. Not from API.

```kotlin
object ApiConfig {
    const val BASE_URL = "http://192.168.0.98:8080/api/v1/"
    const val DISPATCHER_URL = "http://192.168.0.234:5173"  // or current value
}
```

---

## Dependencies (build.gradle.kts)

```kotlin
val ktorVersion = "3.1.2"
implementation("io.ktor:ktor-client-core:$ktorVersion")
implementation("io.ktor:ktor-client-cio:$ktorVersion")
implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
implementation("io.ktor:ktor-client-auth:$ktorVersion")
implementation("io.ktor:ktor-client-logging:$ktorVersion")

testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
```

---

## Session Invalidation via JS Bridge

### Problem

When operator A logs in with PIN X and operator B also logs in with PIN X, the server invalidates A's token. The SIP Phone app does not detect this because it makes no API calls during normal operation. However, the web page (dispatcher panel in WebView) IS making API calls with the token and will receive 401.

### Solution

The frontend app detects 401 and sends a `requestLogout` command through the JS bridge. The native app handles it by performing full logout.

### Bridge Command: `requestLogout`

Frontend calls:
```javascript
YallaSIP.requestLogout()
// Response: { success: true, data: null }
```

### Implementation

`BridgeRouter` handles the new command:
```kotlin
"requestLogout" -> {
    logger.info { "Frontend requested logout (likely token invalidated by another session)" }
    scope.launch {
        logoutOrchestrator.logout()
        authEventBus.emit(AuthEvent.SessionExpired)
    }
    CommandResult.success(null)
}
```

`BridgeRouter` needs `LogoutOrchestrator` and `AuthEventBus` injected (or accessible via MainComponent).

### Flow

```
Operator B logs in with same PIN
  → Server invalidates A's token
  → A's WebView frontend makes API call → 401
  → Frontend calls window.YallaSIP.requestLogout()
  → BridgeRouter receives command
  → LogoutOrchestrator.logout() (SIP unregister, connection stop, token clear)
  → AuthEventBus.emit(SessionExpired)
  → RootComponent navigates to Login screen
```

### Modified Files (additional to existing list)

| File | Change |
|------|--------|
| `data/jcef/BridgeRouter.kt` | Add `requestLogout` command handler |
| `docs/js-bridge-api.md` | Document `requestLogout` command |

---

## Error Handling

| Scenario | What happens |
|----------|-------------|
| Wrong PIN | API returns `{status: false, code: 401, errors: "employee not found"}` → LoginScreen shows error |
| Network down | `IOException` → `NetworkError.NoConnection` → LoginScreen shows "No connection" |
| Token expired (API call) | HTTP 401 from `safeRequest` → `AuthEventBus.SessionExpired` → LogoutOrchestrator → Login |
| Token invalidated (another login) | WebView gets 401 → frontend calls `requestLogout` → LogoutOrchestrator → Login |
| No active SIP in /me | `sips.none { it.isActive }` → `Result.failure` → LoginScreen shows "No SIP connection" |
| API server down | Timeout → `NetworkError.NoConnection` → LoginScreen shows error |
| SIP registration fails | Existing flow handles this via `RegistrationState.Failed` |

---

## Testing Strategy

| Component | Test approach |
|-----------|--------------|
| `AuthApi` | `ktor-client-mock` — simulate all response codes |
| `AuthRepositoryImpl` | Inject mock `AuthApi` + `InMemoryTokenProvider` |
| `safeRequest` | Test each status code path with mock engine |
| `TokenProvider` | `InMemoryTokenProvider` is directly testable |
| `LoginComponent` | Existing test structure + `MockAuthRepository` for UI tests |
| `NetworkError` mapping | Unit tests for each HTTP status → NetworkError subtype |

`MockAuthRepository` moves to test source set. Production DI uses `AuthRepositoryImpl`.

---

## Complete File Change List

### New files (13):

| File | Purpose |
|------|---------|
| `data/network/HttpClientFactory.kt` | Ktor CIO client setup |
| `data/network/ApiResponse.kt` | Generic envelope DTO + errorMessage() helper |
| `data/network/NetworkError.kt` | Sealed error hierarchy |
| `data/network/SafeRequest.kt` | `inline reified` safeRequest extension |
| `data/auth/TokenProvider.kt` | Interface + InMemoryTokenProvider |
| `data/auth/AuthApi.kt` | Raw HTTP calls |
| `data/auth/AuthRepositoryImpl.kt` | Login orchestration |
| `data/auth/AuthEventBus.kt` | Session expiry event bus |
| `data/auth/LogoutOrchestrator.kt` | Full logout sequence coordinator |
| `data/auth/dto/LoginRequestDto.kt` | Request body |
| `data/auth/dto/LoginResultDto.kt` | Login result |
| `data/auth/dto/MeResultDto.kt` | Me result + SipConnectionDto |
| `di/NetworkModule.kt` | Koin: HttpClient, TokenProvider, AuthEventBus |

### Modified files (12):

| File | Change |
|------|--------|
| `domain/AuthResult.kt` | Add `token: String` field |
| `domain/SipCredentials.kt` | Add `transport: String = "UDP"`, update `toString()` |
| `domain/AuthRepository.kt` | Add `logout()`, rename param to `pinCode` |
| `data/auth/MockAuthRepository.kt` | Implement `logout()`, add `token` to AuthResult, construct directly (no LoginResponse) |
| `feature/login/LoginComponent.kt` | `manualConnect`: add `token = ""` to AuthResult |
| `feature/main/MainComponent.kt` | Append `?token=` to dispatcher URL |
| `navigation/RootComponent.kt` | Add scope, AuthEventBus, LogoutOrchestrator |
| `Main.kt` | Pass AuthEventBus + LogoutOrchestrator to RootComponent |
| `di/AuthModule.kt` | Bind AuthRepositoryImpl + LogoutOrchestrator |
| `di/AppModule.kt` | Add networkModule to list |
| `build.gradle.kts` | Add 6 Ktor deps + 1 test dep |

### Deleted files (1):

| File | Reason |
|------|--------|
| `data/auth/LoginResponse.kt` | Replaced by DTOs. Delete AFTER MockAuthRepository is updated. |

### Test files that need updating (3):

| File | Change |
|------|--------|
| `test/.../RootComponentTest.kt` | Add `token` to AuthResult, add `logout()` to anonymous AuthRepository |
| `test/.../LoginComponentTest.kt` | MockAuthRepository now has `logout()` (inherited) |
| `test/.../MockAuthRepositoryTest.kt` | Add test for `logout()` |

### Implementation Notes

1. **`transport` field is data-only for now.** `PjsipAccountManager.register()` does not use it — both UDP and TCP transports are created unconditionally in `PjsipEndpointManager`. Will be used when transport selection is implemented.

2. **`SipCredentials` adding `transport` with default value is non-breaking.** All existing call sites compile without changes.

3. **`LoginResponse.kt` deletion must happen AFTER `MockAuthRepository` is updated** to construct `AuthResult` directly instead of using `LoginResponse.toAuthResult()`.

4. **`safeRequest` MUST stay `inline reified`.** If refactored to non-inline, Ktor's generic deserialization via `typeInfo<T>()` breaks silently at runtime.

---

## Out of Scope

These are known issues found during code review. They should be addressed in separate tasks:

- Multi-SIP connection (separate task)
- UI redesign (separate task)
- PhoneNumberValidator `+` prefix bug
- Simulator JS in production builds
- `AgentStatus.colorHex` Clean Architecture violation
- `CallState` missing failure state
- `SipConstants` splitting
- `BridgeRouter` DI registration
