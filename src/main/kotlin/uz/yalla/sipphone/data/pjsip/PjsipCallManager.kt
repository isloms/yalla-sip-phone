package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.pjsua_call_flag
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.parseRemoteUri

private val logger = KotlinLogging.logger {}

interface AudioMediaProvider {
    fun getPlaybackDevMedia(): AudioMedia
    fun getCaptureDevMedia(): AudioMedia
}

private data class ActiveCall(val call: PjsipCall, val id: String, val accountId: String)

class PjsipCallManager(
    private val accountProvider: AccountProvider,
    private val audioMediaProvider: AudioMediaProvider,
    private val isDestroyed: () -> Boolean,
    private val pjDispatcher: CoroutineContext,
) {

    private val stateMachine = CallStateMachine()
    val callState: StateFlow<CallState> get() = stateMachine.state

    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)
    private var active: ActiveCall? = null

    private val holdController = HoldController(scope)
    private var hangupTimeoutJob: Job? = null

    fun isCallManagerDestroyed(): Boolean = isDestroyed()

    private fun applyMuteState(call: PjsipCall, muted: Boolean) {
        call.getInfo().use { info ->
            info.forEachActiveAudioMedia { i, _ ->
                val audioMedia = call.getAudioMedia(i)
                val captureMedia = audioMediaProvider.getCaptureDevMedia()
                if (muted) captureMedia.stopTransmit(audioMedia)
                else captureMedia.startTransmit(audioMedia)
            }
        }
    }

    private fun issueHoldOp(call: PjsipCall, hold: Boolean): Boolean =
        holdController.request {
            withCallOpParam { prm ->
                if (hold) {
                    call.setHold(prm)
                } else {
                    prm.opt.flag = pjsua_call_flag.PJSUA_CALL_UNHOLD.toLong()
                    call.reinvite(prm)
                }
            }
        }

    suspend fun makeCall(number: String, accountId: String = ""): Result<Unit> {
        if (active != null) return Result.failure(IllegalStateException("Call already active"))

        val acc: PjsipAccount
        val resolvedAccountId: String
        if (accountId.isNotEmpty()) {
            acc = accountProvider.getAccount(accountId)
                ?: return Result.failure(IllegalStateException("Account $accountId not found"))
            resolvedAccountId = accountId
        } else {
            val firstAcc = accountProvider.getFirstConnectedAccount()
                ?: return Result.failure(IllegalStateException("No connected account"))
            acc = firstAcc
            resolvedAccountId = firstAcc.accountId
        }

        return runCatching<Unit> {
            val call = PjsipCall(this, acc)
            val uri = SipConstants.buildCallUri(number, acc.server)
            withCallOpParam { prm -> call.makeCall(uri, prm) }
            val id = UUID.randomUUID().toString()
            active = ActiveCall(call, id, resolvedAccountId)
            stateMachine.dispatch(
                CallEvent.OutgoingDial(
                    callId = id,
                    remoteNumber = number,
                    accountId = resolvedAccountId,
                ),
            )
        }.onFailure {
            logger.error(it) { "makeCall failed on account $resolvedAccountId" }
            resetCallState()
        }
    }

    suspend fun answerCall() {
        val call = active?.call ?: return
        val ringing = stateMachine.state.value as? CallState.Ringing ?: return
        if (ringing.isOutbound) return
        runCatching {
            withCallOpParam(statusCode = SipConstants.STATUS_OK) { prm -> call.answer(prm) }
        }.onFailure { logger.error(it) { "answerCall failed" } }
    }

    suspend fun hangupCall() {
        val a = active ?: return
        runCatching {
            stateMachine.dispatch(CallEvent.LocalHangup)
            withCallOpParam { prm -> a.call.hangup(prm) }
            hangupTimeoutJob?.cancel()
            hangupTimeoutJob = scope.launch {
                delay(HANGUP_TIMEOUT_MS)
                if (stateMachine.state.value is CallState.Ending) {
                    logger.warn { "Hangup timeout — forcing Idle state" }
                    active?.call?.safeDelete()
                    resetCallState()
                }
            }
        }.onFailure {
            logger.error(it) { "hangupCall failed" }
            resetCallState()
        }
    }

    suspend fun toggleMute() {
        val state = stateMachine.state.value as? CallState.Active ?: return
        val call = active?.call ?: return
        runCatching {
            val nextMuted = !state.isMuted
            applyMuteState(call, muted = nextMuted)
            stateMachine.dispatch(CallEvent.MuteChanged(nextMuted))
        }.onFailure { logger.error(it) { "toggleMute failed" } }
    }

    suspend fun toggleHold() {
        val state = stateMachine.state.value as? CallState.Active ?: return
        val call = active?.call ?: return
        val targetHold = !state.isOnHold
        val issued = runCatching { issueHoldOp(call, targetHold) }
            .onFailure { logger.error(it) { "toggleHold failed" } }
            .getOrDefault(false)
        if (!issued) {
            logger.warn { "Hold/resume operation already in progress or failed, ignoring" }
            return
        }
        stateMachine.dispatch(CallEvent.HoldChanged(targetHold))
    }

    suspend fun setMute(callId: String, muted: Boolean) {
        val state = stateMachine.state.value as? CallState.Active ?: return
        if (state.callId != callId) {
            logger.warn { "setMute: callId mismatch (expected=${state.callId}, got=$callId)" }
            return
        }
        if (state.isMuted == muted) return
        val call = active?.call ?: return
        runCatching {
            applyMuteState(call, muted)
            stateMachine.dispatch(CallEvent.MuteChanged(muted))
        }.onFailure { logger.error(it) { "setMute failed" } }
    }

    suspend fun setHold(callId: String, onHold: Boolean) {
        val state = stateMachine.state.value as? CallState.Active ?: return
        if (state.callId != callId) {
            logger.warn { "setHold: callId mismatch (expected=${state.callId}, got=$callId)" }
            return
        }
        if (state.isOnHold == onHold) return
        val call = active?.call ?: return
        val issued = runCatching { issueHoldOp(call, onHold) }
            .onFailure { logger.error(it) { "setHold failed" } }
            .getOrDefault(false)
        if (!issued) {
            logger.warn { "Hold/resume operation already in progress or failed, ignoring" }
            return
        }
        stateMachine.dispatch(CallEvent.HoldChanged(onHold))
    }

    fun onCallConfirmed(call: PjsipCall) {
        if (call !== active?.call) return
        if (stateMachine.state.value is CallState.Ending) {
            logger.warn { "onCallConfirmed ignored — call already in Ending state" }
            return
        }
        stateMachine.dispatch(CallEvent.Answered)
    }

    fun onCallDisconnected(call: PjsipCall) {
        // Non-current call (e.g. we rejected an incoming with 486) — clean up and exit.
        if (call !== active?.call) {
            call.safeDelete()
            return
        }
        hangupTimeoutJob?.cancel()
        hangupTimeoutJob = null
        resetCallState()
        call.safeDelete()
    }

    fun handleIncomingCall(accountId: String, callId: Int) {
        val acc = accountProvider.getAccount(accountId) ?: run {
            logger.warn { "Incoming call on unknown account $accountId — ignoring" }
            return
        }
        if (active != null) {
            logger.warn { "Rejecting incoming call on $accountId (already in call)" }
            val rejectCall = PjsipCall(this, acc, callId)
            runCatching {
                withCallOpParam(statusCode = SipConstants.STATUS_BUSY_HERE) { prm -> rejectCall.hangup(prm) }
            }.onFailure { logger.error(it) { "Failed to reject incoming call" } }
            rejectCall.safeDelete()
            return
        }
        runCatching {
            val call = PjsipCall(this, acc, callId)
            val id = UUID.randomUUID().toString()
            active = ActiveCall(call, id, accountId)
            call.getInfo().use { info ->
                val remoteUri = info.remoteUri
                val callerInfo = parseRemoteUri(remoteUri)
                logger.debug {
                    "Incoming call detail: account=$accountId pjCallId=$callId " +
                        "sipCallId=${info.callIdString} remote=$remoteUri local=${info.localUri} " +
                        "media=${info.media?.size ?: 0}"
                }
                stateMachine.dispatch(
                    CallEvent.IncomingRing(
                        callId = id,
                        remoteNumber = callerInfo.number,
                        remoteName = callerInfo.displayName,
                        accountId = accountId,
                        remoteUri = remoteUri,
                    ),
                )
                logger.info {
                    "Incoming call on $accountId from: ${callerInfo.displayName ?: callerInfo.number}"
                }
            }
        }.onFailure {
            logger.error(it) { "Error handling incoming call on $accountId" }
            resetCallState()
        }
    }

    suspend fun sendDtmf(callId: String, digits: String): Result<Unit> {
        if (!digits.matches(Regex("[0-9*#A-Da-d]+"))) {
            return Result.failure(IllegalArgumentException("Invalid DTMF digits: $digits"))
        }
        val a = active ?: return Result.failure(IllegalStateException("No active call"))
        if (a.id != callId) {
            logger.warn { "sendDtmf: callId mismatch (expected=${a.id}, got=$callId)" }
            return Result.failure(IllegalStateException("callId mismatch"))
        }
        return runCatching {
            a.call.dialDtmf(digits)
            logger.info { "DTMF sent: $digits" }
        }.onFailure { logger.error(it) { "DTMF failed" } }
    }

    suspend fun transferCall(callId: String, destination: String): Result<Unit> {
        val a = active ?: return Result.failure(IllegalStateException("No active call"))
        if (a.id != callId) {
            logger.warn { "transferCall: callId mismatch (expected=${a.id}, got=$callId)" }
            return Result.failure(IllegalStateException("callId mismatch"))
        }
        val host = accountProvider.getAccount(a.accountId)?.server
            ?: return Result.failure(IllegalStateException("No server for account ${a.accountId}"))
        return runCatching {
            val destUri = SipConstants.buildCallUri(destination, host)
            withCallOpParam { prm -> a.call.xfer(destUri, prm) }
            logger.info { "Call transferred to: $destination" }
        }.onFailure { logger.error(it) { "Transfer failed" } }
    }

    fun connectCallAudio(call: PjsipCall) {
        if (call !== active?.call) return
        holdController.onMediaStateChanged()

        call.getInfo().use { info ->
            info.forEachActiveAudioMedia { i, _ ->
                val audioMedia = call.getAudioMedia(i)
                val playbackMedia = audioMediaProvider.getPlaybackDevMedia()
                val captureMedia = audioMediaProvider.getCaptureDevMedia()
                audioMedia.startTransmit(playbackMedia)
                val isMuted = (stateMachine.state.value as? CallState.Active)?.isMuted == true
                if (!isMuted) captureMedia.startTransmit(audioMedia)
                logger.info { "Audio media connected for media index $i (muted=$isMuted)" }
                runCatching {
                    call.getStreamInfo(i.toLong()).use { si ->
                        logger.info {
                            "Stream: codec=${si.codecName}/${si.codecClockRate}Hz, " +
                                "dir=${si.dir}, remote=${si.remoteRtpAddress}"
                        }
                    }
                }.onFailure { logger.warn(it) { "Could not get stream info" } }
            }
        }
    }

    fun destroy() {
        holdController.cancel()
        scope.cancel()
        active?.call?.let { call ->
            runCatching { withCallOpParam { prm -> call.hangup(prm) } }
            call.safeDelete()
        }
        resetCallState()
    }

    private fun resetCallState() {
        active = null
        stateMachine.dispatch(CallEvent.RemoteDisconnect)
    }

    companion object {
        private const val HANGUP_TIMEOUT_MS = 10_000L
    }
}
