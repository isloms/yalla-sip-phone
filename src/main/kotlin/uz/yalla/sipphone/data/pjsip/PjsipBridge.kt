package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_stun_use
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.SipEngine
import uz.yalla.sipphone.domain.SipEvent

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class PjsipBridge : SipEngine {
    private val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pjDispatcher = newSingleThreadContext("pjsip-event-loop")

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    override val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _events = MutableSharedFlow<SipEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<SipEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)
    private lateinit var endpoint: Endpoint
    private var account: PjsipAccount? = null
    private var pollJob: Job? = null
    private var logWriter: PjsipLogWriter? = null // must keep reference alive!

    internal fun updateRegistrationState(state: RegistrationState) {
        _registrationState.value = state
    }

    internal fun emitEvent(event: SipEvent) {
        _events.tryEmit(event)
    }

    override suspend fun init(): Result<Unit> = withContext(pjDispatcher) {
        try {
            // 1. Load native library
            try {
                System.loadLibrary("pjsua2")
            } catch (e: UnsatisfiedLinkError) {
                logger.error(e) { "Failed to load pjsua2 native library" }
                return@withContext Result.failure(e)
            }

            // 2. Create and init endpoint
            endpoint = Endpoint()
            endpoint.libCreate()

            val epConfig = EpConfig()
            epConfig.uaConfig.threadCnt = 0
            epConfig.uaConfig.mainThreadOnly = false // not needed with threadCnt=0
            epConfig.uaConfig.userAgent = "YallaSipPhone/1.0"

            // Route pjsip native logs to logback
            logWriter = PjsipLogWriter()
            epConfig.logConfig.writer = logWriter
            epConfig.logConfig.level = 4          // debug
            epConfig.logConfig.consoleLevel = 0   // disable console, use writer

            endpoint.libInit(epConfig)
            epConfig.delete() // SWIG cleanup: pjsip copied config data

            // 3. Create UDP transport
            val transportConfig = TransportConfig()
            transportConfig.port = 0 // auto-assign
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig)
            transportConfig.delete() // SWIG cleanup

            // 4. Start library
            endpoint.libStart()

            // 5. Register polling thread + start polling
            startPolling()

            val version = endpoint.libVersion()
            logger.info { "pjsip initialized, version: ${version.full}" }
            version.delete() // SWIG cleanup

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "pjsip init failed" }
            Result.failure(e)
        }
    }

    private fun startPolling() {
        pollJob = scope.launch(pjDispatcher) {
            // Register this thread with pjsip (defense-in-depth)
            if (!endpoint.libIsThreadRegistered()) {
                endpoint.libRegisterThread("pjsip-poll")
            }
            while (isActive) {
                endpoint.libHandleEvents(50) // 50ms timeout = ~20 polls/sec
            }
        }
    }

    override suspend fun register(credentials: SipCredentials): Result<Unit> = withContext(pjDispatcher) {
        try {
            _registrationState.value = RegistrationState.Registering

            // Cleanup previous account
            account?.shutdown()
            account = null

            val accountConfig = AccountConfig()
            val sipUri = "sip:${credentials.username}@${credentials.server}"

            accountConfig.idUri = sipUri
            accountConfig.regConfig.registrarUri = "sip:${credentials.server}:${credentials.port}"

            val authCred = AuthCredInfo("digest", "*", credentials.username, 0, credentials.password)
            accountConfig.sipConfig.authCreds.add(authCred)

            // NAT: disabled for LAN deployment
            accountConfig.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
            accountConfig.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED

            // Create account - onRegState callback handles state transitions
            account = PjsipAccount(this@PjsipBridge).apply {
                create(accountConfig, true)
            }

            accountConfig.delete() // SWIG cleanup
            authCred.delete()      // SWIG cleanup

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Registration failed" }
            _registrationState.value = RegistrationState.Failed("Registration error: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun unregister() = withContext(pjDispatcher) {
        val acc = account ?: return@withContext
        try {
            // Send REGISTER Expires:0, wait for server confirmation
            acc.setRegistration(false)
            withTimeout(5000) {
                _registrationState.first { it is RegistrationState.Idle }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "Unregistration timed out, forcing shutdown" }
        } catch (e: Exception) {
            logger.error(e) { "Unregister error" }
        } finally {
            acc.shutdown()
            account = null
            _registrationState.value = RegistrationState.Idle
        }
    }

    override suspend fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        withContext(pjDispatcher) {
            pollJob?.cancel()
            pollJob?.join() // wait for poll loop to fully exit

            account?.shutdown()
            account = null

            try {
                endpoint.libDestroy()
                endpoint.delete() // release SWIG pointer
            } catch (e: Exception) {
                logger.error(e) { "Error during pjsip destroy" }
            }

            logWriter?.delete()
            logWriter = null

            _registrationState.value = RegistrationState.Idle
        }
        scope.cancel()
        (pjDispatcher as CloseableCoroutineDispatcher).close()
    }
}
