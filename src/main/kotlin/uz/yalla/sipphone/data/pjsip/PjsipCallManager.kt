package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.pjsip.pjsua2.CallOpParam
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.parseRemoteUri
import java.util.UUID

private val logger = KotlinLogging.logger {}

interface AudioMediaProvider {
    fun getPlaybackDevMedia(): org.pjsip.pjsua2.AudioMedia
    fun getCaptureDevMedia(): org.pjsip.pjsua2.AudioMedia
}

class PjsipCallManager(
    private val accountProvider: AccountProvider,
    private val audioMediaProvider: AudioMediaProvider,
    private val isDestroyed: () -> Boolean,
    private val pjDispatcher: kotlin.coroutines.CoroutineContext,
) : IncomingCallListener {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)
    private var currentCall: PjsipCall? = null
    private var currentCallId: String? = null
    private var currentAccountId: String? = null
    @Volatile
    private var holdInProgress = false
    private var holdTimeoutJob: Job? = null
    private var hangupTimeoutJob: Job? = null

    fun isCallManagerDestroyed(): Boolean = isDestroyed()

    private inline fun <R> withCallOpParam(
        statusCode: Int = 200,
        block: (CallOpParam) -> R,
    ): R {
        // useDefaultCallSetting=true ensures opt.audioCount=1 and other defaults.
        // Without this, reinvite() gets audioCount=0 → empty SDP → local 488 rejection.
        val prm = CallOpParam(true)
        prm.statusCode = statusCode
        return try {
            block(prm)
        } finally {
            prm.delete()
        }
    }

    // Do NOT call delete() on captureDevMedia — it is owned by the audio device manager
    private fun applyMuteState(call: PjsipCall, muted: Boolean) {
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
                    if (muted) {
                        captureMedia.stopTransmit(audioMedia)
                    } else {
                        captureMedia.startTransmit(audioMedia)
                    }
                    break
                }
            }
        } finally {
            callInfo.delete()
        }
    }

    private fun applyHoldState(call: PjsipCall, hold: Boolean, state: CallState.Active) {
        withCallOpParam { prm ->
            if (hold) {
                call.setHold(prm)
            } else {
                prm.opt.flag = org.pjsip.pjsua2.pjsua_call_flag.PJSUA_CALL_UNHOLD.toLong()
                call.reinvite(prm)
            }
        }
        _callState.value = state.copy(isOnHold = hold)
        // Start a timeout to reset holdInProgress if media callback never fires
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
        if (currentCall != null) return Result.failure(IllegalStateException("Call already active"))

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

        val host = acc.server
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
            currentCallId = UUID.randomUUID().toString()
            currentAccountId = resolvedAccountId
            _callState.value = CallState.Ringing(
                callId = currentCallId!!,
                callerNumber = number,
                callerName = null,
                isOutbound = true,
                accountId = resolvedAccountId,
            )
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "makeCall failed on account $resolvedAccountId" }
            _callState.value = CallState.Idle
            return Result.failure(e)
        }
    }

    suspend fun answerCall() {
        val call = currentCall ?: return
        val ringing = _callState.value as? CallState.Ringing ?: return
        if (ringing.isOutbound) return
        try {
            withCallOpParam(statusCode = SipConstants.STATUS_OK) { prm -> call.answer(prm) }
        } catch (e: Exception) {
            logger.error(e) { "answerCall failed" }
        }
    }

    suspend fun hangupCall() {
        val call = currentCall ?: return
        try {
            _callState.value = CallState.Ending(
                callId = currentCallId ?: "",
                accountId = currentAccountId ?: "",
            )
            withCallOpParam { prm -> call.hangup(prm) }
            // Safety net: force Idle after 10s if onCallDisconnected never fires
            hangupTimeoutJob?.cancel()
            hangupTimeoutJob = scope.launch {
                delay(HANGUP_TIMEOUT_MS)
                if (_callState.value is CallState.Ending) {
                    logger.warn { "Hangup timeout — forcing Idle state" }
                    try {
                        currentCall?.safeDelete()
                    } catch (e: Exception) {
                        logger.warn(e) { "Error deleting call on hangup timeout" }
                    }
                    resetCallState()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "hangupCall failed" }
            resetCallState()
        }
    }

    suspend fun toggleMute() {
        val state = _callState.value
        if (state !is CallState.Active) return
        val call = currentCall ?: return
        try {
            applyMuteState(call, muted = !state.isMuted)
            _callState.value = state.copy(isMuted = !state.isMuted)
        } catch (e: Exception) {
            logger.error(e) { "toggleMute failed" }
        }
    }

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
            applyHoldState(call, hold = !state.isOnHold, state = state)
        } catch (e: Exception) {
            holdInProgress = false
            logger.error(e) { "toggleHold failed" }
        }
    }

    suspend fun setMute(callId: String, muted: Boolean) {
        val state = _callState.value
        if (state !is CallState.Active) return
        if (state.callId != callId) {
            logger.warn { "setMute: callId mismatch (expected=${state.callId}, got=$callId)" }
            return
        }
        if (state.isMuted == muted) return
        val call = currentCall ?: return
        try {
            applyMuteState(call, muted)
            _callState.value = state.copy(isMuted = muted)
        } catch (e: Exception) {
            logger.error(e) { "setMute failed" }
        }
    }

    suspend fun setHold(callId: String, onHold: Boolean) {
        val state = _callState.value
        if (state !is CallState.Active) return
        if (state.callId != callId) {
            logger.warn { "setHold: callId mismatch (expected=${state.callId}, got=$callId)" }
            return
        }
        if (state.isOnHold == onHold) return
        if (holdInProgress) {
            logger.warn { "Hold/resume operation already in progress, ignoring" }
            return
        }
        val call = currentCall ?: return
        holdInProgress = true
        try {
            applyHoldState(call, hold = onHold, state = state)
        } catch (e: Exception) {
            holdInProgress = false
            logger.error(e) { "setHold failed" }
        }
    }

    fun onCallConfirmed(call: PjsipCall) {
        if (call !== currentCall) return
        val state = _callState.value
        if (state is CallState.Ending) {
            logger.warn { "onCallConfirmed ignored — call already in Ending state" }
            return
        }
        if (state is CallState.Ringing) {
            _callState.value = CallState.Active(
                callId = state.callId,
                remoteNumber = state.callerNumber,
                remoteName = state.callerName,
                isOutbound = state.isOutbound,
                isMuted = false,
                isOnHold = false,
                accountId = state.accountId,
                remoteUri = state.remoteUri,
            )
        }
    }

    fun onCallDisconnected(call: PjsipCall) {
        // Only reset state if this is our active call — rejected calls (486 Busy)
        // also fire onCallDisconnected and must not clobber the active call state.
        if (call !== currentCall) {
            try {
                call.safeDelete()
            } catch (e: Exception) {
                logger.warn(e) { "Error deleting non-current call object" }
            }
            return
        }
        hangupTimeoutJob?.cancel()
        hangupTimeoutJob = null
        resetCallState()
        try {
            call.safeDelete()
        } catch (e: Exception) {
            logger.warn(e) { "Error deleting call object" }
        }
    }

    override fun onIncomingCall(accountId: String, callId: Int) {
        val acc = accountProvider.getAccount(accountId) ?: run {
            logger.warn { "Incoming call on unknown account $accountId — ignoring" }
            return
        }
        if (currentCall != null) {
            logger.warn { "Rejecting incoming call on $accountId (already in call)" }
            val rejectCall = PjsipCall(this, acc, callId)
            try {
                withCallOpParam(statusCode = SipConstants.STATUS_BUSY_HERE) { prm -> rejectCall.hangup(prm) }
            } catch (e: Exception) {
                logger.error(e) { "Failed to reject incoming call" }
            } finally {
                rejectCall.safeDelete()
            }
            return
        }
        try {
            val call = PjsipCall(this, acc, callId)
            currentCall = call
            currentCallId = UUID.randomUUID().toString()
            currentAccountId = accountId
            val info = call.getInfo()
            try {
                // Snapshot all SWIG fields to Kotlin types before any escape
                val remoteUri = info.remoteUri
                val remoteContact = info.remoteContact
                val localUri = info.localUri
                val localContact = info.localContact
                val callIdString = info.callIdString
                val stateText = info.stateText
                val lastStatusCode = info.lastStatusCode
                val lastReason = info.lastReason
                val role = info.role
                val mediaCount = info.media?.size ?: 0

                logger.debug {
                    "Incoming call detail: account=$accountId pjCallId=$callId " +
                        "sipCallId=$callIdString remote=$remoteUri local=$localUri media=$mediaCount"
                }

                val callerInfo = parseRemoteUri(remoteUri)
                _callState.value = CallState.Ringing(
                    callId = currentCallId!!,
                    callerNumber = callerInfo.number,
                    callerName = callerInfo.displayName,
                    isOutbound = false,
                    accountId = accountId,
                    remoteUri = remoteUri,
                )
                logger.info {
                    "Incoming call on $accountId from: ${callerInfo.displayName ?: callerInfo.number}"
                }
            } finally {
                info.delete()
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling incoming call on $accountId" }
            resetCallState()
        }
    }

    suspend fun sendDtmf(callId: String, digits: String): Result<Unit> {
        if (!digits.matches(Regex("[0-9*#A-Da-d]+"))) {
            return Result.failure(IllegalArgumentException("Invalid DTMF digits: $digits"))
        }
        val call = currentCall ?: return Result.failure(IllegalStateException("No active call"))
        if (currentCallId != callId) {
            logger.warn { "sendDtmf: callId mismatch (expected=$currentCallId, got=$callId)" }
            return Result.failure(IllegalStateException("callId mismatch"))
        }
        return try {
            call.dialDtmf(digits)
            logger.info { "DTMF sent: $digits" }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "DTMF failed" }
            Result.failure(e)
        }
    }

    suspend fun transferCall(callId: String, destination: String): Result<Unit> {
        val call = currentCall ?: return Result.failure(IllegalStateException("No active call"))
        if (currentCallId != callId) {
            logger.warn { "transferCall: callId mismatch (expected=$currentCallId, got=$callId)" }
            return Result.failure(IllegalStateException("callId mismatch"))
        }
        return try {
            val callAccountId = currentAccountId
                ?: return Result.failure(IllegalStateException("No active call account"))
            val host = accountProvider.getAccount(callAccountId)?.server
                ?: return Result.failure(IllegalStateException("No server for account $callAccountId"))
            val destUri = SipConstants.buildCallUri(destination, host)
            withCallOpParam { prm -> call.xfer(destUri, prm) }
            logger.info { "Call transferred to: $destination" }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Transfer failed" }
            Result.failure(e)
        }
    }

    fun connectCallAudio(call: PjsipCall) {
        if (call !== currentCall) return
        // Reset holdInProgress here — media state callback means re-INVITE completed
        holdInProgress = false
        holdTimeoutJob?.cancel()
        holdTimeoutJob = null

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
                    val isMuted = (_callState.value as? CallState.Active)?.isMuted == true
                    if (!isMuted) {
                        captureMedia.startTransmit(audioMedia)
                    }
                    logger.info { "Audio media connected for media index $i (muted=$isMuted)" }

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
        scope.cancel()
        currentCall?.let { call ->
            try {
                withCallOpParam { prm -> call.hangup(prm) }
            } catch (_: Exception) {}
            try { call.safeDelete() } catch (_: Exception) {}
        }
        currentCall = null
        currentCallId = null
        currentAccountId = null
        _callState.value = CallState.Idle
    }

    private fun resetCallState() {
        currentCall = null
        currentCallId = null
        currentAccountId = null
        _callState.value = CallState.Idle
    }

    companion object {
        private const val HOLD_TIMEOUT_MS = 15_000L
        private const val HANGUP_TIMEOUT_MS = 10_000L
    }
}
