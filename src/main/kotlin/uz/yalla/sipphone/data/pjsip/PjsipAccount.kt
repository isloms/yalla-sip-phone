package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnRegStateParam
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

/**
 * PJSIP Account wrapper. All callbacks run synchronously on the pjsip-event-loop thread.
 * SWIG pointer values are captured before any processing since they become invalid after
 * the callback returns.
 */
class PjsipAccount(
    val accountId: String,
    val server: String,
    private val accountManager: PjsipAccountManager,
) : Account() {

    private val deleted = AtomicBoolean(false)

    override fun onRegState(prm: OnRegStateParam) {
        if (accountManager.isAccountDestroyed()) return
        var info: org.pjsip.pjsua2.AccountInfo? = null
        try {
            info = getInfo()
            val code = prm.code
            val reason = prm.reason
            val regIsActive = info.regIsActive
            val uri = info.uri
            val regExpiresSec = info.regExpiresSec
            val regLastErr = info.regLastErr
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
        } finally {
            info?.delete()
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam) {
        if (accountManager.isAccountDestroyed()) return
        val callId = prm.callId
        try {
            accountManager.handleIncomingCall(accountId, callId)
        } catch (e: Exception) {
            logger.error(e) { "[$accountId] Error in onIncomingCall callback" }
        }
    }

    fun safeDelete() {
        if (!deleted.compareAndSet(false, true)) return
        try {
            delete()
        } catch (e: Exception) {
            logger.warn(e) { "[$accountId] Error during account delete" }
        }
    }
}
