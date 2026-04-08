package uz.yalla.sipphone.data.auth.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginResultDto(
    val token: String,
    @SerialName("token_type") val tokenType: String,
    val expire: Long,
)
