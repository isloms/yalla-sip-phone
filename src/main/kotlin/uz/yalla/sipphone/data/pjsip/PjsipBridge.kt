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
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_stun_use
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.parseRemoteUri
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class PjsipBridge : RegistrationEngine, CallEngine {

    private val destroyed = AtomicBoolean(false)
    private val pjDispatcher = newSingleThreadContext("pjsip-event-loop")
    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    override val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    override val callState: StateFlow<CallState> = _callState.asStateFlow()

    private var currentCall: PjsipCall? = null
    private var lastRegisteredServer: String? = null

    private lateinit var endpoint: Endpoint
    private var account: PjsipAccount? = null
    private var pollJob: Job? = null
    private var logWriter: PjsipLogWriter? = null

    internal fun isDestroyed(): Boolean = destroyed.get()

    internal fun updateRegistrationState(state: RegistrationState) {
        if (state is RegistrationState.Registered) {
            lastRegisteredServer = state.server
        }
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

    override suspend fun makeCall(number: String): Result<Unit> = withContext(pjDispatcher) {
        if (currentCall != null) return@withContext Result.failure(IllegalStateException("Call already active"))
        val acc = account ?: return@withContext Result.failure(IllegalStateException("Not registered"))
        try {
            val call = PjsipCall(this@PjsipBridge, acc)
            val uri = "sip:$number@${extractHost(lastRegisteredServer)}"
            val prm = CallOpParam(true)
            call.makeCall(uri, prm)
            prm.delete()
            currentCall = call
            _callState.value = CallState.Active(
                remoteNumber = number,
                remoteName = null,
                isOutbound = true,
                isMuted = false,
                isOnHold = false,
            )
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "makeCall failed" }
            _callState.value = CallState.Idle
            Result.failure(e)
        }
    }

    override suspend fun answerCall() = withContext(pjDispatcher) {
        val call = currentCall ?: return@withContext
        if (_callState.value !is CallState.Ringing) return@withContext
        try {
            val prm = CallOpParam()
            prm.statusCode = 200
            call.answer(prm)
            prm.delete()
        } catch (e: Exception) {
            logger.error(e) { "answerCall failed" }
        }
    }

    override suspend fun hangupCall() = withContext(pjDispatcher) {
        val call = currentCall ?: return@withContext
        try {
            _callState.value = CallState.Ending
            val prm = CallOpParam()
            call.hangup(prm)
            prm.delete()
        } catch (e: Exception) {
            logger.error(e) { "hangupCall failed" }
            clearCurrentCall()
        }
    }

    override suspend fun toggleMute() = withContext(pjDispatcher) {
        val state = _callState.value
        if (state !is CallState.Active) return@withContext
        try {
            val captureMedia = endpoint.audDevManager().captureDevMedia
            if (state.isMuted) captureMedia.adjustRxLevel(1.0f) else captureMedia.adjustRxLevel(0.0f)
            captureMedia.delete()
            _callState.value = state.copy(isMuted = !state.isMuted)
        } catch (e: Exception) {
            logger.error(e) { "toggleMute failed" }
        }
    }

    override suspend fun toggleHold() = withContext(pjDispatcher) {
        val state = _callState.value
        if (state !is CallState.Active) return@withContext
        val call = currentCall ?: return@withContext
        try {
            val prm = CallOpParam()
            if (state.isOnHold) { prm.opt.flag = 0; call.reinvite(prm) } else call.setHold(prm)
            prm.delete()
            _callState.value = state.copy(isOnHold = !state.isOnHold)
        } catch (e: Exception) {
            logger.error(e) { "toggleHold failed" }
        }
    }

    internal fun onCallConfirmed(call: PjsipCall) {
        val state = _callState.value
        if (state is CallState.Ringing) {
            _callState.value = CallState.Active(
                remoteNumber = state.callerNumber,
                remoteName = state.callerName,
                isOutbound = false,
                isMuted = false,
                isOnHold = false,
            )
        }
    }

    internal fun onCallDisconnected(call: PjsipCall) {
        clearCurrentCall()
        try {
            call.delete()
        } catch (e: Exception) {
            logger.warn(e) { "Error deleting call object" }
        }
    }

    internal fun onIncomingCall(callId: Int) {
        val acc = account ?: return
        if (currentCall != null) {
            logger.warn { "Rejecting incoming call (already in call)" }
            try {
                val call = PjsipCall(this, acc, callId)
                val prm = CallOpParam()
                prm.statusCode = 486
                call.hangup(prm)
                prm.delete()
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
            val callerInfo = parseRemoteUri(info.remoteUri)
            info.delete()
            _callState.value = CallState.Ringing(
                callerNumber = callerInfo.number,
                callerName = callerInfo.displayName,
            )
            logger.info { "Incoming call from: ${callerInfo.displayName ?: callerInfo.number}" }
        } catch (e: Exception) {
            logger.error(e) { "Error handling incoming call" }
            clearCurrentCall()
        }
    }

    private fun clearCurrentCall() {
        currentCall = null
        _callState.value = CallState.Idle
    }

    internal fun getPlaybackDevMedia(): AudioMedia = endpoint.audDevManager().playbackDevMedia
    internal fun getCaptureDevMedia(): AudioMedia = endpoint.audDevManager().captureDevMedia

    private fun extractHost(serverUri: String?): String {
        val uri = serverUri ?: return ""
        val atIndex = uri.lastIndexOf('@')
        return if (atIndex >= 0) uri.substring(atIndex + 1) else uri
    }

    override suspend fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        withContext(pjDispatcher) {
            currentCall?.let { call ->
                try {
                    val prm = CallOpParam()
                    call.hangup(prm)
                    prm.delete()
                } catch (_: Exception) {}
            }
            currentCall = null
            _callState.value = CallState.Idle
            pollJob?.cancel()
            pollJob?.join()
            try { account?.shutdown() } catch (_: Exception) {}
            account = null
            logWriter = null
            _registrationState.value = RegistrationState.Idle
        }
        scope.cancel()
        pjDispatcher.close()
    }
}
