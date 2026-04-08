# Real Backend Auth Integration (Planned)

**Status:** Blocked — waiting for backend to add SIP config to response
**Priority:** Critical for production
**Depends on:** Backend engineer adding `sip` + `dispatcher_url` to `/auth/login`

## Current State

Backend provides:
- `POST /auth/login` → JWT token + token_type + expire
- `GET /auth/me` → employee id, full_name, roles, tm_user_id
- `POST /auth/logout` → invalidates token

**Missing from backend:**
- SIP server address, port, username, password
- Dispatcher URL (WebView panel URL)

**App workaround:** Manual connection dialog (operator enters SIP details manually)

## What Backend Needs to Add

`POST /auth/login` response should include:
```json
{
  "result": {
    "token": "jwt...",
    "token_type": "Bearer",
    "expire": 4928747668,
    "employee": {
      "id": 1,
      "full_name": "Sadullo Kimsanov",
      "roles": "super_admin"
    },
    "sip": {
      "server": "192.168.0.22",
      "port": 5060,
      "username": "101",
      "password": "1234qwerQQ",
      "transport": "UDP"
    },
    "dispatcher_url": "http://192.168.0.234:5173"
  }
}
```

## Implementation Plan (when backend is ready)

### Dependencies to add
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

### New files to create
```
data/api/
├── ApiClient.kt              — Ktor HttpClient factory (CIO engine)
├── ApiConfig.kt              — Base URL, timeouts
├── TokenStore.kt             — In-memory JWT holder
├── dto/LoginRequest.kt       — { pin_code: String }
├── dto/LoginApiResponse.kt   — Raw API response model
└── AuthApiService.kt         — HTTP call wrapper

data/auth/
├── AuthRepositoryImpl.kt     — Real implementation
└── AuthMapper.kt             — DTO → Domain mapper

domain/
└── AuthError.kt              — Sealed class (InvalidPin, Network, Server, SessionExpired)
```

### Key Decisions
- **Ktor CIO engine** — no external deps, JVM native
- **Token: in-memory only** — no disk persistence, relogin on restart
- **Token expire during call** — call continues, relogin after call ends
- **AuthRepository.login(password)** → rename to `login(pinCode)`
- **AuthRepository** gets `logout()` method
- **MockAuthRepository** → moves to test/, renamed FakeAuthRepository
- **manualConnect stays** — debug/fallback, hide in production UI

### Migration Steps
1. Add Ktor dependencies
2. Create `data/api/` package (ApiClient, TokenStore, DTOs, AuthApiService)
3. Create `AuthRepositoryImpl` + `AuthMapper`
4. Create `domain/AuthError` sealed class
5. Add `networkModule` to DI
6. Update `authModule` to bind real impl
7. Update LoginComponent error handling
8. Move MockAuthRepository to test/ as FakeAuthRepository
9. Add ApiConfig env-based configuration
