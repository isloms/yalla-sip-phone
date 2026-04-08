package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
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
import uz.yalla.sipphone.domain.SipStackLifecycle
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Production implementation of [SipStackLifecycle] and [CallEngine]
 * backed by the pjsua2 SWIG bindings.
 *
 * All operations are marshalled onto a dedicated single-thread coroutine context (`pjsip-event-loop`)
 * that serves as pjsip's event loop. Never call pjsip SWIG objects from any other thread.
 *
 * Registration is no longer handled here — it is delegated to [PjsipSipAccountManager] which
 * wraps [PjsipAccountManager] with per-account reconnection and state management.
 *
 * Exposes [accountManager] and [pjDispatcher] for DI wiring with [PjsipSipAccountManager].
 *
 * SWIG lifecycle: always call [shutdown] before the process exits to avoid native memory leaks.
 * [shutdown] is idempotent — subsequent calls are no-ops.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class PjsipEngine : SipStackLifecycle, CallEngine {

    private val destroyed = AtomicBoolean(false)

    /** Single-thread dispatcher for all pjsip operations. Exposed for DI. */
    @Suppress("OPT_IN_USAGE")
    private val closeableDispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("pjsip-event-loop")
    val pjDispatcher: CoroutineDispatcher get() = closeableDispatcher

    private val scope = CoroutineScope(SupervisorJob() + closeableDispatcher)

    private val endpointManager = PjsipEndpointManager(closeableDispatcher)

    /** Low-level multi-account manager. Exposed for DI wiring with [PjsipSipAccountManager]. */
    val accountManager = PjsipAccountManager(::isDestroyed)

    private val callManager = PjsipCallManager(
        accountProvider = accountManager,
        audioMediaProvider = object : AudioMediaProvider {
            override fun getPlaybackDevMedia() = endpointManager.getPlaybackDevMedia()
            override fun getCaptureDevMedia() = endpointManager.getCaptureDevMedia()
        },
        isDestroyed = ::isDestroyed,
        pjDispatcher = closeableDispatcher,
    )

    init {
        accountManager.incomingCallListener = callManager
    }

    private fun isDestroyed(): Boolean = destroyed.get()

    override suspend fun initialize(): Result<Unit> = withContext(closeableDispatcher) {
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
        try {
            withContext(closeableDispatcher) {
                callManager.destroy()
                accountManager.destroy()
                endpointManager.stopPolling()
                endpointManager.destroy()
            }
        } catch (e: Exception) {
            logger.warn(e) { "Error during pjsip shutdown" }
        } finally {
            scope.cancel()
            runCatching { closeableDispatcher.close() }
        }
    }

    override val callState: StateFlow<CallState>
        get() = callManager.callState

    override suspend fun makeCall(number: String, accountId: String): Result<Unit> =
        withContext(pjDispatcher) { callManager.makeCall(number, accountId) }

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
