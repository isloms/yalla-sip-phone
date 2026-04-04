package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

class PjsipAccount(private val bridge: PjsipBridge) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        if (bridge.isDestroyed()) return
        var info: org.pjsip.pjsua2.AccountInfo? = null
        try {
            info = getInfo()
            val code = prm.code

            when {
                code / 100 == 2 && info.regIsActive -> {
                    bridge.updateRegistrationState(RegistrationState.Registered(server = info.uri))
                    logger.info { "Registered: ${info.uri}, expires: ${info.regExpiresSec}s" }
                }
                code / 100 == 2 && !info.regIsActive -> {
                    bridge.updateRegistrationState(RegistrationState.Idle)
                    logger.info { "Unregistered" }
                }
                else -> {
                    val reason = prm.reason
                    bridge.updateRegistrationState(RegistrationState.Failed(SipError.fromSipStatus(prm.code, reason)))
                    logger.warn { "Registration failed: ${prm.code} $reason (lastErr=${info.regLastErr})" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error in onRegState callback" }
            bridge.updateRegistrationState(RegistrationState.Failed(SipError.fromException(e)))
        } finally {
            info?.delete()
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        if (bridge.isDestroyed()) return
        try {
            bridge.onIncomingCall(prm.callId)
        } catch (e: Exception) {
            logger.error(e) { "Error in onIncomingCall callback" }
        }
    }
}
