package uz.yalla.sipphone.data.auth.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.SipCredentials

@Serializable
data class MeResultDto(
    val id: Int,
    @SerialName("tm_user_id") val tmUserId: Int,
    @SerialName("full_name") val fullName: String,
    val roles: String,
    @SerialName("created_at") val createdAt: String,
    val sips: List<SipConnectionDto>,
)

@Serializable
data class SipConnectionDto(
    @SerialName("extension_number") val extensionNumber: Int,
    val password: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("sip_name") val sipName: String,
    @SerialName("server_url") val serverUrl: String,
    @SerialName("server_port") val serverPort: Int,
    val domain: String,
    @SerialName("connection_type") val connectionType: String,
)

fun MeResultDto.toAuthResult(token: String, dispatcherUrl: String): AuthResult {
    val sip = sips.first { it.isActive }
    return AuthResult(
        token = token,
        sipCredentials = SipCredentials(
            server = sip.domain,
            port = sip.serverPort,
            username = sip.extensionNumber.toString(),
            password = sip.password,
            transport = sip.connectionType.uppercase(),
        ),
        dispatcherUrl = dispatcherUrl,
        agent = AgentInfo(id = id.toString(), name = fullName),
    )
}
