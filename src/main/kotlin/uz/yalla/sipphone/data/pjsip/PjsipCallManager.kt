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

/**
 * Manages the pjsua2 call lifecycle: outbound dialling, inbound acceptance, audio wiring,
 * mute/hold state, DTMF, and blind transfer.
 *
 * All public methods must be called on [pjDispatcher] (the pjsip event-loop thread).
 * State is exposed via [callState] and can be collected from any thread.
 *
 * Supports multi-account call routing: [makeCall] accepts an [accountId] to select which
 * SIP line to dial from. Incoming calls carry the [accountId] of the receiving account.
 *
 * SWIG lifecycle: call [destroy] before [PjsipEndpointManager.destroy]. The manager hangs up
 * any active call and deletes the underlying [PjsipCall] SWIG object during [destroy].
 */
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
    private var holdInProgress = false
    private var holdTimeoutJob: Job? = null
    private var hangupTimeoutJob: Job? = null

    fun isCallManagerDestroyed(): Boolean = isDestroyed()

    private inline fun <R> withCallOpParam(
        statusCode: Int = 200,
        block: (CallOpParam) -> R,
    ): R {
        val prm = CallOpParam()
        prm.statusCode = statusCode
        return try {
            block(prm)
        } finally {
            prm.delete()
        }
    }

    /**
     * Applies mute/unmute by controlling capture media transmission to the call's audio media.
     * CRITICAL: Do NOT call delete() on captureDevMedia — it is owned by the audio device manager.
     */
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

    /**
     * Applies hold/resume via setHold/reinvite, updates state, and launches a safety timeout.
     */
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
        // pjsip call sent successfully — release the guard immediately.
        // connectCallAudio() handles the actual media reconnection separately.
        holdInProgress = false
        holdTimeoutJob?.cancel()
        holdTimeoutJob = null
    }

    /**
     * Initiates an outbound call to [number] using the account identified by [accountId].
     *
     * If [accountId] is empty, the first connected account is used (backward compat).
     */
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
                delay(10_000)
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
            )
        }
    }

    fun onCallDisconnected(call: PjsipCall) {
        hangupTimeoutJob?.cancel()
        hangupTimeoutJob = null
        resetCallState()
        try {
            call.safeDelete()
        } catch (e: Exception) {
            logger.warn(e) { "Error deleting call object" }
        }
    }

    /**
     * Handles an incoming call on the account identified by [accountId].
     */
    override fun onIncomingCall(accountId: String, callId: Int) {
        val acc = accountProvider.getAccount(accountId) ?: run {
            logger.warn { "Incoming call on unknown account $accountId — ignoring" }
            return
        }
        if (currentCall != null) {
            logger.warn { "Rejecting incoming call on $accountId (already in call)" }
            try {
                val call = PjsipCall(this, acc, callId)
                withCallOpParam(statusCode = SipConstants.STATUS_BUSY_HERE) { prm -> call.hangup(prm) }
                call.safeDelete()
            } catch (e: Exception) {
                logger.error(e) { "Failed to reject incoming call" }
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
                val callerInfo = parseRemoteUri(info.remoteUri)
                _callState.value = CallState.Ringing(
                    callId = currentCallId!!,
                    callerNumber = callerInfo.number,
                    callerName = callerInfo.displayName,
                    isOutbound = false,
                    accountId = accountId,
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
        val call = currentCall ?: return Result.failure(IllegalStateException("No active call"))
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
        return try {
            val host = SipConstants.extractHostFromUri(accountProvider.lastRegisteredServer)
            if (host.isBlank()) return Result.failure(IllegalStateException("No server address"))
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
}
