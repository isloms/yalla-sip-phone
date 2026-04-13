package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.data.jcef.BridgeAuditLog
import uz.yalla.sipphone.data.jcef.BridgeEventEmitter
import uz.yalla.sipphone.data.jcef.BridgeSecurity
import uz.yalla.sipphone.data.jcef.JcefManager
import uz.yalla.sipphone.data.update.UpdateManager
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.feature.login.LoginComponent
import uz.yalla.sipphone.feature.main.MainComponent

class ComponentFactoryImpl(
    private val authRepository: AuthRepository,
    private val sipAccountManager: SipAccountManager,
    private val callEngine: CallEngine,
    private val jcefManager: JcefManager,
    private val eventEmitter: BridgeEventEmitter,
    private val security: BridgeSecurity,
    private val auditLog: BridgeAuditLog,
    private val updateManager: UpdateManager,
) : ComponentFactory {

    override fun createLogin(
        context: ComponentContext,
        onLoginSuccess: (AuthResult) -> Unit,
    ): LoginComponent = LoginComponent(
        componentContext = context,
        authRepository = authRepository,
        sipAccountManager = sipAccountManager,
        onLoginSuccess = onLoginSuccess,
    )

    override fun createMain(
        context: ComponentContext,
        authResult: AuthResult,
        onLogout: () -> Unit,
    ): MainComponent = MainComponent(
        componentContext = context,
        authResult = authResult,
        callEngine = callEngine,
        sipAccountManager = sipAccountManager,
        jcefManager = jcefManager,
        eventEmitter = eventEmitter,
        security = security,
        auditLog = auditLog,
        updateManager = updateManager,
        onLogout = onLogout,
    )
}
