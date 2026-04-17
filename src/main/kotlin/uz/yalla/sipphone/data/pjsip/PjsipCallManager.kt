package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsua_call_flag
import org.pjsip.pjsua2.pjsua_call_media_status
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

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)
    private var active: ActiveCall? = null

    @Volatile
    private var holdInProgress = false
    private var holdTimeoutJob: Job? = null
    private var hangupTimeoutJob: Job? = null

    init {
        scope.launch {
            accountProvider.incomingCalls.collect { handleIncomingCall(it) }
        }
    }

    fun isCallManagerDestroyed(): Boolean = isDestroyed()

    // captureDevMedia is owned by the audio device manager — never delete it here
    private fun applyMuteState(call: PjsipCall, muted: Boolean) {
        call.getInfo().use { info ->
            for (i in 0 until info.media.size) {
                val media = info.media[i]
                if (media.type != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue
                if (media.status != pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) continue
                val audioMedia = call.getAudioMedia(i)
                val captureMedia = audioMediaProvider.getCaptureDevMedia()
                if (muted) captureMedia.stopTransmit(audioMedia)
                else captureMedia.startTransmit(audioMedia)
                return@use
            }
        }
    }

    private fun applyHoldState(call: PjsipCall, hold: Boolean, state: CallState.Active) {
        withCallOpParam { prm ->
            if (hold) {
                call.setHold(prm)
            } else {
                prm.opt.flag = pjsua_call_flag.PJSUA_CALL_UNHOLD.toLong()
                call.reinvite(prm)
            }
        }
        _callState.value = state.copy(isOnHold = hold)
        holdTimeoutJob?.cancel()
        holdTimeoutJob = scope.launch {
            delay(HOLD_TIMEOUT_MS)
            if (holdInProgress) {
                logger.warn { "Hold timeout — resetting holdInProgress flag" }
                holdInProgress = false
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

        return runCatching {
            val call = PjsipCall(this, acc)
            val uri = SipConstants.buildCallUri(number, acc.server)
            withCallOpParam { prm -> call.makeCall(uri, prm) }
            val id = UUID.randomUUID().toString()
            active = ActiveCall(call, id, resolvedAccountId)
            _callState.value = CallState.Ringing(
                callId = id,
                callerNumber = number,
                callerName = null,
                isOutbound = true,
                accountId = resolvedAccountId,
            )
        }.onFailure {
            logger.error(it) { "makeCall failed on account $resolvedAccountId" }
            _callState.value = CallState.Idle
        }
    }

    suspend fun answerCall() {
        val call = active?.call ?: return
        val ringing = _callState.value as? CallState.Ringing ?: return
        if (ringing.isOutbound) return
        runCatching {
            withCallOpParam(statusCode = SipConstants.STATUS_OK) { prm -> call.answer(prm) }
        }.onFailure { logger.error(it) { "answerCall failed" } }
    }

    suspend fun hangupCall() {
        val a = active ?: return
        runCatching {
            _callState.value = CallState.Ending(callId = a.id, accountId = a.accountId)
            withCallOpParam { prm -> a.call.hangup(prm) }
            // Safety net: some PJSIP error paths never fire onCallDisconnected
            hangupTimeoutJob?.cancel()
            hangupTimeoutJob = scope.launch {
                delay(HANGUP_TIMEOUT_MS)
                if (_callState.value is CallState.Ending) {
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
        val state = _callState.value as? CallState.Active ?: return
        val call = active?.call ?: return
        runCatching {
            applyMuteState(call, muted = !state.isMuted)
            _callState.value = state.copy(isMuted = !state.isMuted)
        }.onFailure { logger.error(it) { "toggleMute failed" } }
    }

    suspend fun toggleHold() {
        val state = _callState.value as? CallState.Active ?: return
        if (holdInProgress) {
            logger.warn { "Hold/resume operation already in progress, ignoring" }
            return
        }
        val call = active?.call ?: return
        holdInProgress = true
        runCatching {
            applyHoldState(call, hold = !state.isOnHold, state = state)
        }.onFailure {
            holdInProgress = false
            logger.error(it) { "toggleHold failed" }
        }
    }

    suspend fun setMute(callId: String, muted: Boolean) {
        val state = _callState.value as? CallState.Active ?: return
        if (state.callId != callId) {
            logger.warn { "setMute: callId mismatch (expected=${state.callId}, got=$callId)" }
            return
        }
        if (state.isMuted == muted) return
        val call = active?.call ?: return
        runCatching {
            applyMuteState(call, muted)
            _callState.value = state.copy(isMuted = muted)
        }.onFailure { logger.error(it) { "setMute failed" } }
    }

    suspend fun setHold(callId: String, onHold: Boolean) {
        val state = _callState.value as? CallState.Active ?: return
        if (state.callId != callId) {
            logger.warn { "setHold: callId mismatch (expected=${state.callId}, got=$callId)" }
            return
        }
        if (state.isOnHold == onHold) return
        if (holdInProgress) {
            logger.warn { "Hold/resume operation already in progress, ignoring" }
            return
        }
        val call = active?.call ?: return
        holdInProgress = true
        runCatching {
            applyHoldState(call, hold = onHold, state = state)
        }.onFailure {
            holdInProgress = false
            logger.error(it) { "setHold failed" }
        }
    }

    fun onCallConfirmed(call: PjsipCall) {
        if (call !== active?.call) return
        when (val state = _callState.value) {
            is CallState.Ending ->
                logger.warn { "onCallConfirmed ignored — call already in Ending state" }
            is CallState.Ringing -> _callState.value = CallState.Active(
                callId = state.callId,
                remoteNumber = state.callerNumber,
                remoteName = state.callerName,
                isOutbound = state.isOutbound,
                isMuted = false,
                isOnHold = false,
                accountId = state.accountId,
                remoteUri = state.remoteUri,
            )
            else -> {}
        }
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

    private fun handleIncomingCall(event: IncomingCallEvent) {
        val accountId = event.accountId
        val callId = event.callId
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
                _callState.value = CallState.Ringing(
                    callId = id,
                    callerNumber = callerInfo.number,
                    callerName = callerInfo.displayName,
                    isOutbound = false,
                    accountId = accountId,
                    remoteUri = remoteUri,
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
        // Media state callback = re-INVITE completed; reset hold guard
        holdInProgress = false
        holdTimeoutJob?.cancel()
        holdTimeoutJob = null

        call.getInfo().use { info ->
            for (i in 0 until info.media.size) {
                val media = info.media[i]
                if (media.type != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue
                if (media.status != pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) continue
                val audioMedia = call.getAudioMedia(i)
                val playbackMedia = audioMediaProvider.getPlaybackDevMedia()
                val captureMedia = audioMediaProvider.getCaptureDevMedia()
                audioMedia.startTransmit(playbackMedia)
                val isMuted = (_callState.value as? CallState.Active)?.isMuted == true
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
                break
            }
        }
    }

    fun destroy() {
        scope.cancel()
        active?.call?.let { call ->
            runCatching { withCallOpParam { prm -> call.hangup(prm) } }
            call.safeDelete()
        }
        resetCallState()
    }

    private fun resetCallState() {
        active = null
        _callState.value = CallState.Idle
    }

    companion object {
        private const val HOLD_TIMEOUT_MS = 15_000L
        private const val HANGUP_TIMEOUT_MS = 10_000L
    }
}
