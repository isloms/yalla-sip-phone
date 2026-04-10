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

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class PjsipEngine : SipStackLifecycle, CallEngine {

    private val destroyed = AtomicBoolean(false)

    @Suppress("OPT_IN_USAGE")
    private val closeableDispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("pjsip-event-loop")
    val pjDispatcher: CoroutineDispatcher get() = closeableDispatcher

    private val pjScope = CoroutineScope(SupervisorJob() + closeableDispatcher)

    private val endpointManager = PjsipEndpointManager(closeableDispatcher)

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
        } catch (e: Throwable) {
            // Catch Exception + NoClassDefFoundError (./gradlew run classpath issue)
            logger.warn(e) { "Error during pjsip shutdown" }
        } finally {
            pjScope.cancel()
            runCatching { closeableDispatcher.close() }
        }
    }

    override val callState: StateFlow<CallState>
        get() = callManager.callState

    override suspend fun makeCall(number: String, accountId: String): Result<Unit> =
        withContext(pjDispatcher) { callManager.makeCall(number, accountId) }

    override suspend fun answerCall(): Result<Unit> =
        withContext(pjDispatcher) { runCatching { callManager.answerCall() } }

    override suspend fun hangupCall(): Result<Unit> =
        withContext(pjDispatcher) { runCatching { callManager.hangupCall() } }

    override suspend fun toggleMute(): Result<Unit> =
        withContext(pjDispatcher) { runCatching { callManager.toggleMute() } }

    override suspend fun toggleHold(): Result<Unit> =
        withContext(pjDispatcher) { runCatching { callManager.toggleHold() } }

    override suspend fun setMute(callId: String, muted: Boolean): Unit =
        withContext(pjDispatcher) { callManager.setMute(callId, muted) }

    override suspend fun setHold(callId: String, onHold: Boolean): Unit =
        withContext(pjDispatcher) { callManager.setHold(callId, onHold) }

    override suspend fun sendDtmf(callId: String, digits: String): Result<Unit> =
        withContext(pjDispatcher) { callManager.sendDtmf(callId, digits) }

    override suspend fun transferCall(callId: String, destination: String): Result<Unit> =
        withContext(pjDispatcher) { callManager.transferCall(callId, destination) }
}
