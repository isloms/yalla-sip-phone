package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallInfo
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.pjsip_inv_state

private val logger = KotlinLogging.logger {}

/**
 * PJSIP Call wrapper. All callbacks run synchronously on the pjsip-event-loop thread
 * during libHandleEvents(). Async dispatch (pjScope.launch) is NOT used because PJSIP
 * invalidates call/media objects after the callback returns.
 */
class PjsipCall : Call {

    private val callManager: PjsipCallManager
    private val deleted = AtomicBoolean(false)

    constructor(callManager: PjsipCallManager, account: Account) : super(account) {
        this.callManager = callManager
    }

    constructor(callManager: PjsipCallManager, account: Account, callId: Int) : super(account, callId) {
        this.callManager = callManager
    }

    override fun onCallState(prm: OnCallStateParam) {
        if (callManager.isCallManagerDestroyed()) return
        var info: CallInfo? = null
        try {
            info = getInfo()
            val stateText = info.stateText
            val lastStatusCode = info.lastStatusCode
            val state = info.state
            logger.info { "Call state: $stateText ($lastStatusCode)" }
            when (state) {
                pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> callManager.onCallConfirmed(this)
                pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> callManager.onCallDisconnected(this)
                else -> {}
            }
        } catch (e: Exception) {
            logger.error(e) { "Error processing onCallState" }
        } finally {
            info?.delete()
        }
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam) {
        if (callManager.isCallManagerDestroyed()) return
        try {
            callManager.connectCallAudio(this)
        } catch (e: Exception) {
            logger.error(e) { "Error in onCallMediaState callback" }
        }
    }

    fun safeDelete() {
        if (!deleted.compareAndSet(false, true)) return
        try {
            delete()
        } catch (e: Exception) {
            logger.warn(e) { "Error during call delete" }
        }
    }
}
