package uz.yalla.sipphone.data.auth.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestDto(
    @SerialName("pin_code") val pinCode: String,
)
