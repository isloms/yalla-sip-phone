package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallInfo
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsua_call_media_status

private val logger = KotlinLogging.logger {}

class PjsipCall(private val bridge: PjsipBridge) : Call() {

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
            for (i in 0 until info.media.size().toInt()) {
                val mediaInfo = info.media[i]
                if (mediaInfo.type == org.pjsip.pjsua2.pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    mediaInfo.status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE
                ) {
                    val audioMedia = getAudioMedia(i)
                    val playbackMedia = bridge.getPlaybackDevMedia()
                    audioMedia.startTransmit(playbackMedia)
                    playbackMedia.delete()
                    val captureMedia = bridge.getCaptureDevMedia()
                    captureMedia.startTransmit(audioMedia)
                    captureMedia.delete()
                    logger.info { "Audio media connected for media index $i" }
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
