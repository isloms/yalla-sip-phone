package uz.yalla.sipphone.feature.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.arkivanov.essenty.lifecycle.doOnDestroy
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
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.feature.main.toolbar.ToolbarComponent

class MainComponent(
    componentContext: ComponentContext,
    val authResult: AuthResult,
    private val callEngine: CallEngine,
    private val registrationEngine: RegistrationEngine,
    val jcefManager: JcefManager,
    private val eventEmitter: BridgeEventEmitter,
    private val security: BridgeSecurity,
    private val auditLog: BridgeAuditLog,
    private val onLogout: () -> Unit,
) : ComponentContext by componentContext {

    val toolbar = ToolbarComponent(
        callEngine = callEngine,
        registrationEngine = registrationEngine,
    )

    val dispatcherUrl: String = if (authResult.token.isNotEmpty())
        "${authResult.dispatcherUrl}?token=${authResult.token}"
    else
        authResult.dispatcherUrl
    val agentInfo: AgentInfo = authResult.agent

    private val scope = coroutineScope()
    private var bridgeRouter: BridgeRouter? = null

    init {
        lifecycle.doOnDestroy {
            toolbar.destroy()
            bridgeRouter?.dispose()
        }

        // Set up JS Bridge (only if JCEF is initialized — skipped in tests)
        if (jcefManager.isInitialized) {
            eventEmitter.agentInfo = authResult.agent

            val bridgeRouter = BridgeRouter(
                callEngine = callEngine,
                registrationEngine = registrationEngine,
                security = security,
                auditLog = auditLog,
                agentStatusProvider = { toolbar.agentStatus.value },
                onAgentStatusChange = { toolbar.setAgentStatus(it) },
                onReady = eventEmitter::completeHandshake,
                onRequestLogout = { onLogout() },
            )
            this.bridgeRouter = bridgeRouter

            jcefManager.setupBridge(
                installMessageRouter = bridgeRouter::install,
                onPageLoadEnd = eventEmitter::injectBridgeScript,
                onPageLoadStart = eventEmitter::resetHandshake,
            )
        }

        // --- Call state observation → bridge events ---
        var previousCallState: CallState = CallState.Idle
        var callStartTimestamp: Long = 0L

        scope.launch {
            callEngine.callState.collect { newState ->
                val prev = previousCallState
                previousCallState = newState

                when {
                    // Idle → Ringing (inbound)
                    prev is CallState.Idle && newState is CallState.Ringing && !newState.isOutbound -> {
                        callStartTimestamp = System.currentTimeMillis()
                        eventEmitter.emitIncomingCall(newState.callId, newState.callerNumber)
                    }
                    // Idle → Ringing (outbound)
                    prev is CallState.Idle && newState is CallState.Ringing && newState.isOutbound -> {
                        callStartTimestamp = System.currentTimeMillis()
                        eventEmitter.emitOutgoingCall(newState.callId, newState.callerNumber)
                    }
                    // Ringing → Active (call connected)
                    prev is CallState.Ringing && newState is CallState.Active -> {
                        callStartTimestamp = System.currentTimeMillis()
                        val direction = if (newState.isOutbound) "outbound" else "inbound"
                        eventEmitter.emitCallConnected(newState.callId, newState.remoteNumber, direction)
                    }
                    // Active → Active (mute/hold changed)
                    prev is CallState.Active && newState is CallState.Active -> {
                        if (prev.isMuted != newState.isMuted) {
                            eventEmitter.emitCallMuteChanged(newState.callId, newState.isMuted)
                        }
                        if (prev.isOnHold != newState.isOnHold) {
                            eventEmitter.emitCallHoldChanged(newState.callId, newState.isOnHold)
                        }
                    }
                    // Any → Idle (call ended)
                    newState is CallState.Idle &&
                        (prev is CallState.Ringing || prev is CallState.Active || prev is CallState.Ending) -> {
                        val duration = ((System.currentTimeMillis() - callStartTimestamp) / 1000).toInt()
                        val callId: String
                        val number: String
                        val direction: String
                        val reason: String

                        when (prev) {
                            is CallState.Ringing -> {
                                callId = prev.callId
                                number = prev.callerNumber
                                direction = if (prev.isOutbound) "outbound" else "inbound"
                                reason = if (prev.isOutbound) "hangup" else "missed"
                            }
                            is CallState.Active -> {
                                callId = prev.callId
                                number = prev.remoteNumber
                                direction = if (prev.isOutbound) "outbound" else "inbound"
                                reason = "hangup"
                            }
                            else -> return@collect // Ending state doesn't carry call info
                        }

                        eventEmitter.emitCallEnded(callId, number, direction, duration, reason)
                        callStartTimestamp = 0L
                    }
                }
            }
        }

        // --- Registration state observation → bridge events ---
        var previousRegState: RegistrationState = registrationEngine.registrationState.value

        scope.launch {
            registrationEngine.registrationState.collect { newState ->
                val prev = previousRegState
                previousRegState = newState

                val state = when (newState) {
                    is RegistrationState.Registered -> "connected"
                    is RegistrationState.Registering -> "reconnecting"
                    else -> "disconnected"
                }
                val prevState = when (prev) {
                    is RegistrationState.Registered -> "connected"
                    is RegistrationState.Registering -> "reconnecting"
                    else -> "disconnected"
                }

                if (state != prevState) {
                    eventEmitter.emitConnectionChanged(state, 0)
                }
            }
        }

        // --- Agent status observation → bridge events ---
        var previousAgentStatus = toolbar.agentStatus.value

        scope.launch {
            toolbar.agentStatus.collect { newStatus ->
                val prev = previousAgentStatus
                previousAgentStatus = newStatus
                if (prev != newStatus) {
                    eventEmitter.emitAgentStatusChanged(
                        status = newStatus.name.lowercase(),
                        previousStatus = prev.name.lowercase(),
                    )
                }
            }
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

    fun onThemeChanged(isDark: Boolean) {
        eventEmitter.emitThemeChanged(if (isDark) "dark" else "light")
    }

    fun logout() {
        toolbar.disconnect()
    }
}
