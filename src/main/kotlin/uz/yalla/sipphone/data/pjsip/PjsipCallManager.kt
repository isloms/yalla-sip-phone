package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : IncomingCallListener {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private var currentCall: PjsipCall? = null
    private var currentCallId: String? = null
    private var holdInProgress = false

    fun isCallManagerDestroyed(): Boolean = isDestroyed()

    suspend fun makeCall(number: String): Result<Unit> {
        if (currentCall != null) return Result.failure(IllegalStateException("Call already active"))
        val acc = accountProvider.currentAccount
            ?: return Result.failure(IllegalStateException("Not registered"))
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
            _callState.value = CallState.Ringing(
                callId = currentCallId!!,
                callerNumber = number,
                callerName = null,
                isOutbound = true,
            )
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "makeCall failed" }
            _callState.value = CallState.Idle
            return Result.failure(e)
        }
    }

    suspend fun answerCall() {
        val call = currentCall ?: return
        val ringing = _callState.value as? CallState.Ringing ?: return
        if (ringing.isOutbound) return
        try {
            val prm = CallOpParam()
            try {
                prm.statusCode = SipConstants.STATUS_OK
                call.answer(prm)
            } finally {
                prm.delete()
            }
        } catch (e: Exception) {
            logger.error(e) { "answerCall failed" }
        }
    }

    suspend fun hangupCall() {
        val call = currentCall ?: return
        try {
            _callState.value = CallState.Ending
            val prm = CallOpParam()
            try {
                call.hangup(prm)
            } finally {
                prm.delete()
            }
        } catch (e: Exception) {
            logger.error(e) { "hangupCall failed" }
            resetCallState()
        }
    }

    /**
     * Mute fix: uses stopTransmit/startTransmit on capture media instead of adjustRxLevel.
     * The adjustRxLevel approach was unreliable. startTransmit/stopTransmit directly controls
     * whether our microphone audio reaches the remote call media.
     *
     * CRITICAL: Do NOT call delete() on captureDevMedia — it is owned by the audio device manager.
     */
    suspend fun toggleMute() {
        val state = _callState.value
        if (state !is CallState.Active) return
        val call = currentCall ?: return
        try {
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
                        if (state.isMuted) {
                            captureMedia.startTransmit(audioMedia)
                        } else {
                            captureMedia.stopTransmit(audioMedia)
                        }
                        break
                    }
                }
            } finally {
                callInfo.delete()
            }
            _callState.value = state.copy(isMuted = !state.isMuted)
        } catch (e: Exception) {
            logger.error(e) { "toggleMute failed" }
        }
    }

    /**
     * Hold fix: holdInProgress guard prevents PJ_EINVALIDOP when a re-INVITE is still in-flight.
     */
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
            val prm = CallOpParam()
            try {
                if (state.isOnHold) {
                    prm.opt.flag = 0
                    call.reinvite(prm)
                } else {
                    call.setHold(prm)
                }
            } finally {
                prm.delete()
            }
            _callState.value = state.copy(isOnHold = !state.isOnHold)
        } catch (e: Exception) {
            logger.error(e) { "toggleHold failed" }
        } finally {
            holdInProgress = false
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
            val prm = CallOpParam()
            try {
                if (onHold) {
                    call.setHold(prm)
                } else {
                    prm.opt.flag = 0
                    call.reinvite(prm)
                }
            } finally {
                prm.delete()
            }
            _callState.value = state.copy(isOnHold = onHold)
        } catch (e: Exception) {
            logger.error(e) { "setHold failed" }
        } finally {
            holdInProgress = false
        }
    }

    fun onCallConfirmed(call: PjsipCall) {
        val state = _callState.value
        if (state is CallState.Ringing) {
            _callState.value = CallState.Active(
                callId = state.callId,
                remoteNumber = state.callerNumber,
                remoteName = state.callerName,
                isOutbound = state.isOutbound,
                isMuted = false,
                isOnHold = false,
            )
        }
    }

    fun onCallDisconnected(call: PjsipCall) {
        resetCallState()
        try {
            call.delete()
        } catch (e: Exception) {
            logger.warn(e) { "Error deleting call object" }
        }
    }

    override fun onIncomingCall(callId: Int) {
        val acc = accountProvider.currentAccount ?: return
        if (currentCall != null) {
            logger.warn { "Rejecting incoming call (already in call)" }
            try {
                val call = PjsipCall(this, acc, callId)
                val prm = CallOpParam()
                try {
                    prm.statusCode = SipConstants.STATUS_BUSY_HERE
                    call.hangup(prm)
                } finally {
                    prm.delete()
                }
                call.delete()
            } catch (e: Exception) {
                logger.error(e) { "Failed to reject incoming call" }
            }
            return
        }
        try {
            val call = PjsipCall(this, acc, callId)
            currentCall = call
            currentCallId = UUID.randomUUID().toString()
            val info = call.getInfo()
            try {
                val callerInfo = parseRemoteUri(info.remoteUri)
                _callState.value = CallState.Ringing(
                    callId = currentCallId!!,
                    callerNumber = callerInfo.number,
                    callerName = callerInfo.displayName,
                    isOutbound = false,
                )
                logger.info { "Incoming call from: ${callerInfo.displayName ?: callerInfo.number}" }
            } finally {
                info.delete()
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling incoming call" }
            resetCallState()
        }
    }

    fun connectCallAudio(call: PjsipCall) {
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
        currentCall?.let { call ->
            try {
                val prm = CallOpParam()
                try {
                    call.hangup(prm)
                } finally {
                    prm.delete()
                }
            } catch (_: Exception) {}
            try { call.delete() } catch (_: Exception) {}
        }
        currentCall = null
        currentCallId = null
        _callState.value = CallState.Idle
    }

    private fun resetCallState() {
        currentCall = null
        currentCallId = null
        _callState.value = CallState.Idle
    }
}
