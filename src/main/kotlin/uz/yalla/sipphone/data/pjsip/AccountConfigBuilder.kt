package uz.yalla.sipphone.data.pjsip

import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.pjsua_stun_use
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipCredentials

object AccountConfigBuilder {
    fun build(credentials: SipCredentials): AccountConfig {
        val config = AccountConfig()
        AuthCredInfo(
            SipConstants.AUTH_SCHEME_DIGEST,
            SipConstants.AUTH_REALM_ANY,
            credentials.username,
            SipConstants.AUTH_DATA_TYPE_PLAINTEXT,
            credentials.password,
        ).use { authCred ->
            config.idUri = SipConstants.buildUserUri(credentials.username, credentials.server)
            config.regConfig.registrarUri = SipConstants.buildRegistrarUri(credentials.server, credentials.port)
            config.regConfig.retryIntervalSec = 0
            config.sipConfig.authCreds.add(authCred)
            config.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
            config.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
        }
        return config
    }
}
