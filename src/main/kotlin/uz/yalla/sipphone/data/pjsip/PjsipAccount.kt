package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.OnRegStateParam
import uz.yalla.sipphone.domain.RegistrationState

private val logger = KotlinLogging.logger {}

class PjsipAccount(private val bridge: PjsipBridge) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        if (bridge.isDestroyed()) return
        try {
            val info = getInfo()
            val code = prm.code

            when {
                code / 100 == 2 && info.regIsActive -> {
                    bridge.updateRegistrationState(
                        RegistrationState.Registered(server = info.uri)
                    )
                    logger.info { "Registered: ${info.uri}, expires: ${info.regExpiresSec}s" }
                }
                code / 100 == 2 && !info.regIsActive -> {
                    // Successful unregistration (REGISTER Expires:0 got 200 OK)
                    bridge.updateRegistrationState(RegistrationState.Idle)
                    logger.info { "Unregistered" }
                }
                else -> {
                    val reason = "${prm.code} ${prm.reason}"
                    bridge.updateRegistrationState(
                        RegistrationState.Failed(message = reason)
                    )
                    logger.warn { "Registration failed: $reason (lastErr=${info.regLastErr})" }
                }
            }

            info.delete() // SWIG cleanup
        } catch (e: Exception) {
            logger.error(e) { "Error in onRegState callback" }
            bridge.updateRegistrationState(
                RegistrationState.Failed(message = "Internal error: ${e.message}")
            )
        }
    }

    // Phase 3: override onIncomingCall(prm: OnIncomingCallParam)
}
