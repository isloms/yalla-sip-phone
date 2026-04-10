package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

class PjsipAccount(
    val accountId: String,
    val server: String,
    private val accountManager: PjsipAccountManager,
    private val pjScope: CoroutineScope,
) : Account() {

    override fun onRegState(prm: OnRegStateParam) {
        if (accountManager.isAccountDestroyed()) return
        // Capture SWIG pointer values BEFORE dispatching — they become invalid after callback returns
        var info: org.pjsip.pjsua2.AccountInfo? = null
        try {
            info = getInfo()
            val code = prm.code
            val reason = prm.reason
            val regIsActive = info.regIsActive
            val uri = info.uri
            val regExpiresSec = info.regExpiresSec
            val regLastErr = info.regLastErr
            pjScope.launch {
                try {
                    when {
                        code / 100 == SipConstants.STATUS_CLASS_SUCCESS && regIsActive -> {
                            accountManager.updateRegistrationState(
                                accountId,
                                PjsipRegistrationState.Registered(uri = uri),
                            )
                            logger.info { "[$accountId] Registered: $uri, expires: ${regExpiresSec}s" }
                        }
                        code / 100 == SipConstants.STATUS_CLASS_SUCCESS && !regIsActive -> {
                            accountManager.updateRegistrationState(accountId, PjsipRegistrationState.Idle)
                            logger.info { "[$accountId] Unregistered" }
                        }
                        else -> {
                            val error = SipError.fromSipStatus(code, reason)
                            accountManager.updateRegistrationState(
                                accountId,
                                PjsipRegistrationState.Failed(error = error),
                            )
                            logger.warn {
                                "[$accountId] Registration failed: $code $reason (lastErr=$regLastErr)"
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[$accountId] Error processing onRegState" }
                    accountManager.updateRegistrationState(
                        accountId,
                        PjsipRegistrationState.Failed(error = SipError.fromException(e)),
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "[$accountId] Error capturing onRegState data" }
            accountManager.updateRegistrationState(
                accountId,
                PjsipRegistrationState.Failed(error = SipError.fromException(e)),
            )
        } finally {
            info?.delete()
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        if (accountManager.isAccountDestroyed()) return
        // Capture callId from SWIG pointer BEFORE dispatching
        val callId = prm.callId
        pjScope.launch {
            try {
                accountManager.handleIncomingCall(accountId, callId)
            } catch (e: Exception) {
                logger.error(e) { "[$accountId] Error in onIncomingCall callback" }
            }
        }
    }
}
