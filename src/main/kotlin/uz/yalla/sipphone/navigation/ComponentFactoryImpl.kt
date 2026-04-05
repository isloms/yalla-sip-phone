package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import org.koin.core.Koin
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.data.jcef.BridgeAuditLog
import uz.yalla.sipphone.data.jcef.BridgeEventEmitter
import uz.yalla.sipphone.data.jcef.BridgeSecurity
import uz.yalla.sipphone.data.jcef.JcefManager
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.feature.login.LoginComponent
import uz.yalla.sipphone.feature.main.MainComponent

class ComponentFactoryImpl(private val koin: Koin) : ComponentFactory {

    override fun createLogin(
        context: ComponentContext,
        onLoginSuccess: (AuthResult) -> Unit,
    ): LoginComponent = LoginComponent(
        componentContext = context,
        authRepository = koin.get<AuthRepository>(),
        registrationEngine = koin.get<RegistrationEngine>(),
        onLoginSuccess = onLoginSuccess,
    )

    override fun createMain(
        context: ComponentContext,
        authResult: AuthResult,
        onLogout: () -> Unit,
    ): MainComponent = MainComponent(
        componentContext = context,
        authResult = authResult,
        callEngine = koin.get<CallEngine>(),
        registrationEngine = koin.get<RegistrationEngine>(),
        jcefManager = koin.get<JcefManager>(),
        eventEmitter = koin.get<BridgeEventEmitter>(),
        security = koin.get<BridgeSecurity>(),
        auditLog = koin.get<BridgeAuditLog>(),
        onLogout = onLogout,
    )
}
