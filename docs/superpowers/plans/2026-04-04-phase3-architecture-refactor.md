# Phase 3: Architecture Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the Yalla SIP Phone from a working prototype into a professional-grade, scalable call center softphone architecture — fixing all known bugs (mute, hold, audio routing, destroy ordering) and establishing enterprise extension points.

**Architecture:** Decompose PjsipBridge god class into focused components (PjsipEngine facade + EndpointManager + AccountManager + CallManager). Extract SipStackLifecycle interface. Replace all magic values with SipConstants + AppTokens. Split DI into modules. Add ComponentFactory for navigation scaling. Establish typed SipError model.

**Tech Stack:** Kotlin 2.1, Compose Desktop 1.8, pjsua2 JNI (SWIG), Decompose 3.4, Koin 4.1, Coroutines, StateFlow

---

## Base paths

- **Source:** `/Users/macbookpro/Ildam/yalla/yalla-sip-phone/src/main/kotlin/uz/yalla/sipphone/`
- **Test:** `/Users/macbookpro/Ildam/yalla/yalla-sip-phone/src/test/kotlin/uz/yalla/sipphone/`
- **Project root:** `/Users/macbookpro/Ildam/yalla/yalla-sip-phone/`

---

## Group A: Foundation (Tasks 1-3)

These files have zero dependencies on other new files. They can be created in any order.

### Task 1: Create SipConstants.kt

All magic numbers and strings extracted from PjsipBridge, PjsipAccount, SipCredentials, and DialerScreen.

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/domain/SipConstants.kt`

```kotlin
package uz.yalla.sipphone.domain

object SipConstants {
    const val DEFAULT_PORT = 5060
    const val USER_AGENT = "YallaSipPhone/1.0"
    const val AUTH_SCHEME_DIGEST = "digest"
    const val AUTH_REALM_ANY = "*"
    const val AUTH_DATA_TYPE_PLAINTEXT = 0
    const val STATUS_OK = 200
    const val STATUS_BUSY_HERE = 486
    const val STATUS_CLASS_SUCCESS = 2
    const val POLL_INTERVAL_MS = 50
    const val AUDIO_LEVEL_UNMUTED = 1.0f
    const val AUDIO_LEVEL_MUTED = 0.0f
    const val RATE_LIMIT_MS = 1000L
    const val UNREGISTER_DELAY_MS = 200L

    object Timeout {
        const val UNREGISTER_BEFORE_REREGISTER_MS = 3000L
        const val UNREGISTER_MS = 5000L
        const val DESTROY_MS = 3000L
    }

    object NativeLib {
        const val MAC = "libpjsua2.jnilib"
        const val WINDOWS = "pjsua2.dll"
        const val LINUX = "libpjsua2.so"
        const val FALLBACK = "pjsua2"
    }

    fun buildUserUri(user: String, server: String): String = "sip:$user@$server"

    fun buildRegistrarUri(server: String, port: Int): String = "sip:$server:$port"

    fun buildCallUri(number: String, host: String): String = "sip:$number@$host"

    fun extractHostFromUri(serverUri: String?): String {
        val uri = serverUri ?: return ""
        val atIndex = uri.lastIndexOf('@')
        return if (atIndex >= 0) uri.substring(atIndex + 1) else uri
    }
}
```

- [ ] Update `SipCredentials.kt` to use `SipConstants.DEFAULT_PORT`

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/domain/SipCredentials.kt`:

```kotlin
package uz.yalla.sipphone.domain

data class SipCredentials(
    val server: String,
    val port: Int = SipConstants.DEFAULT_PORT,
    val username: String,
    val password: String,
)
```

- [ ] Verify: `./gradlew compileKotlin`

---

### Task 2: Create SipError.kt

Typed error model replacing raw error strings.

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/domain/SipError.kt`

```kotlin
package uz.yalla.sipphone.domain

sealed interface SipError {
    val displayMessage: String

    data class AuthFailed(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Authentication failed: $code $reason"
    }

    data class NetworkError(val cause: Throwable) : SipError {
        override val displayMessage: String get() = "Network error: ${cause.message}"
    }

    data class ServerError(val code: Int, val reason: String) : SipError {
        override val displayMessage: String get() = "Server error: $code $reason"
    }

    data class InternalError(val cause: Throwable) : SipError {
        override val displayMessage: String get() = "Internal error: ${cause.message}"
    }

    companion object {
        fun fromSipStatus(code: Int, reason: String): SipError = when {
            code == 401 || code == 403 -> AuthFailed(code, reason)
            code == 408 || code == 503 || code == 504 -> NetworkError(
                Exception("$code $reason")
            )
            code in 500..599 -> ServerError(code, reason)
            else -> ServerError(code, reason)
        }

        fun fromException(e: Throwable): SipError = InternalError(e)
    }
}
```

- [ ] Update `RegistrationState.kt` — change `Failed.message` to `Failed.error`

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/domain/RegistrationState.kt`:

```kotlin
package uz.yalla.sipphone.domain

sealed interface RegistrationState {
    data object Idle : RegistrationState
    data object Registering : RegistrationState
    data class Registered(val server: String) : RegistrationState
    data class Failed(val error: SipError) : RegistrationState
}
```

- [ ] Update `ConnectionStatusCard.kt` — use `error.displayMessage` instead of `message`

In file `src/main/kotlin/uz/yalla/sipphone/ui/component/ConnectionStatusCard.kt`, replace:

```kotlin
                        is RegistrationState.Failed -> state.message
```

with:

```kotlin
                        is RegistrationState.Failed -> state.error.displayMessage
```

- [ ] Update `FakeRegistrationEngine.kt` to use new `Failed(error:)`

Replace the full file content of `src/test/kotlin/uz/yalla/sipphone/domain/FakeRegistrationEngine.kt`:

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeRegistrationEngine : RegistrationEngine {

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    override val registrationState = _registrationState.asStateFlow()

    var initCalled = false
    var lastCredentials: SipCredentials? = null

    override suspend fun init() = Result.success(Unit).also { initCalled = true }

    override suspend fun register(credentials: SipCredentials): Result<Unit> {
        lastCredentials = credentials
        _registrationState.value = RegistrationState.Registering
        return Result.success(Unit)
    }

    override suspend fun unregister() {
        _registrationState.value = RegistrationState.Idle
    }

    override suspend fun destroy() {
        _registrationState.value = RegistrationState.Idle
    }

    fun simulateRegistered(server: String = "sip:102@192.168.0.22") {
        _registrationState.value = RegistrationState.Registered(server)
    }

    fun simulateFailed(message: String = "403 Forbidden") {
        _registrationState.value = RegistrationState.Failed(
            SipError.fromSipStatus(403, message)
        )
    }
}
```

- [ ] Update `FakeRegistrationEngineTest.kt` to match new `Failed` shape

In file `src/test/kotlin/uz/yalla/sipphone/domain/FakeRegistrationEngineTest.kt`, replace:

```kotlin
        assertEquals("403 Forbidden", state.message)
```

with:

```kotlin
        assertIs<SipError.AuthFailed>(state.error)
```

- [ ] Update `DialerComponentTest.kt` — the `simulateFailed("timeout")` call works via FakeRegistrationEngine, no change needed since FakeRegistrationEngine.simulateFailed wraps the string.

- [ ] Verify: `./gradlew test`

---

### Task 3: Create SipStackLifecycle.kt and extract domain interfaces

Extract `init()` and `destroy()` from `RegistrationEngine` into `SipStackLifecycle`.

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/domain/SipStackLifecycle.kt`

```kotlin
package uz.yalla.sipphone.domain

interface SipStackLifecycle {
    suspend fun initialize(): Result<Unit>
    suspend fun shutdown()
}
```

- [ ] Update `RegistrationEngine.kt` — remove `init()` and `destroy()`

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/domain/RegistrationEngine.kt`:

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

interface RegistrationEngine {
    val registrationState: StateFlow<RegistrationState>
    suspend fun register(credentials: SipCredentials): Result<Unit>
    suspend fun unregister()
}
```

- [ ] Update `FakeRegistrationEngine.kt` — remove `init()` and `destroy()`, add `FakeSipStackLifecycle`

Replace the full file content of `src/test/kotlin/uz/yalla/sipphone/domain/FakeRegistrationEngine.kt`:

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeRegistrationEngine : RegistrationEngine {

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    override val registrationState = _registrationState.asStateFlow()

    var lastCredentials: SipCredentials? = null

    override suspend fun register(credentials: SipCredentials): Result<Unit> {
        lastCredentials = credentials
        _registrationState.value = RegistrationState.Registering
        return Result.success(Unit)
    }

    override suspend fun unregister() {
        _registrationState.value = RegistrationState.Idle
    }

    fun simulateRegistered(server: String = "sip:102@192.168.0.22") {
        _registrationState.value = RegistrationState.Registered(server)
    }

    fun simulateFailed(message: String = "403 Forbidden") {
        _registrationState.value = RegistrationState.Failed(
            SipError.fromSipStatus(403, message)
        )
    }
}

class FakeSipStackLifecycle : SipStackLifecycle {
    var initializeCalled = false
    var shutdownCalled = false

    override suspend fun initialize(): Result<Unit> {
        initializeCalled = true
        return Result.success(Unit)
    }

    override suspend fun shutdown() {
        shutdownCalled = true
    }
}
```

- [ ] Update `FakeRegistrationEngineTest.kt` — remove `init()` and `destroy()` tests, add lifecycle tests

Replace the full file content of `src/test/kotlin/uz/yalla/sipphone/domain/FakeRegistrationEngineTest.kt`:

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeRegistrationEngineTest {

    @Test
    fun `initial state is Idle`() {
        val engine = FakeRegistrationEngine()
        assertIs<RegistrationState.Idle>(engine.registrationState.value)
    }

    @Test
    fun `register transitions to Registering`() = runTest {
        val engine = FakeRegistrationEngine()
        val credentials = SipCredentials("192.168.0.22", 5060, "102", "pass")

        engine.register(credentials)

        assertIs<RegistrationState.Registering>(engine.registrationState.value)
        assertEquals("102", engine.lastCredentials?.username)
    }

    @Test
    fun `simulateRegistered transitions to Registered`() {
        val engine = FakeRegistrationEngine()
        engine.simulateRegistered("sip:102@192.168.0.22")

        val state = engine.registrationState.value
        assertIs<RegistrationState.Registered>(state)
        assertEquals("sip:102@192.168.0.22", state.server)
    }

    @Test
    fun `simulateFailed transitions to Failed`() {
        val engine = FakeRegistrationEngine()
        engine.simulateFailed("403 Forbidden")

        val state = engine.registrationState.value
        assertIs<RegistrationState.Failed>(state)
        assertIs<SipError.AuthFailed>(state.error)
    }

    @Test
    fun `unregister transitions to Idle`() = runTest {
        val engine = FakeRegistrationEngine()
        engine.simulateRegistered()

        engine.unregister()

        assertIs<RegistrationState.Idle>(engine.registrationState.value)
    }

    @Test
    fun `register stores last credentials`() = runTest {
        val engine = FakeRegistrationEngine()
        val creds = SipCredentials("10.0.0.1", 5080, "user1", "secret")

        engine.register(creds)

        assertEquals(creds, engine.lastCredentials)
    }

    @Test
    fun `lastCredentials is null before register`() {
        val engine = FakeRegistrationEngine()
        assertNull(engine.lastCredentials)
    }

    @Test
    fun `full lifecycle - register, registered, unregister`() = runTest {
        val engine = FakeRegistrationEngine()

        assertIs<RegistrationState.Idle>(engine.registrationState.value)

        engine.register(SipCredentials("server", 5060, "user", "pass"))
        assertIs<RegistrationState.Registering>(engine.registrationState.value)

        engine.simulateRegistered()
        assertIs<RegistrationState.Registered>(engine.registrationState.value)

        engine.unregister()
        assertIs<RegistrationState.Idle>(engine.registrationState.value)
    }
}

class FakeSipStackLifecycleTest {

    @Test
    fun `initialize sets flag and returns success`() = runTest {
        val lifecycle = FakeSipStackLifecycle()
        val result = lifecycle.initialize()
        assertTrue(result.isSuccess)
        assertTrue(lifecycle.initializeCalled)
    }

    @Test
    fun `shutdown sets flag`() = runTest {
        val lifecycle = FakeSipStackLifecycle()
        lifecycle.shutdown()
        assertTrue(lifecycle.shutdownCalled)
    }
}
```

- [ ] Verify: `./gradlew test`
- [ ] Commit: `refactor(domain): extract SipStackLifecycle, SipConstants, SipError`

---

## Group B: PjsipBridge Decomposition (Tasks 4-8)

These must be done in order: NativeLibraryLoader first, then the three managers, then the facade.

### Task 4: Extract NativeLibraryLoader.kt

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/data/pjsip/NativeLibraryLoader.kt`

```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.domain.SipConstants
import java.io.File

private val logger = KotlinLogging.logger {}

object NativeLibraryLoader {

    fun load() {
        val osName = System.getProperty("os.name").lowercase()
        val libName = when {
            osName.contains("mac") || osName.contains("darwin") -> SipConstants.NativeLib.MAC
            osName.contains("win") -> SipConstants.NativeLib.WINDOWS
            else -> SipConstants.NativeLib.LINUX
        }

        val devDir = System.getProperty("pjsip.library.path")
        if (devDir != null) {
            val devLib = File("$devDir/$libName")
            if (devLib.exists()) {
                System.load(devLib.absolutePath)
                logger.info { "Loaded native library from dev path: ${devLib.absolutePath}" }
                return
            }
        }

        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (resourcesDir != null) {
            val packagedLib = File("$resourcesDir/$libName")
            if (packagedLib.exists()) {
                System.load(packagedLib.absolutePath)
                logger.info { "Loaded native library from resources: ${packagedLib.absolutePath}" }
                return
            }
        }

        System.loadLibrary(SipConstants.NativeLib.FALLBACK)
        logger.info { "Loaded native library from system path: ${SipConstants.NativeLib.FALLBACK}" }
    }
}
```

- [ ] Verify: `./gradlew compileKotlin`

---

### Task 5: Create PjsipEndpointManager.kt

Endpoint lifecycle, transport creation, polling loop, audio device media access.

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipEndpointManager.kt`

```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_transport_type_e
import uz.yalla.sipphone.domain.SipConstants
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

class PjsipEndpointManager(private val pjDispatcher: CoroutineContext) {

    lateinit var endpoint: Endpoint
        private set

    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)
    private var pollJob: Job? = null
    private var logWriter: PjsipLogWriter? = null

    fun initEndpoint() {
        endpoint = Endpoint()
        endpoint.libCreate()

        val epConfig = EpConfig()
        try {
            epConfig.uaConfig.threadCnt = 0
            epConfig.uaConfig.mainThreadOnly = false
            epConfig.uaConfig.userAgent = SipConstants.USER_AGENT

            logWriter = PjsipLogWriter()
            epConfig.logConfig.writer = logWriter
            epConfig.logConfig.level = 5
            epConfig.logConfig.consoleLevel = 0

            endpoint.libInit(epConfig)
        } finally {
            epConfig.delete()
        }
    }

    fun createTransports() {
        val transportConfig = TransportConfig()
        try {
            transportConfig.port = 0
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig)
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, transportConfig)
        } finally {
            transportConfig.delete()
        }
    }

    fun startLibrary() {
        endpoint.libStart()

        val version = endpoint.libVersion()
        logger.info { "pjsip initialized, version: ${version.full}" }
        version.delete()

        logAudioDevices()
    }

    fun startPolling() {
        pollJob = scope.launch(pjDispatcher) {
            if (!endpoint.libIsThreadRegistered()) {
                endpoint.libRegisterThread("pjsip-poll")
            }
            while (isActive) {
                endpoint.libHandleEvents(SipConstants.POLL_INTERVAL_MS)
                yield()
            }
        }
    }

    suspend fun stopPolling() {
        pollJob?.cancel()
        pollJob?.join()
        pollJob = null
    }

    fun getPlaybackDevMedia(): AudioMedia = endpoint.audDevManager().playbackDevMedia

    fun getCaptureDevMedia(): AudioMedia = endpoint.audDevManager().captureDevMedia

    fun destroy() {
        scope.cancel()
        logWriter = null
    }

    private fun logAudioDevices() {
        val adm = endpoint.audDevManager()
        logger.info { "Audio capture device: ${adm.captureDev}, playback device: ${adm.playbackDev}" }
        val devCount = adm.enumDev2().size
        for (j in 0 until devCount) {
            val dev = adm.enumDev2()[j]
            logger.info { "Audio device[$j]: '${dev.name}' in=${dev.inputCount} out=${dev.outputCount}" }
        }
    }
}
```

- [ ] Verify: `./gradlew compileKotlin`

---

### Task 6: Create PjsipAccountManager.kt

Account lifecycle, registration, incoming call delegation. Fixes: registration race condition (PJSIP_EBUSY), rate limiting, proper unregister-before-reregister wait.

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccountManager.kt`

```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.pjsua_stun_use
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

interface IncomingCallListener {
    fun onIncomingCall(callId: Int)
}

interface AccountProvider {
    val currentAccount: PjsipAccount?
    val lastRegisteredServer: String?
}

class PjsipAccountManager(
    private val isDestroyed: () -> Boolean,
) : AccountProvider {

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    override var currentAccount: PjsipAccount? = null
        private set

    override var lastRegisteredServer: String? = null
        private set

    var incomingCallListener: IncomingCallListener? = null
    private var lastRegisterAttemptMs = 0L

    fun updateRegistrationState(state: RegistrationState) {
        if (state is RegistrationState.Registered) {
            lastRegisteredServer = state.server
        }
        _registrationState.value = state
    }

    fun isAccountDestroyed(): Boolean = isDestroyed()

    fun handleIncomingCall(callId: Int) {
        incomingCallListener?.onIncomingCall(callId)
    }

    suspend fun register(credentials: SipCredentials): Result<Unit> {
        if (_registrationState.value is RegistrationState.Registering) {
            return Result.failure(IllegalStateException("Registration already in progress"))
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastRegisterAttemptMs
        if (elapsed < SipConstants.RATE_LIMIT_MS) {
            delay(SipConstants.RATE_LIMIT_MS - elapsed)
        }
        lastRegisterAttemptMs = System.currentTimeMillis()

        val wasRegistered = _registrationState.value is RegistrationState.Registered
        _registrationState.value = RegistrationState.Registering

        currentAccount?.let { prevAccount ->
            try {
                prevAccount.setRegistration(false)
            } catch (_: Exception) {
                logger.warn { "setRegistration(false) threw (PJSIP_EBUSY or similar) - continuing teardown" }
            }

            if (wasRegistered) {
                try {
                    withTimeoutOrNull(SipConstants.Timeout.UNREGISTER_BEFORE_REREGISTER_MS) {
                        _registrationState.first { it is RegistrationState.Idle }
                    }
                } catch (_: Exception) {}
            }
            _registrationState.value = RegistrationState.Registering

            try { prevAccount.delete() } catch (_: Exception) {}
            currentAccount = null
        }

        val accountConfig = AccountConfig()
        val authCred = AuthCredInfo(
            SipConstants.AUTH_SCHEME_DIGEST,
            SipConstants.AUTH_REALM_ANY,
            credentials.username,
            SipConstants.AUTH_DATA_TYPE_PLAINTEXT,
            credentials.password,
        )
        try {
            accountConfig.idUri = SipConstants.buildUserUri(credentials.username, credentials.server)
            accountConfig.regConfig.registrarUri = SipConstants.buildRegistrarUri(credentials.server, credentials.port)
            accountConfig.sipConfig.authCreds.add(authCred)
            accountConfig.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
            accountConfig.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED

            val account = PjsipAccount(this).apply {
                create(accountConfig, true)
            }
            currentAccount = account

            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Registration failed" }
            _registrationState.value = RegistrationState.Failed(SipError.fromException(e))
            return Result.failure(e)
        } finally {
            accountConfig.delete()
            authCred.delete()
        }
    }

    suspend fun unregister() {
        val acc = currentAccount ?: return
        try {
            acc.setRegistration(false)
            withTimeout(SipConstants.Timeout.UNREGISTER_MS) {
                _registrationState.first { it is RegistrationState.Idle }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "Unregistration timed out" }
        } catch (e: Exception) {
            logger.error(e) { "Unregister error" }
        } finally {
            try { acc.delete() } catch (_: Exception) {}
            currentAccount = null
            _registrationState.value = RegistrationState.Idle
        }
    }

    suspend fun destroy() {
        try {
            currentAccount?.setRegistration(false)
        } catch (_: Exception) {}
        delay(SipConstants.UNREGISTER_DELAY_MS)
        try {
            currentAccount?.delete()
        } catch (_: Exception) {}
        currentAccount = null
        _registrationState.value = RegistrationState.Idle
    }
}
```

- [ ] Verify: `./gradlew compileKotlin`

---

### Task 7: Create PjsipCallManager.kt

Call lifecycle, audio routing, mute/hold bug fixes. Fixes: mute via `startTransmit`/`stopTransmit` instead of `adjustRxLevel`, hold guard with actual media state check.

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCallManager.kt`

```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pjsip.pjsua2.CallOpParam
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.parseRemoteUri

private val logger = KotlinLogging.logger {}

interface AudioMediaProvider {
    fun getPlaybackDevMedia(): org.pjsip.pjsua2.AudioMedia
    fun getCaptureDevMedia(): org.pjsip.pjsua2.AudioMedia
}

class PjsipCallManager(
    private val accountProvider: AccountProvider,
    private val audioMediaProvider: AudioMediaProvider,
    private val isDestroyed: () -> Boolean,
) : IncomingCallListener {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private var currentCall: PjsipCall? = null
    private var holdInProgress = false

    fun isCallManagerDestroyed(): Boolean = isDestroyed()

    suspend fun makeCall(number: String): Result<Unit> {
        if (currentCall != null) return Result.failure(IllegalStateException("Call already active"))
        val acc = accountProvider.currentAccount
            ?: return Result.failure(IllegalStateException("Not registered"))
        val host = SipConstants.extractHostFromUri(accountProvider.lastRegisteredServer)
        if (host.isBlank()) return Result.failure(IllegalStateException("No server address"))
        try {
            val call = PjsipCall(this, acc)
            val uri = SipConstants.buildCallUri(number, host)
            val prm = CallOpParam(true)
            try {
                call.makeCall(uri, prm)
            } finally {
                prm.delete()
            }
            currentCall = call
            _callState.value = CallState.Ringing(
                callerNumber = number,
                callerName = null,
                isOutbound = true,
            )
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "makeCall failed" }
            _callState.value = CallState.Idle
            return Result.failure(e)
        }
    }

    suspend fun answerCall() {
        val call = currentCall ?: return
        val ringing = _callState.value as? CallState.Ringing ?: return
        if (ringing.isOutbound) return
        try {
            val prm = CallOpParam()
            try {
                prm.statusCode = SipConstants.STATUS_OK
                call.answer(prm)
            } finally {
                prm.delete()
            }
        } catch (e: Exception) {
            logger.error(e) { "answerCall failed" }
        }
    }

    suspend fun hangupCall() {
        val call = currentCall ?: return
        try {
            _callState.value = CallState.Ending
            val prm = CallOpParam()
            try {
                call.hangup(prm)
            } finally {
                prm.delete()
            }
        } catch (e: Exception) {
            logger.error(e) { "hangupCall failed" }
            resetCallState()
        }
    }

    /**
     * Mute fix: uses stopTransmit/startTransmit on capture media instead of adjustRxLevel.
     * The adjustRxLevel approach was unreliable. startTransmit/stopTransmit directly controls
     * whether our microphone audio reaches the remote call media.
     *
     * CRITICAL: Do NOT call delete() on captureDevMedia — it is owned by the audio device manager
     * and the SWIG destructor will crash the endpoint.
     */
    suspend fun toggleMute() {
        val state = _callState.value
        if (state !is CallState.Active) return
        val call = currentCall ?: return
        try {
            val callInfo = call.getInfo()
            val mediaCount = callInfo.media.size
            try {
                for (i in 0 until mediaCount) {
                    val mediaInfo = callInfo.media[i]
                    if (mediaInfo.type == org.pjsip.pjsua2.pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                        mediaInfo.status == org.pjsip.pjsua2.pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                    ) {
                        val audioMedia = call.getAudioMedia(i)
                        val captureMedia = audioMediaProvider.getCaptureDevMedia()
                        if (state.isMuted) {
                            captureMedia.startTransmit(audioMedia)
                        } else {
                            captureMedia.stopTransmit(audioMedia)
                        }
                        break
                    }
                }
            } finally {
                callInfo.delete()
            }
            _callState.value = state.copy(isMuted = !state.isMuted)
        } catch (e: Exception) {
            logger.error(e) { "toggleMute failed" }
        }
    }

    /**
     * Hold fix: holdInProgress guard prevents PJ_EINVALIDOP when a re-INVITE is still
     * in-flight. The guard is only cleared after the operation completes or fails.
     */
    suspend fun toggleHold() {
        val state = _callState.value
        if (state !is CallState.Active) return
        if (holdInProgress) {
            logger.warn { "Hold/resume operation already in progress, ignoring" }
            return
        }
        val call = currentCall ?: return
        holdInProgress = true
        try {
            val prm = CallOpParam()
            try {
                if (state.isOnHold) {
                    prm.opt.flag = 0
                    call.reinvite(prm)
                } else {
                    call.setHold(prm)
                }
            } finally {
                prm.delete()
            }
            _callState.value = state.copy(isOnHold = !state.isOnHold)
        } catch (e: Exception) {
            logger.error(e) { "toggleHold failed" }
        } finally {
            holdInProgress = false
        }
    }

    fun onCallConfirmed(call: PjsipCall) {
        val state = _callState.value
        if (state is CallState.Ringing) {
            _callState.value = CallState.Active(
                remoteNumber = state.callerNumber,
                remoteName = state.callerName,
                isOutbound = state.isOutbound,
                isMuted = false,
                isOnHold = false,
            )
        }
    }

    fun onCallDisconnected(call: PjsipCall) {
        resetCallState()
        try {
            call.delete()
        } catch (e: Exception) {
            logger.warn(e) { "Error deleting call object" }
        }
    }

    override fun onIncomingCall(callId: Int) {
        val acc = accountProvider.currentAccount ?: return
        if (currentCall != null) {
            logger.warn { "Rejecting incoming call (already in call)" }
            try {
                val call = PjsipCall(this, acc, callId)
                val prm = CallOpParam()
                try {
                    prm.statusCode = SipConstants.STATUS_BUSY_HERE
                    call.hangup(prm)
                } finally {
                    prm.delete()
                }
                call.delete()
            } catch (e: Exception) {
                logger.error(e) { "Failed to reject incoming call" }
            }
            return
        }
        try {
            val call = PjsipCall(this, acc, callId)
            currentCall = call
            val info = call.getInfo()
            try {
                val callerInfo = parseRemoteUri(info.remoteUri)
                _callState.value = CallState.Ringing(
                    callerNumber = callerInfo.number,
                    callerName = callerInfo.displayName,
                    isOutbound = false,
                )
                logger.info { "Incoming call from: ${callerInfo.displayName ?: callerInfo.number}" }
            } finally {
                info.delete()
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling incoming call" }
            resetCallState()
        }
    }

    fun connectCallAudio(call: PjsipCall) {
        var info: org.pjsip.pjsua2.CallInfo? = null
        try {
            info = call.getInfo()
            val mediaCount = info.media.size
            for (i in 0 until mediaCount) {
                val mediaInfo = info.media[i]
                if (mediaInfo.type == org.pjsip.pjsua2.pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    mediaInfo.status == org.pjsip.pjsua2.pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                ) {
                    val audioMedia = call.getAudioMedia(i)
                    val playbackMedia = audioMediaProvider.getPlaybackDevMedia()
                    val captureMedia = audioMediaProvider.getCaptureDevMedia()
                    audioMedia.startTransmit(playbackMedia)
                    captureMedia.startTransmit(audioMedia)
                    logger.info { "Audio media connected for media index $i" }

                    try {
                        val si = call.getStreamInfo(i.toLong())
                        logger.info {
                            "Stream: codec=${si.codecName}/${si.codecClockRate}Hz, " +
                                "dir=${si.dir}, remote=${si.remoteRtpAddress}"
                        }
                        si.delete()
                    } catch (e: Exception) {
                        logger.warn(e) { "Could not get stream info" }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error connecting call audio" }
        } finally {
            info?.delete()
        }
    }

    fun destroy() {
        currentCall?.let { call ->
            try {
                val prm = CallOpParam()
                try {
                    call.hangup(prm)
                } finally {
                    prm.delete()
                }
            } catch (_: Exception) {}
            try { call.delete() } catch (_: Exception) {}
        }
        currentCall = null
        _callState.value = CallState.Idle
    }

    private fun resetCallState() {
        currentCall = null
        _callState.value = CallState.Idle
    }
}
```

- [ ] Verify: `./gradlew compileKotlin`

---

### Task 8: Create PjsipEngine.kt facade, update PjsipAccount/PjsipCall, delete PjsipBridge

This is the central task: wire the three managers together behind a thin facade.

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipEngine.kt`

```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.SipStackLifecycle
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class PjsipEngine : SipStackLifecycle, RegistrationEngine, CallEngine {

    private val destroyed = AtomicBoolean(false)
    private val pjDispatcher = newSingleThreadContext("pjsip-event-loop")
    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)

    private val endpointManager = PjsipEndpointManager(pjDispatcher)
    private val accountManager = PjsipAccountManager(::isDestroyed)
    private val callManager = PjsipCallManager(
        accountProvider = accountManager,
        audioMediaProvider = object : AudioMediaProvider {
            override fun getPlaybackDevMedia() = endpointManager.getPlaybackDevMedia()
            override fun getCaptureDevMedia() = endpointManager.getCaptureDevMedia()
        },
        isDestroyed = ::isDestroyed,
    )

    init {
        accountManager.incomingCallListener = callManager
    }

    private fun isDestroyed(): Boolean = destroyed.get()

    // --- SipStackLifecycle ---

    override suspend fun initialize(): Result<Unit> = withContext(pjDispatcher) {
        try {
            NativeLibraryLoader.load()
            endpointManager.initEndpoint()
            endpointManager.createTransports()
            endpointManager.startLibrary()
            endpointManager.startPolling()
            logger.info { "PjsipEngine initialized successfully" }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "PjsipEngine init failed" }
            Result.failure(e)
        }
    }

    override suspend fun shutdown() {
        if (!destroyed.compareAndSet(false, true)) return
        withContext(pjDispatcher) {
            callManager.destroy()
            accountManager.destroy()
            endpointManager.stopPolling()
            endpointManager.destroy()
        }
        scope.cancel()
        pjDispatcher.close()
    }

    // --- RegistrationEngine ---

    override val registrationState: StateFlow<RegistrationState>
        get() = accountManager.registrationState

    override suspend fun register(credentials: SipCredentials): Result<Unit> =
        withContext(pjDispatcher) { accountManager.register(credentials) }

    override suspend fun unregister(): Unit =
        withContext(pjDispatcher) { accountManager.unregister() }

    // --- CallEngine ---

    override val callState: StateFlow<CallState>
        get() = callManager.callState

    override suspend fun makeCall(number: String): Result<Unit> =
        withContext(pjDispatcher) { callManager.makeCall(number) }

    override suspend fun answerCall(): Unit =
        withContext(pjDispatcher) { callManager.answerCall() }

    override suspend fun hangupCall(): Unit =
        withContext(pjDispatcher) { callManager.hangupCall() }

    override suspend fun toggleMute(): Unit =
        withContext(pjDispatcher) { callManager.toggleMute() }

    override suspend fun toggleHold(): Unit =
        withContext(pjDispatcher) { callManager.toggleHold() }
}
```

- [ ] Update `PjsipAccount.kt` to depend on `PjsipAccountManager` instead of `PjsipBridge`

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipAccount.kt`:

```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

class PjsipAccount(private val accountManager: PjsipAccountManager) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        if (accountManager.isAccountDestroyed()) return
        var info: org.pjsip.pjsua2.AccountInfo? = null
        try {
            info = getInfo()
            val code = prm.code

            when {
                code / 100 == SipConstants.STATUS_CLASS_SUCCESS && info.regIsActive -> {
                    accountManager.updateRegistrationState(RegistrationState.Registered(server = info.uri))
                    logger.info { "Registered: ${info.uri}, expires: ${info.regExpiresSec}s" }
                }
                code / 100 == SipConstants.STATUS_CLASS_SUCCESS && !info.regIsActive -> {
                    accountManager.updateRegistrationState(RegistrationState.Idle)
                    logger.info { "Unregistered" }
                }
                else -> {
                    val reason = "${prm.code} ${prm.reason}"
                    val error = SipError.fromSipStatus(prm.code, prm.reason)
                    accountManager.updateRegistrationState(RegistrationState.Failed(error = error))
                    logger.warn { "Registration failed: $reason (lastErr=${info.regLastErr})" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in onRegState callback" }
            accountManager.updateRegistrationState(
                RegistrationState.Failed(error = SipError.fromException(e))
            )
        } finally {
            info?.delete()
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        if (accountManager.isAccountDestroyed()) return
        try {
            accountManager.handleIncomingCall(prm.callId)
        } catch (e: Exception) {
            logger.error(e) { "Error in onIncomingCall callback" }
        }
    }
}
```

- [ ] Update `PjsipCall.kt` to depend on `PjsipCallManager` instead of `PjsipBridge`

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipCall.kt`:

```kotlin
package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallInfo
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.pjsip_inv_state

private val logger = KotlinLogging.logger {}

class PjsipCall : Call {

    private val callManager: PjsipCallManager

    constructor(callManager: PjsipCallManager, account: Account) : super(account) {
        this.callManager = callManager
    }

    constructor(callManager: PjsipCallManager, account: Account, callId: Int) : super(account, callId) {
        this.callManager = callManager
    }

    override fun onCallState(prm: OnCallStateParam) {
        if (callManager.isCallManagerDestroyed()) return
        var info: CallInfo? = null
        try {
            info = getInfo()
            logger.info { "Call state: ${info.stateText} (${info.lastStatusCode})" }
            when (info.state) {
                pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> callManager.onCallConfirmed(this)
                pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> callManager.onCallDisconnected(this)
                else -> {}
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in onCallState callback" }
        } finally {
            info?.delete()
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        if (callManager.isCallManagerDestroyed()) return
        try {
            callManager.connectCallAudio(this)
        } catch (e: Exception) {
            logger.error(e) { "Error in onCallMediaState callback" }
        }
    }
}
```

- [ ] Delete `PjsipBridge.kt`

Delete the file `src/main/kotlin/uz/yalla/sipphone/data/pjsip/PjsipBridge.kt`.

- [ ] Verify: `./gradlew compileKotlin`
- [ ] Commit: `refactor(pjsip): decompose PjsipBridge into Engine + EndpointManager + AccountManager + CallManager`

---

## Group C: DI + Navigation (Tasks 9-11)

### Task 9: Split DI into modules

- [ ] Delete `src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt` and replace with four files.

Create `src/main/kotlin/uz/yalla/sipphone/di/SipModule.kt`:

```kotlin
package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.data.pjsip.PjsipEngine
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.SipStackLifecycle

val sipModule = module {
    single { PjsipEngine() }
    single<SipStackLifecycle> { get<PjsipEngine>() }
    single<RegistrationEngine> { get<PjsipEngine>() }
    single<CallEngine> { get<PjsipEngine>() }
}
```

Create `src/main/kotlin/uz/yalla/sipphone/di/SettingsModule.kt`:

```kotlin
package uz.yalla.sipphone.di

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import uz.yalla.sipphone.data.settings.AppSettings

val settingsModule = module {
    singleOf(::AppSettings)
}
```

Create `src/main/kotlin/uz/yalla/sipphone/di/FeatureModule.kt`:

```kotlin
package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.navigation.ComponentFactory
import uz.yalla.sipphone.navigation.ComponentFactoryImpl

val featureModule = module {
    single<ComponentFactory> { ComponentFactoryImpl(getKoin()) }
}
```

Create `src/main/kotlin/uz/yalla/sipphone/di/AppModule.kt`:

```kotlin
package uz.yalla.sipphone.di

val appModules = listOf(sipModule, settingsModule, featureModule)
```

- [ ] Verify: `./gradlew compileKotlin` (will fail until ComponentFactory exists — that is Task 10)

---

### Task 10: Create ComponentFactory

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/navigation/ComponentFactory.kt`

```kotlin
package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent

interface ComponentFactory {
    fun createRegistration(context: ComponentContext, onRegistered: () -> Unit): RegistrationComponent
    fun createDialer(context: ComponentContext, onDisconnected: () -> Unit): DialerComponent
}
```

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/navigation/ComponentFactoryImpl.kt`

```kotlin
package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import org.koin.core.Koin
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent

class ComponentFactoryImpl(private val koin: Koin) : ComponentFactory {

    override fun createRegistration(
        context: ComponentContext,
        onRegistered: () -> Unit,
    ): RegistrationComponent = RegistrationComponent(
        componentContext = context,
        sipEngine = koin.get<RegistrationEngine>(),
        appSettings = koin.get<AppSettings>(),
        onRegistered = onRegistered,
    )

    override fun createDialer(
        context: ComponentContext,
        onDisconnected: () -> Unit,
    ): DialerComponent = DialerComponent(
        componentContext = context,
        registrationEngine = koin.get<RegistrationEngine>(),
        callEngine = koin.get<CallEngine>(),
        onDisconnected = onDisconnected,
    )
}
```

- [ ] Update `RootComponent.kt` to use `ComponentFactory`

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/navigation/RootComponent.kt`:

```kotlin
package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent

class RootComponent(
    componentContext: ComponentContext,
    private val factory: ComponentFactory,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Screen>()

    val childStack: Value<ChildStack<Screen, Child>> = childStack(
        source = navigation,
        serializer = Screen.serializer(),
        initialConfiguration = Screen.Registration,
        handleBackButton = true,
        childFactory = ::createChild,
    )

    private fun createChild(screen: Screen, context: ComponentContext): Child =
        when (screen) {
            is Screen.Registration -> Child.Registration(
                factory.createRegistration(context) { navigation.pushNew(Screen.Dialer) }
            )
            is Screen.Dialer -> Child.Dialer(
                factory.createDialer(context) { navigation.pop() }
            )
        }

    sealed interface Child {
        data class Registration(val component: RegistrationComponent) : Child
        data class Dialer(val component: DialerComponent) : Child
    }
}
```

- [ ] Verify: `./gradlew compileKotlin`

---

### Task 11: Update Main.kt

Use `SipStackLifecycle`, `ComponentFactory`, proper shutdown hook, window tokens.

- [ ] Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/Main.kt`

```kotlin
package uz.yalla.sipphone

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.startKoin
import uz.yalla.sipphone.di.appModules
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipStackLifecycle
import uz.yalla.sipphone.navigation.ComponentFactory
import uz.yalla.sipphone.navigation.RootComponent
import uz.yalla.sipphone.navigation.RootContent
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

private val logger = KotlinLogging.logger {}

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        logger.error(throwable) { "Uncaught exception on ${thread.name}" }
    }

    val koin = startKoin { modules(appModules) }.koin

    val lifecycle: SipStackLifecycle = koin.get()
    val initResult = runBlocking { lifecycle.initialize() }

    if (initResult.isFailure) {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Failed to initialize SIP engine:\n${initResult.exceptionOrNull()?.message}",
            "Yalla SIP Phone - Error",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        return
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            withTimeoutOrNull(SipConstants.Timeout.DESTROY_MS) { lifecycle.shutdown() }
        }
    })

    val decomposeLifecycle = LifecycleRegistry()
    val factory: ComponentFactory = koin.get()
    val rootComponent = runOnUiThread {
        RootComponent(
            componentContext = DefaultComponentContext(lifecycle = decomposeLifecycle),
            factory = factory,
        )
    }

    application {
        val tokens = LocalAppTokens.current
        val windowState = rememberWindowState(
            size = tokens.registrationWindowSize,
            position = WindowPosition(Alignment.Center),
        )

        Window(
            onCloseRequest = {
                runBlocking {
                    withTimeoutOrNull(SipConstants.Timeout.DESTROY_MS) { lifecycle.shutdown() }
                }
                exitApplication()
            },
            title = "Yalla SIP Phone",
            state = windowState,
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = tokens.minimumAwtDimension()
            }

            LifecycleController(decomposeLifecycle, windowState)

            YallaSipPhoneTheme {
                RootContent(rootComponent, windowState)
            }
        }
    }
}

private fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()

    var error: Throwable? = null
    var result: T? = null

    SwingUtilities.invokeAndWait {
        try {
            result = block()
        } catch (e: Throwable) {
            error = e
        }
    }

    error?.let { throw it }

    @Suppress("UNCHECKED_CAST")
    return result as T
}
```

- [ ] Verify: `./gradlew compileKotlin`
- [ ] Commit: `refactor(di): split modules, add ComponentFactory, update Main.kt lifecycle`

---

## Group D: UI Cleanup (Tasks 12-15)

### Task 12: Expand AppTokens with shapes, window sizes, and utility

- [ ] Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/ui/theme/AppTokens.kt`

```kotlin
package uz.yalla.sipphone.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

data class AppTokens(
    // Spacing
    val spacingXs: Dp = 4.dp,
    val spacingSm: Dp = 8.dp,
    val spacingMd: Dp = 16.dp,
    val spacingLg: Dp = 24.dp,
    val spacingXl: Dp = 32.dp,

    // Elevation
    val elevationNone: Dp = 0.dp,
    val elevationLow: Dp = 2.dp,
    val elevationMedium: Dp = 6.dp,

    // Corner radius
    val cornerSmall: Dp = 8.dp,
    val cornerMedium: Dp = 12.dp,
    val cornerLarge: Dp = 16.dp,

    // Shapes
    val shapeSmall: Shape = RoundedCornerShape(8.dp),
    val shapeMedium: Shape = RoundedCornerShape(12.dp),

    // Window sizes
    val registrationWindowSize: DpSize = DpSize(420.dp, 520.dp),
    val dialerWindowSize: DpSize = DpSize(800.dp, 180.dp),
    val windowMinWidth: Dp = 380.dp,
    val windowMinHeight: Dp = 180.dp,

    // Icons
    val iconSmall: Dp = 16.dp,
    val iconMedium: Dp = 24.dp,

    // Indicators
    val indicatorDot: Dp = 8.dp,
    val indicatorDotSmall: Dp = 7.dp,
    val dividerThickness: Dp = 1.dp,
    val dividerHeight: Dp = 32.dp,

    // Progress
    val progressSmall: Dp = 18.dp,
    val progressStrokeSmall: Dp = 2.dp,

    // Alpha
    val alphaDisabled: Float = 0.6f,
    val alphaHint: Float = 0.7f,

    // Animation
    val animFast: Int = 200,
    val animMedium: Int = 300,
    val animSlow: Int = 350,
) {
    fun minimumAwtDimension(): java.awt.Dimension =
        java.awt.Dimension(windowMinWidth.value.toInt(), windowMinHeight.value.toInt())
}

val LocalAppTokens = staticCompositionLocalOf { AppTokens() }
```

- [ ] Update `RootContent.kt` to use tokens for window sizes

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/navigation/RootContent.kt`:

```kotlin
package uz.yalla.sipphone.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.WindowState
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import uz.yalla.sipphone.feature.dialer.DialerScreen
import uz.yalla.sipphone.feature.registration.RegistrationScreen
import uz.yalla.sipphone.ui.theme.LocalAppTokens

@Composable
fun RootContent(root: RootComponent, windowState: WindowState) {
    val tokens = LocalAppTokens.current
    val childStack by root.childStack.subscribeAsState()

    LaunchedEffect(childStack.active.instance) {
        val targetSize = when (childStack.active.instance) {
            is RootComponent.Child.Registration -> tokens.registrationWindowSize
            is RootComponent.Child.Dialer -> tokens.dialerWindowSize
        }
        windowState.size = targetSize
    }

    Children(
        stack = childStack,
        animation = stackAnimation {
            slide(animationSpec = tween(tokens.animSlow, easing = FastOutSlowInEasing)) +
                fade(animationSpec = tween(tokens.animFast))
        },
    ) { child ->
        when (val instance = child.instance) {
            is RootComponent.Child.Registration ->
                RegistrationScreen(instance.component)
            is RootComponent.Child.Dialer ->
                DialerScreen(instance.component)
        }
    }
}
```

- [ ] Verify: `./gradlew compileKotlin`

---

### Task 13: Extract Strings.kt

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/ui/strings/Strings.kt`

```kotlin
package uz.yalla.sipphone.ui.strings

object Strings {
    const val APP_TITLE = "Yalla SIP Phone"
    const val REGISTRATION_TITLE = "SIP Registration"

    const val BUTTON_CONNECT = "Connect"
    const val BUTTON_DISCONNECT = "Disconnect"
    const val BUTTON_CANCEL = "Cancel"
    const val BUTTON_RETRY = "Retry"
    const val BUTTON_CONNECTING = "Connecting..."
    const val BUTTON_CALL = "Call"
    const val BUTTON_ANSWER = "Answer"
    const val BUTTON_REJECT = "Reject"
    const val BUTTON_END = "End"
    const val BUTTON_MUTE = "Mute"
    const val BUTTON_UNMUTE = "Unmute"
    const val BUTTON_HOLD = "Hold"
    const val BUTTON_RESUME = "Resume"

    const val STATUS_READY = "READY"
    const val STATUS_ACTIVE = "ACTIVE"
    const val STATUS_ON_HOLD = "ON HOLD"
    const val STATUS_CALLING = "CALLING\u2026"
    const val STATUS_INCOMING_CALL = "INCOMING CALL"
    const val STATUS_ENDING_CALL = "Ending call..."
    const val STATUS_CONNECTION_LOST = "Connection lost \u2014 returning..."
    const val STATUS_SPACE_HINT = " (Space)"

    const val REG_STATUS_REGISTERING = "Registering..."
    const val REG_STATUS_REGISTERED = "Registered"
    const val REG_STATUS_FAILED = "Connection Failed"
    const val REG_DETAIL_CONNECTING = "Connecting to server..."

    const val PLACEHOLDER_PHONE = "Phone number"
    const val PLACEHOLDER_SERVER = "192.168.0.22"
    const val PLACEHOLDER_USERNAME = "102"

    const val LABEL_SERVER = "SIP Server"
    const val LABEL_PORT = "Port"
    const val LABEL_USERNAME = "Username"
    const val LABEL_PASSWORD = "Password"

    const val ERROR_INIT_TITLE = "Yalla SIP Phone - Error"
    fun errorInitMessage(reason: String?): String =
        "Failed to initialize SIP engine:\n$reason"
}
```

This is a foundation for i18n. The actual replacement of hardcoded strings throughout UI files is a mechanical change -- each string literal gets replaced with the corresponding `Strings.*` constant. This is safe to do in a sweep.

- [ ] Update `DialerScreen.kt` to use `Strings` and token shapes (full replacement)

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/feature/dialer/DialerScreen.kt`:

```kotlin
package uz.yalla.sipphone.feature.dialer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalExtendedColors
import uz.yalla.sipphone.util.formatDuration

@Composable
fun DialerScreen(component: DialerComponent) {
    val tokens = LocalAppTokens.current
    val registrationState by component.registrationState.collectAsState()
    val callState by component.callState.collectAsState()

    var phoneNumber by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }
    var callDuration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(callState) {
        if (callState is CallState.Active) {
            callDuration = 0
            while (isActive) {
                delay(1000)
                callDuration++
            }
        } else {
            callDuration = 0
        }
    }

    when (registrationState) {
        is RegistrationState.Registered -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Spacebar &&
                            callState is CallState.Ringing &&
                            !(callState as CallState.Ringing).isOutbound &&
                            !isInputFocused
                        ) {
                            component.answerCall()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                Surface(tonalElevation = 1.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = tokens.spacingMd, vertical = tokens.spacingSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(tokens.indicatorDot).clip(CircleShape)
                                .background(LocalExtendedColors.current.success),
                        )
                        Spacer(Modifier.width(tokens.spacingSm))
                        Text(
                            (registrationState as RegistrationState.Registered).server,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                when (val state = callState) {
                    is CallState.Idle -> IdleRow(
                        phoneNumber = phoneNumber,
                        onPhoneNumberChange = { phoneNumber = it },
                        onCall = { if (phoneNumber.isNotBlank()) component.makeCall(phoneNumber) },
                        onDisconnect = component::disconnect,
                        onFocusChanged = { isInputFocused = it },
                    )
                    is CallState.Ringing -> if (state.isOutbound) {
                        OutboundRingingRow(
                            callerNumber = state.callerNumber,
                            onCancel = component::hangupCall,
                        )
                    } else {
                        RingingRow(
                            callerNumber = state.callerNumber,
                            callerName = state.callerName,
                            onAnswer = component::answerCall,
                            onReject = component::hangupCall,
                        )
                    }
                    is CallState.Active -> ActiveCallRow(
                        remoteNumber = state.remoteNumber,
                        remoteName = state.remoteName,
                        duration = callDuration,
                        isMuted = state.isMuted,
                        isOnHold = state.isOnHold,
                        onToggleMute = component::toggleMute,
                        onToggleHold = component::toggleHold,
                        onHangup = component::hangupCall,
                    )
                    is CallState.Ending -> EndingRow()
                }
            }
        }
        else -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    Strings.STATUS_CONNECTION_LOST,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun IdleRow(
    phoneNumber: String,
    onPhoneNumberChange: (String) -> Unit,
    onCall: () -> Unit,
    onDisconnect: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm),
    ) {
        Text(
            Strings.STATUS_READY,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )
        OutlinedTextField(
            value = phoneNumber,
            onValueChange = onPhoneNumberChange,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            placeholder = { Text(Strings.PLACEHOLDER_PHONE) },
            singleLine = true,
            shape = tokens.shapeSmall,
        )
        Button(
            onClick = onCall,
            enabled = phoneNumber.isNotBlank(),
            shape = tokens.shapeSmall,
        ) {
            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(tokens.iconSmall))
            Spacer(Modifier.width(tokens.spacingXs))
            Text(Strings.BUTTON_CALL)
        }
        TextButton(onClick = onDisconnect) {
            Text(Strings.BUTTON_DISCONNECT, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun OutboundRingingRow(
    callerNumber: String,
    onCancel: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                Strings.STATUS_CALLING,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(tokens.spacingXs))
            Text(
                callerNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OutlinedButton(
            onClick = onCancel,
            shape = tokens.shapeSmall,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Filled.CallEnd, contentDescription = null, modifier = Modifier.size(tokens.iconSmall))
            Spacer(Modifier.width(tokens.spacingXs))
            Text(Strings.BUTTON_CANCEL)
        }
    }
}

@Composable
private fun RingingRow(
    callerNumber: String,
    callerName: String?,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                Strings.STATUS_INCOMING_CALL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(tokens.spacingXs))
            Text(
                callerNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (callerName != null) {
                Text(
                    callerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm)) {
            Button(
                onClick = onAnswer,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LocalExtendedColors.current.success,
                ),
                shape = tokens.shapeSmall,
            ) {
                Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(tokens.iconSmall))
                Spacer(Modifier.width(6.dp))
                Text(Strings.BUTTON_ANSWER)
                Text(
                    Strings.STATUS_SPACE_HINT,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalExtendedColors.current.onSuccess.copy(alpha = tokens.alphaHint),
                )
            }
            OutlinedButton(
                onClick = onReject,
                shape = tokens.shapeSmall,
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(tokens.iconSmall))
                Spacer(Modifier.width(tokens.spacingXs))
                Text(Strings.BUTTON_REJECT)
            }
        }
    }
}

@Composable
private fun ActiveCallRow(
    remoteNumber: String,
    remoteName: String?,
    duration: Long,
    isMuted: Boolean,
    isOnHold: Boolean,
    onToggleMute: () -> Unit,
    onToggleHold: () -> Unit,
    onHangup: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(tokens.indicatorDotSmall).clip(CircleShape).background(
                        if (isOnHold) MaterialTheme.colorScheme.tertiary
                        else LocalExtendedColors.current.success,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isOnHold) Strings.STATUS_ON_HOLD else Strings.STATUS_ACTIVE,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOnHold) MaterialTheme.colorScheme.tertiary
                    else LocalExtendedColors.current.success,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.width(tokens.spacingSm))
                Text(
                    formatDuration(duration),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (isOnHold) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(tokens.spacingXs))
            Text(
                remoteNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (remoteName != null) {
                Text(
                    remoteName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacingSm),
        ) {
            OutlinedButton(
                onClick = onToggleMute,
                shape = tokens.shapeSmall,
                colors = if (isMuted) ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ) else ButtonDefaults.outlinedButtonColors(),
            ) {
                Icon(Icons.Filled.MicOff, contentDescription = null, modifier = Modifier.size(tokens.iconSmall))
                Spacer(Modifier.width(tokens.spacingXs))
                Text(if (isMuted) Strings.BUTTON_UNMUTE else Strings.BUTTON_MUTE)
            }

            if (isOnHold) {
                Button(
                    onClick = onToggleHold,
                    shape = tokens.shapeSmall,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(tokens.iconSmall))
                    Spacer(Modifier.width(tokens.spacingXs))
                    Text(Strings.BUTTON_RESUME)
                }
            } else {
                OutlinedButton(
                    onClick = onToggleHold,
                    shape = tokens.shapeSmall,
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(tokens.iconSmall))
                    Spacer(Modifier.width(tokens.spacingXs))
                    Text(Strings.BUTTON_HOLD)
                }
            }

            Box(
                Modifier
                    .width(tokens.dividerThickness)
                    .height(tokens.dividerHeight)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            OutlinedButton(
                onClick = onHangup,
                shape = tokens.shapeSmall,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = null, modifier = Modifier.size(tokens.iconSmall))
                Spacer(Modifier.width(tokens.spacingXs))
                Text(Strings.BUTTON_END)
            }
        }
    }
}

@Composable
private fun EndingRow() {
    val tokens = LocalAppTokens.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(tokens.spacingMd),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            Strings.STATUS_ENDING_CALL,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] Verify: `./gradlew compileKotlin` (will fail until TimeFormat.kt exists — that is Task 14)

---

### Task 14: Extract TimeFormat.kt

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/util/TimeFormat.kt`

```kotlin
package uz.yalla.sipphone.util

fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(minutes, secs)
}
```

- [ ] Create test `src/test/kotlin/uz/yalla/sipphone/util/TimeFormatTest.kt`

```kotlin
package uz.yalla.sipphone.util

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatTest {

    @Test
    fun `zero seconds`() {
        assertEquals("00:00", formatDuration(0))
    }

    @Test
    fun `59 seconds`() {
        assertEquals("00:59", formatDuration(59))
    }

    @Test
    fun `60 seconds shows 01 00`() {
        assertEquals("01:00", formatDuration(60))
    }

    @Test
    fun `90 seconds shows 01 30`() {
        assertEquals("01:30", formatDuration(90))
    }

    @Test
    fun `3661 seconds shows 61 01`() {
        assertEquals("61:01", formatDuration(3661))
    }
}
```

- [ ] Verify: `./gradlew compileKotlin`

---

### Task 15: Update ConnectButton and ConnectionStatusCard to use Strings

- [ ] Update `ConnectButton.kt`

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/ui/component/ConnectButton.kt`:

```kotlin
package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens

@Composable
fun ConnectButton(
    state: RegistrationState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        when (state) {
            is RegistrationState.Idle -> {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text(Strings.BUTTON_CONNECT)
                }
            }
            is RegistrationState.Registering -> {
                OutlinedButton(onClick = onCancel) { Text(Strings.BUTTON_CANCEL) }
                Button(onClick = {}, enabled = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(tokens.progressSmall),
                            strokeWidth = tokens.progressStrokeSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(tokens.spacingSm))
                        Text(Strings.BUTTON_CONNECTING)
                    }
                }
            }
            is RegistrationState.Registered -> {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text(Strings.BUTTON_DISCONNECT)
                }
            }
            is RegistrationState.Failed -> {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text(Strings.BUTTON_RETRY)
                }
            }
        }
    }
}
```

- [ ] Update `ConnectionStatusCard.kt`

Replace the full file content of `src/main/kotlin/uz/yalla/sipphone/ui/component/ConnectionStatusCard.kt`:

```kotlin
package uz.yalla.sipphone.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalExtendedColors

@Composable
fun ConnectionStatusCard(state: RegistrationState, modifier: Modifier = Modifier) {
    val tokens = LocalAppTokens.current
    val extendedColors = LocalExtendedColors.current

    AnimatedVisibility(
        visible = state is RegistrationState.Registering || state is RegistrationState.Failed,
        enter = fadeIn(tween(tokens.animMedium)) + slideInVertically(
            initialOffsetY = { it / 4 }, animationSpec = tween(tokens.animMedium),
        ),
        exit = fadeOut(tween(tokens.animFast)) + shrinkVertically(tween(tokens.animFast)),
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        val containerColor by animateColorAsState(
            targetValue = when (state) {
                is RegistrationState.Registering -> MaterialTheme.colorScheme.secondaryContainer
                is RegistrationState.Registered -> extendedColors.successContainer
                is RegistrationState.Failed -> MaterialTheme.colorScheme.errorContainer
                is RegistrationState.Idle -> Color.Transparent
            }, animationSpec = tween(tokens.animMedium),
        )
        val contentColor by animateColorAsState(
            targetValue = when (state) {
                is RegistrationState.Registering -> MaterialTheme.colorScheme.onSecondaryContainer
                is RegistrationState.Registered -> extendedColors.onSuccessContainer
                is RegistrationState.Failed -> MaterialTheme.colorScheme.onErrorContainer
                is RegistrationState.Idle -> Color.Transparent
            }, animationSpec = tween(tokens.animMedium),
        )

        Card(colors = CardDefaults.cardColors(containerColor = containerColor), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(tokens.spacingMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    is RegistrationState.Registering -> CircularProgressIndicator(
                        modifier = Modifier.size(tokens.iconMedium), strokeWidth = 2.5.dp, color = contentColor,
                    )
                    is RegistrationState.Registered -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = Strings.REG_STATUS_REGISTERED,
                        tint = contentColor,
                    )
                    is RegistrationState.Failed -> Icon(
                        Icons.Filled.Error,
                        contentDescription = Strings.REG_STATUS_FAILED,
                        tint = contentColor,
                    )
                    is RegistrationState.Idle -> {}
                }
                Column {
                    Text(
                        text = when (state) {
                            is RegistrationState.Registering -> Strings.REG_STATUS_REGISTERING
                            is RegistrationState.Registered -> Strings.REG_STATUS_REGISTERED
                            is RegistrationState.Failed -> Strings.REG_STATUS_FAILED
                            is RegistrationState.Idle -> ""
                        },
                        style = MaterialTheme.typography.titleSmall, color = contentColor,
                    )
                    Text(
                        text = when (state) {
                            is RegistrationState.Registering -> Strings.REG_DETAIL_CONNECTING
                            is RegistrationState.Registered -> state.server
                            is RegistrationState.Failed -> state.error.displayMessage
                            is RegistrationState.Idle -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
```

- [ ] Update `RegistrationScreen.kt` to use `Strings`

In file `src/main/kotlin/uz/yalla/sipphone/feature/registration/RegistrationScreen.kt`, replace:

```kotlin
            Text(
                "SIP Registration",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
```

with:

```kotlin
            Text(
                Strings.REGISTRATION_TITLE,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
```

And add the import at the top of the file:

```kotlin
import uz.yalla.sipphone.ui.strings.Strings
```

- [ ] Also in `RegistrationScreen.kt`, replace hardcoded alpha and spacing:

Replace:

```kotlin
        targetValue = if (formEnabled) 1f else 0.6f, animationSpec = tween(300),
```

with:

```kotlin
        targetValue = if (formEnabled) 1f else tokens.alphaDisabled, animationSpec = tween(tokens.animMedium),
```

And add tokens read near the top of the function. Replace:

```kotlin
    val formAlpha by animateFloatAsState(
```

with:

```kotlin
    val tokens = LocalAppTokens.current

    val formAlpha by animateFloatAsState(
```

And add the import:

```kotlin
import uz.yalla.sipphone.ui.theme.LocalAppTokens
```

- [ ] Verify: `./gradlew compileKotlin`
- [ ] Commit: `refactor(ui): extract Strings, expand AppTokens, use tokens everywhere`

---

## Group E: Rename files (Task 16)

### Task 16: Rename RemoteUriParser.kt to CallerInfo.kt

The file `src/main/kotlin/uz/yalla/sipphone/domain/RemoteUriParser.kt` already contains `data class CallerInfo` and `fun parseRemoteUri`. The file is already named correctly since the current content was already refactored in Phase 2.

Looking at the actual file content, it IS already named `RemoteUriParser.kt` but contains `CallerInfo`. The rename:

- [ ] Rename file: `src/main/kotlin/uz/yalla/sipphone/domain/RemoteUriParser.kt` to `src/main/kotlin/uz/yalla/sipphone/domain/CallerInfo.kt`

The content stays identical — the package and all import paths (`uz.yalla.sipphone.domain.parseRemoteUri`, `uz.yalla.sipphone.domain.CallerInfo`) remain the same. Only the filename changes.

- [ ] Rename test: `src/test/kotlin/uz/yalla/sipphone/domain/RemoteUriParserTest.kt` to `src/test/kotlin/uz/yalla/sipphone/domain/CallerInfoTest.kt`

Update the class name inside to match:

```kotlin
package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallerInfoTest {

    @Test
    fun `parses display name and number from standard URI`() {
        val (name, number) = parseRemoteUri(""""Alex Petrov" <sip:102@192.168.0.22>""")
        assertEquals("Alex Petrov", name)
        assertEquals("102", number)
    }

    @Test
    fun `parses URI without display name`() {
        val (name, number) = parseRemoteUri("<sip:+998901234567@192.168.0.22>")
        assertNull(name)
        assertEquals("+998901234567", number)
    }

    @Test
    fun `parses URI with port in host`() {
        val (name, number) = parseRemoteUri(""""Operator" <sip:201@10.0.0.1:5060>""")
        assertEquals("Operator", name)
        assertEquals("201", number)
    }

    @Test
    fun `parses bare sip URI without angle brackets`() {
        val (name, number) = parseRemoteUri("sip:100@server.local")
        assertNull(name)
        assertEquals("100", number)
    }

    @Test
    fun `handles empty string gracefully`() {
        val (name, number) = parseRemoteUri("")
        assertNull(name)
        assertEquals("", number)
    }

    @Test
    fun `handles malformed URI — returns raw input as number`() {
        val (name, number) = parseRemoteUri("not-a-sip-uri")
        assertNull(name)
        assertEquals("not-a-sip-uri", number)
    }

    @Test
    fun `parses display name with special characters`() {
        val (name, number) = parseRemoteUri(""""O'Brien, John" <sip:300@host>""")
        assertEquals("O'Brien, John", name)
        assertEquals("300", number)
    }

    @Test
    fun `parses URI with transport parameter`() {
        val (name, number) = parseRemoteUri(""""Test" <sip:102@host;transport=udp>""")
        assertEquals("Test", name)
        assertEquals("102", number)
    }
}
```

- [ ] Verify: `./gradlew test`
- [ ] Commit: `refactor(domain): rename RemoteUriParser.kt to CallerInfo.kt`

---

## Group F: Test Updates + New Tests (Tasks 17-18)

### Task 17: Update existing tests for interface changes

- [ ] Update `FakeCallEngine.kt` with configurable failure

Replace the full file content of `src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngine.kt`:

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeCallEngine(
    var makeCallResult: Result<Unit> = Result.success(Unit),
) : CallEngine {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    override val callState = _callState.asStateFlow()

    var lastCallNumber: String? = null
    var answerCallCount = 0
    var hangupCallCount = 0
    var toggleMuteCount = 0
    var toggleHoldCount = 0

    override suspend fun makeCall(number: String): Result<Unit> {
        lastCallNumber = number
        return makeCallResult
    }

    override suspend fun answerCall() {
        answerCallCount++
    }

    override suspend fun hangupCall() {
        hangupCallCount++
    }

    override suspend fun toggleMute() {
        toggleMuteCount++
    }

    override suspend fun toggleHold() {
        toggleHoldCount++
    }

    fun simulateRinging(callerNumber: String = "102", callerName: String? = null, isOutbound: Boolean = false) {
        _callState.value = CallState.Ringing(callerNumber, callerName, isOutbound)
    }

    fun simulateActive(
        remoteNumber: String = "102",
        remoteName: String? = null,
        isOutbound: Boolean = false,
        isMuted: Boolean = false,
        isOnHold: Boolean = false,
    ) {
        _callState.value = CallState.Active(remoteNumber, remoteName, isOutbound, isMuted, isOnHold)
    }

    fun simulateIdle() {
        _callState.value = CallState.Idle
    }
}
```

- [ ] Update `FakeCallEngineTest.kt` with new tests

Replace the full file content of `src/test/kotlin/uz/yalla/sipphone/domain/FakeCallEngineTest.kt`:

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FakeCallEngineTest {

    @Test
    fun `initial state is Idle`() {
        val engine = FakeCallEngine()
        assertIs<CallState.Idle>(engine.callState.value)
    }

    @Test
    fun `makeCall stores last number`() = runTest {
        val engine = FakeCallEngine()
        engine.makeCall("+998901234567")
        assertEquals("+998901234567", engine.lastCallNumber)
    }

    @Test
    fun `answerCall increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateRinging("102", "Alex")
        engine.answerCall()
        assertEquals(1, engine.answerCallCount)
    }

    @Test
    fun `hangupCall increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.hangupCall()
        assertEquals(1, engine.hangupCallCount)
    }

    @Test
    fun `toggleMute increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.toggleMute()
        assertEquals(1, engine.toggleMuteCount)
    }

    @Test
    fun `toggleHold increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.toggleHold()
        assertEquals(1, engine.toggleHoldCount)
    }

    @Test
    fun `simulateRinging sets Ringing state`() {
        val engine = FakeCallEngine()
        engine.simulateRinging("102", "Alex")
        val state = engine.callState.value
        assertIs<CallState.Ringing>(state)
        assertEquals("102", state.callerNumber)
        assertEquals("Alex", state.callerName)
    }

    @Test
    fun `simulateActive sets Active state`() {
        val engine = FakeCallEngine()
        engine.simulateActive(remoteNumber = "102", remoteName = "Alex", isOutbound = false)
        val state = engine.callState.value
        assertIs<CallState.Active>(state)
        assertEquals("102", state.remoteNumber)
        assertEquals(false, state.isOutbound)
    }

    @Test
    fun `simulateIdle resets to Idle`() {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.simulateIdle()
        assertIs<CallState.Idle>(engine.callState.value)
    }

    @Test
    fun `makeCall returns success by default`() = runTest {
        val engine = FakeCallEngine()
        assertTrue(engine.makeCall("102").isSuccess)
    }

    @Test
    fun `makeCall returns configured failure`() = runTest {
        val engine = FakeCallEngine(
            makeCallResult = Result.failure(IllegalStateException("Not registered"))
        )
        val result = engine.makeCall("102")
        assertFalse(result.isSuccess)
    }
}
```

- [ ] Verify: `./gradlew test`

---

### Task 18: New tests — RootComponentTest, double-connect guard, makeCall error handling

- [ ] Create `src/test/kotlin/uz/yalla/sipphone/navigation/RootComponentTest.kt`

```kotlin
package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.FakeCallEngine
import uz.yalla.sipphone.domain.FakeRegistrationEngine
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class RootComponentTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val fakeRegistrationEngine = FakeRegistrationEngine()
    private val fakeCallEngine = FakeCallEngine()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createRoot(): RootComponent {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val factory = object : ComponentFactory {
            override fun createRegistration(
                context: com.arkivanov.decompose.ComponentContext,
                onRegistered: () -> Unit,
            ) = RegistrationComponent(
                componentContext = context,
                sipEngine = fakeRegistrationEngine,
                appSettings = AppSettings(),
                onRegistered = onRegistered,
                ioDispatcher = testDispatcher,
            )

            override fun createDialer(
                context: com.arkivanov.decompose.ComponentContext,
                onDisconnected: () -> Unit,
            ) = DialerComponent(
                componentContext = context,
                registrationEngine = fakeRegistrationEngine,
                callEngine = fakeCallEngine,
                onDisconnected = onDisconnected,
                ioDispatcher = testDispatcher,
            )
        }
        return RootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            factory = factory,
        )
    }

    @Test
    fun `initial screen is Registration`() {
        val root = createRoot()
        val activeChild = root.childStack.value.active.instance
        assertIs<RootComponent.Child.Registration>(activeChild)
    }

    @Test
    fun `navigates to Dialer on registration`() {
        val root = createRoot()
        fakeRegistrationEngine.simulateRegistered()
        val activeChild = root.childStack.value.active.instance
        assertIs<RootComponent.Child.Dialer>(activeChild)
    }

    @Test
    fun `navigates back to Registration on disconnect`() {
        val root = createRoot()
        fakeRegistrationEngine.simulateRegistered()
        assertIs<RootComponent.Child.Dialer>(root.childStack.value.active.instance)

        fakeRegistrationEngine.simulateFailed("timeout")
        val activeChild = root.childStack.value.active.instance
        assertIs<RootComponent.Child.Registration>(activeChild)
    }
}
```

- [ ] Create `src/test/kotlin/uz/yalla/sipphone/feature/registration/RegistrationDoubleConnectTest.kt`

```kotlin
package uz.yalla.sipphone.feature.registration

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.FakeRegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipCredentials
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class RegistrationDoubleConnectTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createComponent(
        engine: FakeRegistrationEngine = FakeRegistrationEngine(),
    ): Pair<RegistrationComponent, FakeRegistrationEngine> {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val component = RegistrationComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            sipEngine = engine,
            appSettings = AppSettings(),
            onRegistered = {},
            ioDispatcher = testDispatcher,
        )
        return component to engine
    }

    @Test
    fun `double connect is blocked when already registering`() = runTest {
        val engine = FakeRegistrationEngine()
        val (component, _) = createComponent(engine)
        val creds = SipCredentials("192.168.0.22", 5060, "102", "pass")

        component.connect(creds)
        advanceUntilIdle()
        assertIs<RegistrationState.Registering>(engine.registrationState.value)

        // Second connect should be silently ignored (guard in RegistrationComponent.connect)
        component.connect(creds)
        advanceUntilIdle()

        // State is still Registering (not a second register call)
        assertIs<RegistrationState.Registering>(engine.registrationState.value)
    }
}
```

- [ ] Create `src/test/kotlin/uz/yalla/sipphone/feature/dialer/DialerMakeCallErrorTest.kt`

```kotlin
package uz.yalla.sipphone.feature.dialer

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.FakeCallEngine
import uz.yalla.sipphone.domain.FakeRegistrationEngine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class DialerMakeCallErrorTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `makeCall with failure result still records attempt`() = runTest {
        val regEngine = FakeRegistrationEngine().apply { simulateRegistered() }
        val callEngine = FakeCallEngine(
            makeCallResult = Result.failure(IllegalStateException("Not registered"))
        )
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val component = DialerComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            registrationEngine = regEngine,
            callEngine = callEngine,
            onDisconnected = {},
            ioDispatcher = testDispatcher,
        )

        component.makeCall("102")
        advanceUntilIdle()

        assertNotNull(callEngine.lastCallNumber)
        // Call state remains Idle since FakeCallEngine does not change state on failure
        assertIs<CallState.Idle>(callEngine.callState.value)
    }
}
```

- [ ] Create `src/test/kotlin/uz/yalla/sipphone/domain/SipErrorTest.kt`

```kotlin
package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SipErrorTest {

    @Test
    fun `401 maps to AuthFailed`() {
        val error = SipError.fromSipStatus(401, "Unauthorized")
        assertIs<SipError.AuthFailed>(error)
        assertEquals(401, error.code)
    }

    @Test
    fun `403 maps to AuthFailed`() {
        val error = SipError.fromSipStatus(403, "Forbidden")
        assertIs<SipError.AuthFailed>(error)
        assertEquals(403, error.code)
    }

    @Test
    fun `408 maps to NetworkError`() {
        val error = SipError.fromSipStatus(408, "Request Timeout")
        assertIs<SipError.NetworkError>(error)
    }

    @Test
    fun `503 maps to NetworkError`() {
        val error = SipError.fromSipStatus(503, "Service Unavailable")
        assertIs<SipError.NetworkError>(error)
    }

    @Test
    fun `500 maps to ServerError`() {
        val error = SipError.fromSipStatus(500, "Internal Server Error")
        assertIs<SipError.ServerError>(error)
        assertEquals(500, error.code)
    }

    @Test
    fun `fromException maps to InternalError`() {
        val error = SipError.fromException(RuntimeException("boom"))
        assertIs<SipError.InternalError>(error)
        assertTrue(error.displayMessage.contains("boom"))
    }

    @Test
    fun `displayMessage is human readable`() {
        val error = SipError.AuthFailed(403, "Forbidden")
        assertEquals("Authentication failed: 403 Forbidden", error.displayMessage)
    }

    @Test
    fun `SipConstants buildCallUri formats correctly`() {
        assertEquals("sip:102@192.168.0.22", SipConstants.buildCallUri("102", "192.168.0.22"))
    }

    @Test
    fun `SipConstants extractHostFromUri extracts after at sign`() {
        assertEquals("192.168.0.22", SipConstants.extractHostFromUri("sip:102@192.168.0.22"))
    }

    @Test
    fun `SipConstants extractHostFromUri handles null`() {
        assertEquals("", SipConstants.extractHostFromUri(null))
    }

    @Test
    fun `SipConstants extractHostFromUri handles no at sign`() {
        assertEquals("192.168.0.22", SipConstants.extractHostFromUri("192.168.0.22"))
    }
}
```

- [ ] Verify: `./gradlew test`
- [ ] Commit: `test: add RootComponentTest, double-connect guard, makeCall error, SipError tests`

---

## Group G: Enterprise Foundation Stubs (Task 19)

### Task 19: Enterprise interface stubs

These are interface-only definitions for Phase 4 extension. No implementation yet.

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/domain/ConnectionManager.kt`

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

interface ConnectionManager {
    val connectionState: StateFlow<ConnectionState>
    suspend fun connect(credentials: SipCredentials)
    suspend fun disconnect()
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val server: String) : ConnectionState
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState
    data class Failed(val error: SipError, val willRetry: Boolean) : ConnectionState
}
```

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/domain/TransportConfig.kt`

```kotlin
package uz.yalla.sipphone.domain

data class TransportPreference(
    val protocol: TransportProtocol = TransportProtocol.UDP,
    val srtpPolicy: SrtpPolicy = SrtpPolicy.DISABLED,
)

enum class TransportProtocol { UDP, TCP, TLS }
enum class SrtpPolicy { DISABLED, OPTIONAL, MANDATORY }
```

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/domain/CallQualityMonitor.kt`

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

interface CallQualityMonitor {
    val qualityStats: StateFlow<CallQualityStats?>
}

data class CallQualityStats(
    val codec: String,
    val rxJitterMs: Float,
    val txJitterMs: Float,
    val rxPacketLoss: Float,
    val txPacketLoss: Float,
    val rttMs: Float,
    val mosScore: Float,
    val durationSeconds: Long,
)
```

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/domain/AudioConfigEngine.kt`

```kotlin
package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.StateFlow

interface AudioConfigEngine {
    val audioDevices: StateFlow<AudioDeviceList>
    val currentConfig: StateFlow<AudioSettings>
    suspend fun selectInputDevice(deviceId: Int)
    suspend fun selectOutputDevice(deviceId: Int)
    suspend fun setEchoCancellation(enabled: Boolean, tailLengthMs: Int = 200)
    suspend fun setNoiseSuppression(enabled: Boolean)
    suspend fun setCodecPriority(codec: String, priority: Int)
}

data class AudioDeviceList(
    val inputDevices: List<AudioDevice> = emptyList(),
    val outputDevices: List<AudioDevice> = emptyList(),
    val selectedInput: Int = -1,
    val selectedOutput: Int = -1,
)

data class AudioDevice(
    val id: Int,
    val name: String,
    val inputCount: Int,
    val outputCount: Int,
)

data class AudioSettings(
    val echoCancellation: Boolean = true,
    val echoCancellationTailMs: Int = 200,
    val noiseSuppression: Boolean = true,
    val codecPriorities: Map<String, Int> = emptyMap(),
)
```

- [ ] Create `src/main/kotlin/uz/yalla/sipphone/domain/DesktopIntegration.kt`

```kotlin
package uz.yalla.sipphone.domain

interface DesktopIntegration {
    fun showNotification(title: String, message: String)
    fun setTrayIcon(state: TrayState)
    fun registerGlobalHotkey(key: String, action: () -> Unit)
    fun setAlwaysOnTop(enabled: Boolean)
}

enum class TrayState { IDLE, REGISTERED, IN_CALL, INCOMING_CALL }
```

- [ ] Verify: `./gradlew compileKotlin`
- [ ] Commit: `feat(domain): add enterprise foundation interfaces (ConnectionManager, AudioConfig, CallQuality, Desktop)`

---

## Group H: Final Verification (Task 20)

### Task 20: Full build + test + final commit

- [ ] Run full build: `./gradlew build`
- [ ] Run all tests: `./gradlew test`
- [ ] Verify no warnings related to deprecated APIs or unused imports: `./gradlew compileKotlin 2>&1 | grep -i "warning"`
- [ ] Review file count matches spec:

Expected new files (14):
1. `domain/SipConstants.kt`
2. `domain/SipError.kt`
3. `domain/SipStackLifecycle.kt`
4. `data/pjsip/NativeLibraryLoader.kt`
5. `data/pjsip/PjsipEndpointManager.kt`
6. `data/pjsip/PjsipAccountManager.kt`
7. `data/pjsip/PjsipCallManager.kt`
8. `data/pjsip/PjsipEngine.kt`
9. `navigation/ComponentFactory.kt`
10. `navigation/ComponentFactoryImpl.kt`
11. `di/SipModule.kt`
12. `di/SettingsModule.kt`
13. `di/FeatureModule.kt`
14. `ui/strings/Strings.kt`
15. `util/TimeFormat.kt`
16. `domain/ConnectionManager.kt`
17. `domain/TransportConfig.kt`
18. `domain/CallQualityMonitor.kt`
19. `domain/AudioConfigEngine.kt`
20. `domain/DesktopIntegration.kt`

Expected deleted files (1):
1. `data/pjsip/PjsipBridge.kt`

Expected renamed files (2):
1. `domain/RemoteUriParser.kt` -> `domain/CallerInfo.kt`
2. `domain/RemoteUriParserTest.kt` -> `domain/CallerInfoTest.kt`

Expected new test files (5):
1. `util/TimeFormatTest.kt`
2. `navigation/RootComponentTest.kt`
3. `feature/registration/RegistrationDoubleConnectTest.kt`
4. `feature/dialer/DialerMakeCallErrorTest.kt`
5. `domain/SipErrorTest.kt`

- [ ] Final commit: `refactor: Phase 3 architecture refactor complete`

---

## Bug Fix Summary

| Bug | Root Cause | Fix Location | Fix Description |
|-----|-----------|-------------|-----------------|
| Mute/Unmute broken | `adjustRxLevel()` unreliable | `PjsipCallManager.toggleMute()` | Use `stopTransmit()/startTransmit()` on capture media to/from call audio media. Never `delete()` captureDevMedia. |
| Hold PJ_EINVALIDOP | reinvite sent while previous still in-flight | `PjsipCallManager.toggleHold()` | `holdInProgress` boolean guard, log and reject duplicate attempts |
| Destroy ordering | Poll stopped before unregister packet sent | `PjsipEngine.shutdown()` | Order: callManager.destroy() -> accountManager.destroy() (sends REGISTER with expires=0 + waits) -> endpointManager.stopPolling() -> endpointManager.destroy() |
| Registration race (PJSIP_EBUSY) | `setRegistration(false)` during mid-transaction | `PjsipAccountManager.register()` | Wrap `setRegistration(false)` in try-catch, log warning, continue teardown |
| 403 flood from rapid re-registration | No rate limit in component layer | `RegistrationComponent.connect()` + `PjsipAccountManager.register()` | Component-level guard (`if Registering return`), engine-level 1s rate limit |

## Architecture After Refactor

```
domain/
  CallEngine.kt              (interface, unchanged shape)
  RegistrationEngine.kt      (interface, init/destroy removed)
  SipStackLifecycle.kt       (NEW interface)
  CallState.kt               (unchanged)
  RegistrationState.kt       (Failed.message -> Failed.error)
  SipCredentials.kt          (uses SipConstants.DEFAULT_PORT)
  SipConstants.kt            (NEW)
  SipError.kt                (NEW)
  CallerInfo.kt              (renamed from RemoteUriParser.kt)
  ConnectionManager.kt       (NEW stub)
  TransportConfig.kt         (NEW stub)
  CallQualityMonitor.kt      (NEW stub)
  AudioConfigEngine.kt       (NEW stub)
  DesktopIntegration.kt      (NEW stub)

data/pjsip/
  PjsipEngine.kt             (NEW facade, implements 3 interfaces)
  PjsipEndpointManager.kt    (NEW)
  PjsipAccountManager.kt     (NEW)
  PjsipCallManager.kt        (NEW)
  PjsipAccount.kt            (depends on AccountManager)
  PjsipCall.kt               (depends on CallManager)
  PjsipLogWriter.kt          (unchanged)
  NativeLibraryLoader.kt     (NEW)

data/settings/
  AppSettings.kt             (unchanged)

di/
  AppModule.kt               (now just aggregates modules)
  SipModule.kt               (NEW)
  SettingsModule.kt           (NEW)
  FeatureModule.kt            (NEW)

navigation/
  RootComponent.kt           (uses ComponentFactory)
  RootContent.kt             (uses tokens)
  Screen.kt                  (unchanged)
  ComponentFactory.kt        (NEW interface)
  ComponentFactoryImpl.kt    (NEW)

feature/registration/
  RegistrationComponent.kt   (unchanged logic)
  RegistrationScreen.kt      (uses Strings + tokens)
  RegistrationModel.kt       (renamed to FormState.kt — already done in Phase 2)

feature/dialer/
  DialerComponent.kt         (unchanged)
  DialerScreen.kt            (uses Strings + tokens, no tokens param)

ui/theme/
  AppTokens.kt               (expanded with shapes, sizes, alpha, animation)
  Theme.kt                   (unchanged)

ui/component/
  ConnectButton.kt           (uses Strings + tokens)
  ConnectionStatusCard.kt    (uses Strings + tokens + SipError)
  SipCredentialsForm.kt      (unchanged)

ui/strings/
  Strings.kt                 (NEW)

util/
  TimeFormat.kt              (NEW)
```
