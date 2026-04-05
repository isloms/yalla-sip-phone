package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.feature.login.LoginComponent
import uz.yalla.sipphone.feature.main.MainComponent

interface ComponentFactory {
    fun createLogin(context: ComponentContext, onLoginSuccess: (AuthResult) -> Unit): LoginComponent
    fun createMain(context: ComponentContext, authResult: AuthResult, onLogout: () -> Unit): MainComponent
}
