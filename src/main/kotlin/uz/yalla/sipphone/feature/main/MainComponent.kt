package uz.yalla.sipphone.feature.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.feature.main.toolbar.ToolbarComponent

class MainComponent(
    componentContext: ComponentContext,
    val authResult: AuthResult,
    callEngine: CallEngine,
    registrationEngine: RegistrationEngine,
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
