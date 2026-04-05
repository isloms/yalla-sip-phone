package uz.yalla.sipphone.feature.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.yalla.sipphone.data.jcef.BridgeEventEmitter
import uz.yalla.sipphone.data.jcef.BridgeRouter
import uz.yalla.sipphone.data.jcef.BridgeSecurity
import uz.yalla.sipphone.data.jcef.BridgeAuditLog
import uz.yalla.sipphone.data.jcef.JcefManager
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.feature.main.toolbar.ToolbarComponent

private val logger = KotlinLogging.logger {}

class MainComponent(
    componentContext: ComponentContext,
    val authResult: AuthResult,
    callEngine: CallEngine,
    registrationEngine: RegistrationEngine,
    val jcefManager: JcefManager,
    private val eventEmitter: BridgeEventEmitter,
    private val onLogout: () -> Unit,
) : ComponentContext by componentContext {

    val toolbar = ToolbarComponent(
        callEngine = callEngine,
        registrationEngine = registrationEngine,
    )

    val dispatcherUrl: String = authResult.dispatcherUrl
    val agentInfo: AgentInfo = authResult.agent

    private val scope = coroutineScope()

    init {
        // Set up JS Bridge (only if JCEF is initialized — skipped in tests)
        if (jcefManager.isInitialized) {
            eventEmitter.agentInfo = authResult.agent

            val security = BridgeSecurity()
            val auditLog = BridgeAuditLog()
            val bridgeRouter = BridgeRouter(
                callEngine = callEngine,
                registrationEngine = registrationEngine,
                security = security,
                auditLog = auditLog,
                onAgentStatusChange = { status: AgentStatus ->
                    logger.info { "Agent status change requested from bridge: $status" }
                },
                onReady = eventEmitter::completeHandshake,
            )

            jcefManager.setupBridge(
                installMessageRouter = bridgeRouter::install,
                onPageLoadEnd = eventEmitter::injectBridgeScript,
                onPageLoadStart = eventEmitter::resetHandshake,
            )
        }

        // Auto-logout on disconnect when no active call
        scope.launch(Dispatchers.Main) {
            combine(
                registrationEngine.registrationState,
                callEngine.callState,
            ) { regState, callState ->
                val isDisconnected = regState is RegistrationState.Idle || regState is RegistrationState.Failed
                val noActiveCall = callState is CallState.Idle
                isDisconnected && noActiveCall
            }
                .drop(1) // skip initial emission
                .first { it }
            onLogout()
        }
    }

    fun logout() {
        toolbar.disconnect()
    }
}
