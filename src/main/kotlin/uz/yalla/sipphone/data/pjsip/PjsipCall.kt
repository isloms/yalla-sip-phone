package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallInfo
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsua_call_media_status

private val logger = KotlinLogging.logger {}

/**
 * For outbound calls: PjsipCall(bridge, account)
 * For incoming calls: PjsipCall(bridge, account, callId)
 */
class PjsipCall : Call {

    private val bridge: PjsipBridge

    constructor(bridge: PjsipBridge, account: Account) : super(account) {
        this.bridge = bridge
    }

    constructor(bridge: PjsipBridge, account: Account, callId: Int) : super(account, callId) {
        this.bridge = bridge
    }

    override fun onCallState(prm: OnCallStateParam) {
        if (bridge.isDestroyed()) return
        var info: CallInfo? = null
        try {
            info = getInfo()
            logger.info { "Call state: ${info.stateText} (${info.lastStatusCode})" }
            when (info.state) {
                pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> bridge.onCallConfirmed(this)
                pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> bridge.onCallDisconnected(this)
                else -> {}
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in onCallState callback" }
        } finally {
            info?.delete()
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        if (bridge.isDestroyed()) return
        var info: CallInfo? = null
        try {
            info = getInfo()
            val mediaCount = info.media.size
            for (i in 0 until mediaCount) {
                val mediaInfo = info.media[i]
                if (mediaInfo.type == org.pjsip.pjsua2.pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    mediaInfo.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                ) {
                    val audioMedia = getAudioMedia(i)
                    val playbackMedia = bridge.getPlaybackDevMedia()
                    val captureMedia = bridge.getCaptureDevMedia()
                    audioMedia.startTransmit(playbackMedia)
                    captureMedia.startTransmit(audioMedia)
                    logger.info { "Audio media connected for media index $i" }

                    // RTP stream diagnostics
                    try {
                        val si = getStreamInfo(i.toLong())
                        logger.info { "Stream: codec=${si.codecName}/${si.codecClockRate}Hz, " +
                            "dir=${si.dir}, remote=${si.remoteRtpAddress}" }
                        si.delete()
                    } catch (e: Exception) {
                        logger.warn(e) { "Could not get stream info" }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in onCallMediaState callback" }
        } finally {
            info?.delete()
        }
    }
}
