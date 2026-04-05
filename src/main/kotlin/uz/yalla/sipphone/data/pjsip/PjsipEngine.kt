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

/**
 * Production implementation of [SipStackLifecycle], [RegistrationEngine], and [CallEngine]
 * backed by the pjsua2 SWIG bindings.
 *
 * All operations are marshalled onto a dedicated single-thread coroutine context (`pjsip-event-loop`)
 * that serves as pjsip's event loop. Never call pjsip SWIG objects from any other thread.
 *
 * SWIG lifecycle: always call [shutdown] before the process exits to avoid native memory leaks.
 * [shutdown] is idempotent — subsequent calls are no-ops.
 */
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
        pjDispatcher = pjDispatcher,
    )

    init {
        accountManager.incomingCallListener = callManager
    }

    private fun isDestroyed(): Boolean = destroyed.get()

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

    override val registrationState: StateFlow<RegistrationState>
        get() = accountManager.registrationState

    override suspend fun register(credentials: SipCredentials): Result<Unit> =
        withContext(pjDispatcher) { accountManager.register(credentials) }

    override suspend fun unregister(): Unit =
        withContext(pjDispatcher) { accountManager.unregister() }

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

    override suspend fun setMute(callId: String, muted: Boolean): Unit =
        withContext(pjDispatcher) { callManager.setMute(callId, muted) }

    override suspend fun setHold(callId: String, onHold: Boolean): Unit =
        withContext(pjDispatcher) { callManager.setHold(callId, onHold) }

    override suspend fun sendDtmf(callId: String, digits: String): Result<Unit> =
        withContext(pjDispatcher) { callManager.sendDtmf(callId, digits) }

    override suspend fun transferCall(callId: String, destination: String): Result<Unit> =
        withContext(pjDispatcher) { callManager.transferCall(callId, destination) }
}
