package uz.yalla.sipphone.feature.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.launch
import uz.yalla.sipphone.data.jcef.BridgeEventEmitter
import uz.yalla.sipphone.data.jcef.BridgeRouter
import uz.yalla.sipphone.data.jcef.BridgeSecurity
import uz.yalla.sipphone.data.jcef.BridgeAuditLog
import uz.yalla.sipphone.data.jcef.JcefManager
import uz.yalla.sipphone.data.update.UpdateManager
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.feature.main.toolbar.ToolbarComponent

class MainComponent(
    componentContext: ComponentContext,
    val authResult: AuthResult,
    private val callEngine: CallEngine,
    private val sipAccountManager: SipAccountManager,
    val jcefManager: JcefManager,
    private val eventEmitter: BridgeEventEmitter,
    private val security: BridgeSecurity,
    private val auditLog: BridgeAuditLog,
    val updateManager: UpdateManager,
    private val onLogout: () -> Unit,
) : ComponentContext by componentContext {

    private val scope = coroutineScope()

    val toolbar = ToolbarComponent(
        callEngine = callEngine,
        sipAccountManager = sipAccountManager,
        scope = scope,
    )

    val dispatcherUrl: String = if (authResult.token.isNotEmpty())
        "${authResult.dispatcherUrl}?token=${authResult.token}"
    else
        authResult.dispatcherUrl
    val agentInfo: AgentInfo = authResult.agent
    private var bridgeRouter: BridgeRouter? = null

    init {
        lifecycle.doOnDestroy {
            toolbar.releaseAudioResources()
            eventEmitter.detach()
            bridgeRouter?.dispose()
            jcefManager.teardownBridge()
        }

        // Set up JS Bridge (only if JCEF is initialized — skipped in tests)
        if (jcefManager.isInitialized) {
            eventEmitter.agentInfo = authResult.agent

            val bridgeRouter = BridgeRouter(
                callEngine = callEngine,
                sipAccountManager = sipAccountManager,
                security = security,
                auditLog = auditLog,
                agentStatusProvider = { toolbar.agentStatus.value },
                onAgentStatusChange = { toolbar.setAgentStatus(it) },
                onReady = eventEmitter::completeHandshake,
                onRequestLogout = { onLogout() },
                tokenProvider = { authResult.token },
            )
            this.bridgeRouter = bridgeRouter

            jcefManager.setupBridge(
                installMessageRouter = bridgeRouter::install,
                onPageLoadEnd = eventEmitter::injectBridgeScript,
                onPageLoadStart = eventEmitter::resetHandshake,
            )
        }

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

        var previousConnState: String = "disconnected"

        scope.launch {
            sipAccountManager.accounts.collect { accounts ->
                val state = when {
                    accounts.any { it.state is SipAccountState.Connected } -> "connected"
                    accounts.any { it.state is SipAccountState.Reconnecting } -> "reconnecting"
                    else -> "disconnected"
                }
                val connectedCount = accounts.count { it.state is SipAccountState.Connected }

                if (state != previousConnState) {
                    previousConnState = state
                    eventEmitter.emitConnectionChanged(state, connectedCount)
                }
            }
        }

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
        // No auto-logout on SIP disconnect — user stays on main screen
        // and can reconnect by clicking the SIP chip
    }

    fun onThemeChanged(isDark: Boolean) {
        eventEmitter.emitThemeChanged(if (isDark) "dark" else "light")
    }

    fun onLocaleChanged(locale: String) {
        eventEmitter.emitLocaleChanged(locale)
    }

    fun logout() {
        toolbar.closeSettings()
        scope.launch {
            kotlinx.coroutines.delay(350)
            toolbar.disconnect()
            onLogout()
        }
    }
}
