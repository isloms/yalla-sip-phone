package uz.yalla.sipphone.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface Screen {
    @Serializable data class Login(val sessionId: Int = 0) : Screen
    @Serializable data object Main : Screen
}
