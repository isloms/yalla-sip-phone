package uz.yalla.sipphone.data.settings

import com.russhwolf.settings.Settings
import uz.yalla.sipphone.domain.SipCredentials

class AppSettings {

    private val settings = Settings()

    fun saveCredentials(credentials: SipCredentials) {
        settings.putString("sip_server", credentials.server)
        settings.putInt("sip_port", credentials.port)
        settings.putString("sip_username", credentials.username)
    }

    fun loadCredentials(): SipCredentials? {
        val server = settings.getStringOrNull("sip_server") ?: return null
        val username = settings.getStringOrNull("sip_username") ?: return null
        return SipCredentials(
            server = server,
            port = settings.getInt("sip_port", SipCredentials.DEFAULT_SIP_PORT),
            username = username,
            password = "",
        )
    }
}
