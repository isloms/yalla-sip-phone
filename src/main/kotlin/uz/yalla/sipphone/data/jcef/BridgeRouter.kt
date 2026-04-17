package uz.yalla.sipphone.data.jcef

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefMessageRouter.CefMessageRouterConfig
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.PhoneNumberValidator
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.domain.SipConstants

private val logger = KotlinLogging.logger {}

class BridgeRouter(
    private val callEngine: CallEngine,
    private val sipAccountManager: SipAccountManager,
    private val security: BridgeSecurity,
    private val auditLog: BridgeAuditLog,
    private val agentStatusProvider: () -> AgentStatus,
    private val onAgentStatusChange: (AgentStatus) -> Unit,
    private val onReady: () -> String,
    private val onRequestLogout: () -> Unit = {},
    private val tokenProvider: suspend () -> String? = { null },
) {
    private var messageRouter: CefMessageRouter? = null
    private var installedClient: CefClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val disposed = AtomicBoolean(false)

    companion object {
        private const val COMMAND_TIMEOUT_MS = 30_000L
    }

    private object Commands {
        const val READY = "_ready"
        const val MAKE_CALL = "makeCall"
        const val ANSWER = "answer"
        const val REJECT = "reject"
        const val HANGUP = "hangup"
        const val SET_MUTE = "setMute"
        const val SET_HOLD = "setHold"
        const val SEND_DTMF = "sendDtmf"
        const val TRANSFER_CALL = "transferCall"
        const val SET_AGENT_STATUS = "setAgentStatus"
        const val GET_STATE = "getState"
        const val GET_VERSION = "getVersion"
        const val REQUEST_LOGOUT = "requestLogout"
    }

    fun install(client: CefClient) {
        val config = CefMessageRouterConfig().apply {
            jsQueryFunction = "yallaSipQuery"
            jsCancelFunction = "yallaSipQueryCancel"
        }
        messageRouter = CefMessageRouter.create(config)
        messageRouter!!.addHandler(RouterHandler(), false)
        client.addMessageRouter(messageRouter!!)
        installedClient = client
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        scope.cancel()
        messageRouter?.let { router ->
            installedClient?.removeMessageRouter(router)
        }
        installedClient = null
    }

    private inner class RouterHandler : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback,
        ): Boolean {
            if (disposed.get()) return false
            scope.launch {
                try {
                    val cmd = bridgeJson.decodeFromString<BridgeCommand>(request)

                    if (cmd.command != Commands.READY && !security.checkRateLimit(cmd.command)) {
                        val result = CommandResult.error("RATE_LIMITED", "Too many requests", true)
                        callback.success(bridgeJson.encodeToString(CommandResult.serializer(), result))
                        return@launch
                    }

                    auditLog.logCommand(cmd.command, cmd.params, "processing")

                    val result = withTimeout(COMMAND_TIMEOUT_MS) { dispatch(cmd) }

                    auditLog.logCommand(
                        cmd.command,
                        cmd.params,
                        if (result.success) "OK" else result.error?.code ?: "ERROR",
                    )
                    callback.success(bridgeJson.encodeToString(CommandResult.serializer(), result))
                } catch (e: Exception) {
                    val cmdName = runCatching {
                        bridgeJson.decodeFromString<BridgeCommand>(request).command
                    }.getOrDefault("unknown")
                    logger.error(e) { "Bridge command failed: $cmdName" }
                    val result = CommandResult.error("INTERNAL_ERROR", e.message ?: "Unknown error", false)
                    callback.success(bridgeJson.encodeToString(CommandResult.serializer(), result))
                }
            }
            return true
        }
    }

    private suspend fun dispatch(cmd: BridgeCommand): CommandResult {
        return when (cmd.command) {
            Commands.READY -> handleReady()
            Commands.MAKE_CALL -> handleMakeCall(cmd.params)
            Commands.ANSWER -> handleAnswer()
            Commands.REJECT -> handleReject()
            Commands.HANGUP -> handleHangup()
            Commands.SET_MUTE -> handleSetMute(cmd.params)
            Commands.SET_HOLD -> handleSetHold(cmd.params)
            Commands.SEND_DTMF -> handleSendDtmf(cmd.params)
            Commands.TRANSFER_CALL -> handleTransferCall(cmd.params)
            Commands.SET_AGENT_STATUS -> handleSetAgentStatus(cmd.params)
            Commands.GET_STATE -> handleGetState(tokenProvider())
            Commands.GET_VERSION -> handleGetVersion()
            Commands.REQUEST_LOGOUT -> {
                logger.info { "Frontend requested logout (token likely invalidated by another session)" }
                SwingUtilities.invokeLater { onRequestLogout() }
                CommandResult.success(null)
            }
            else -> CommandResult.error("INTERNAL_ERROR", "Unknown command: ${cmd.command}", false)
        }
    }

    private fun handleReady(): CommandResult {
        val initJson = onReady()
        return CommandResult.success(data = bridgeJson.parseToJsonElement(initJson))
    }

    private suspend fun handleMakeCall(params: Map<String, String>): CommandResult {
        val number = params["number"]
            ?: return CommandResult.error("INVALID_NUMBER", "Missing number", true)
        val validation = PhoneNumberValidator.validate(number)
        if (validation.isFailure) {
            return CommandResult.error(
                "INVALID_NUMBER",
                validation.exceptionOrNull()?.message ?: "Invalid",
                true,
            )
        }
        if (callEngine.callState.value !is CallState.Idle) {
            return CommandResult.error("ALREADY_IN_CALL", "Active call exists", false)
        }
        if (sipAccountManager.accounts.value.none { it.state is SipAccountState.Connected }) {
            return CommandResult.error("NOT_REGISTERED", "SIP not connected", false)
        }
        val result = callEngine.makeCall(validation.getOrThrow())
        return if (result.isSuccess) {
            val callId = (callEngine.callState.value as? CallState.Ringing)?.callId ?: "unknown"
            CommandResult.success(buildJsonObject { put("callId", callId) })
        } else {
            CommandResult.error(
                "INTERNAL_ERROR",
                result.exceptionOrNull()?.message ?: "Call failed",
                false,
            )
        }
    }

    private suspend fun handleAnswer(): CommandResult {
        if (callEngine.callState.value !is CallState.Ringing) {
            return CommandResult.error("NO_INCOMING_CALL", "No incoming call", false)
        }
        callEngine.answerCall()
        return CommandResult.success()
    }

    private suspend fun handleReject(): CommandResult {
        if (callEngine.callState.value !is CallState.Ringing) {
            return CommandResult.error("NO_INCOMING_CALL", "No incoming call", false)
        }
        callEngine.hangupCall()
        return CommandResult.success()
    }

    private suspend fun handleHangup(): CommandResult {
        if (callEngine.callState.value is CallState.Idle) {
            return CommandResult.error("NO_ACTIVE_CALL", "No call to hangup", false)
        }
        callEngine.hangupCall()
        return CommandResult.success()
    }

    private suspend fun handleSetMute(params: Map<String, String>): CommandResult {
        val callId = params["callId"]
            ?: return CommandResult.error("NO_ACTIVE_CALL", "Missing callId", false)
        val muted = params["muted"]?.toBooleanStrictOrNull()
            ?: return CommandResult.error("INTERNAL_ERROR", "Missing muted", false)
        callEngine.setMute(callId, muted)
        return CommandResult.success(buildJsonObject { put("isMuted", muted) })
    }

    private suspend fun handleSetHold(params: Map<String, String>): CommandResult {
        val callId = params["callId"]
            ?: return CommandResult.error("NO_ACTIVE_CALL", "Missing callId", false)
        val onHold = params["onHold"]?.toBooleanStrictOrNull()
            ?: return CommandResult.error("INTERNAL_ERROR", "Missing onHold", false)
        callEngine.setHold(callId, onHold)
        return CommandResult.success(buildJsonObject { put("isOnHold", onHold) })
    }

    private suspend fun handleSendDtmf(params: Map<String, String>): CommandResult {
        val callId = params["callId"]
            ?: return CommandResult.error("NO_ACTIVE_CALL", "Missing callId", false)
        val digits = params["digits"]
            ?: return CommandResult.error("INTERNAL_ERROR", "Missing digits", false)
        if (callEngine.callState.value !is CallState.Active) {
            return CommandResult.error("NO_ACTIVE_CALL", "No active call", false)
        }
        val result = callEngine.sendDtmf(callId, digits)
        return if (result.isSuccess) {
            CommandResult.success()
        } else {
            CommandResult.error(
                "INTERNAL_ERROR",
                result.exceptionOrNull()?.message ?: "DTMF failed",
                false,
            )
        }
    }

    private suspend fun handleTransferCall(params: Map<String, String>): CommandResult {
        val callId = params["callId"]
            ?: return CommandResult.error("NO_ACTIVE_CALL", "Missing callId", false)
        val destination = params["destination"]
            ?: return CommandResult.error("INTERNAL_ERROR", "Missing destination", false)
        if (callEngine.callState.value !is CallState.Active) {
            return CommandResult.error("NO_ACTIVE_CALL", "No active call", false)
        }
        val result = callEngine.transferCall(callId, destination)
        return if (result.isSuccess) {
            CommandResult.success(buildJsonObject { put("destination", destination) })
        } else {
            CommandResult.error(
                "INTERNAL_ERROR",
                result.exceptionOrNull()?.message ?: "Transfer failed",
                false,
            )
        }
    }

    private fun handleSetAgentStatus(params: Map<String, String>): CommandResult {
        val statusStr = params["status"]
            ?: return CommandResult.error("INTERNAL_ERROR", "Missing status", false)
        val status = AgentStatus.entries.find { it.name.equals(statusStr, ignoreCase = true) }
            ?: return CommandResult.error("INTERNAL_ERROR", "Invalid status: $statusStr", false)
        onAgentStatusChange(status)
        return CommandResult.success(buildJsonObject { put("status", status.name.lowercase()) })
    }

    private fun handleGetState(token: String? = null): CommandResult {
        val callState = callEngine.callState.value
        val sipAccounts = sipAccountManager.accounts.value

        val accounts = sipAccounts.map { account ->
            BridgeAccountState(
                id = account.id,
                name = account.name,
                extension = account.credentials.username,
                status = when (account.state) {
                    is SipAccountState.Connected -> "connected"
                    is SipAccountState.Reconnecting -> "reconnecting"
                    is SipAccountState.Disconnected -> "disconnected"
                },
            )
        }

        val connectionState = when {
            sipAccounts.any { it.state is SipAccountState.Connected } -> "connected"
            sipAccounts.any { it.state is SipAccountState.Reconnecting } -> "reconnecting"
            else -> "disconnected"
        }

        val call = when (callState) {
            is CallState.Ringing -> BridgeCallState(
                callId = callState.callId,
                number = callState.callerNumber,
                direction = callState.direction,
                state = if (callState.isOutbound) "outgoing" else "incoming",
                isMuted = false,
                isOnHold = false,
                duration = 0,
            )
            is CallState.Active -> BridgeCallState(
                callId = callState.callId,
                number = callState.remoteNumber,
                direction = callState.direction,
                state = if (callState.isOnHold) "on_hold" else "active",
                isMuted = callState.isMuted,
                isOnHold = callState.isOnHold,
                duration = 0,
            )
            else -> null
        }

        val state = BridgeState(
            connection = BridgeConnectionState(state = connectionState, attempt = 0),
            agentStatus = agentStatusProvider().name.lowercase(),
            call = call,
            token = token,
            accounts = accounts,
        )

        return CommandResult.success(data = bridgeJson.encodeToJsonElement(BridgeState.serializer(), state))
    }

    private fun handleGetVersion(): CommandResult {
        val info = BridgeVersionInfo(
            version = SipConstants.APP_VERSION,
            capabilities = listOf("call", "agentStatus", "callQuality", "dtmf", "transfer"),
        )
        return CommandResult.success(data = bridgeJson.encodeToJsonElement(BridgeVersionInfo.serializer(), info))
    }
}
