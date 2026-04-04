package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class PjsipBridge : SipEngine {

    private val destroyed = AtomicBoolean(false)
    private val pjDispatcher = newSingleThreadContext("pjsip-event-loop")
    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    override val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private lateinit var endpoint: Endpoint
    private var account: PjsipAccount? = null
    private var pollJob: Job? = null
    private var logWriter: PjsipLogWriter? = null

    internal fun isDestroyed(): Boolean = destroyed.get()

    internal fun updateRegistrationState(state: RegistrationState) {
        _registrationState.value = state
    }

    override suspend fun init(): Result<Unit> = withContext(pjDispatcher) {
        try {
            loadNativeLibrary()
            initEndpoint()
            createTransport()
            endpoint.libStart()
            startPolling()

            val version = endpoint.libVersion()
            logger.info { "pjsip initialized, version: ${version.full}" }
            version.delete()

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "pjsip init failed" }
            Result.failure(e)
        }
    }

    private fun loadNativeLibrary() {
        val libDir = System.getProperty("pjsip.library.path")
        if (libDir != null) {
            System.load("$libDir/libpjsua2.jnilib")
        } else {
            System.loadLibrary("pjsua2")
        }
    }

    private fun initEndpoint() {
        endpoint = Endpoint()
        endpoint.libCreate()

        val epConfig = EpConfig()
        epConfig.uaConfig.threadCnt = 0
        epConfig.uaConfig.mainThreadOnly = false
        epConfig.uaConfig.userAgent = "YallaSipPhone/1.0"

        logWriter = PjsipLogWriter()
        epConfig.logConfig.writer = logWriter
        epConfig.logConfig.level = 4
        epConfig.logConfig.consoleLevel = 0

        endpoint.libInit(epConfig)
        epConfig.delete()
    }

    private fun createTransport() {
        val transportConfig = TransportConfig()
        transportConfig.port = 0
        endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig)
        transportConfig.delete()
    }

    private fun startPolling() {
        pollJob = scope.launch(pjDispatcher) {
            if (!endpoint.libIsThreadRegistered()) {
                endpoint.libRegisterThread("pjsip-poll")
            }
            while (isActive) {
                endpoint.libHandleEvents(50)
                yield()
            }
        }
    }

    override suspend fun register(credentials: SipCredentials): Result<Unit> = withContext(pjDispatcher) {
        _registrationState.value = RegistrationState.Registering

        // Must delete() previous account to prevent GC finalizer crash
        account?.shutdown()
        account?.delete()
        account = null

        val accountConfig = AccountConfig()
        val authCred = AuthCredInfo("digest", "*", credentials.username, 0, credentials.password)
        try {
            accountConfig.idUri = "sip:${credentials.username}@${credentials.server}"
            accountConfig.regConfig.registrarUri = "sip:${credentials.server}:${credentials.port}"
            accountConfig.sipConfig.authCreds.add(authCred)
            accountConfig.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
            accountConfig.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED

            account = PjsipAccount(this@PjsipBridge).apply {
                create(accountConfig, true)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Registration failed" }
            _registrationState.value = RegistrationState.Failed("Registration error: ${e.message}")
            Result.failure(e)
        } finally {
            accountConfig.delete()
            authCred.delete()
        }
    }

    override suspend fun unregister() = withContext(pjDispatcher) {
        val acc = account ?: return@withContext
        try {
            acc.setRegistration(false)
            withTimeout(5000) {
                _registrationState.first { it is RegistrationState.Idle }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "Unregistration timed out, forcing shutdown" }
        } catch (_: kotlinx.coroutines.CancellationException) {
            logger.debug { "Unregister cancelled (component destroyed)" }
        } catch (e: Exception) {
            logger.error(e) { "Unregister error" }
        } finally {
            acc.shutdown()
            acc.delete()
            account = null
            _registrationState.value = RegistrationState.Idle
        }
    }

    override suspend fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        withContext(pjDispatcher) {
            pollJob?.cancel()
            pollJob?.join()

            try { account?.shutdown() } catch (_: Exception) {}
            account = null
            logWriter = null

            // Skip libDestroy() -- it calls Runtime.gc() which triggers SWIG finalizers
            // on the finalizer thread (unregistered with pjsip), causing SIGSEGV.
            // OS reclaims all native resources on process exit.

            _registrationState.value = RegistrationState.Idle
        }
        scope.cancel()
        pjDispatcher.close()
    }
}
