package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.login.LoginComponent
import uz.yalla.sipphone.feature.main.MainComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent

interface ComponentFactory {
    fun createRegistration(context: ComponentContext, onRegistered: () -> Unit): RegistrationComponent
    fun createDialer(context: ComponentContext, onDisconnected: () -> Unit): DialerComponent
    fun createLogin(context: ComponentContext, onLoginSuccess: (AuthResult) -> Unit): LoginComponent
    fun createMain(context: ComponentContext, authResult: AuthResult, onLogout: () -> Unit): MainComponent
}
