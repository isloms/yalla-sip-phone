// src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeProtocol.kt
package uz.yalla.sipphone.data.jcef

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val bridgeJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

// --- Events (Native → Web) ---

object BridgeEvent {
    @Serializable
    data class IncomingCall(
        val callId: String,
        val number: String,
        val direction: String = "inbound",
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class OutgoingCall(
        val callId: String,
        val number: String,
        val direction: String = "outbound",
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallConnected(
        val callId: String,
        val number: String,
        val direction: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallEnded(
        val callId: String,
        val number: String,
        val direction: String,
        val duration: Int,
        val reason: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallMuteChanged(
        val callId: String,
        val isMuted: Boolean,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallHoldChanged(
        val callId: String,
        val isOnHold: Boolean,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class AgentStatusChanged(
        val status: String,
        val previousStatus: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class ConnectionChanged(
        val state: String,
        val attempt: Int,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallQualityUpdate(
        val callId: String,
        val quality: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class ThemeChanged(
        val theme: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class BridgeError(
        val code: String,
        val message: String,
        val severity: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallRejectedBusy(
        val number: String,
        val seq: Int,
        val timestamp: Long,
    )
}

// --- Command Results ---

@Serializable
data class CommandResult(
    val success: Boolean,
    val data: Map<String, String>? = null,
    val error: CommandError? = null,
) {
    companion object {
        fun success(data: Map<String, String>? = null) = CommandResult(success = true, data = data)
        fun error(code: String, message: String, recoverable: Boolean) = CommandResult(
            success = false,
            error = CommandError(code, message, recoverable),
        )
    }
}

@Serializable
data class CommandError(
    val code: String,
    val message: String,
    val recoverable: Boolean,
)

// --- Command Request (from web) ---

@Serializable
data class BridgeCommand(
    val command: String,
    val params: Map<String, String> = emptyMap(),
)

// --- Init Payload ---

@Serializable
data class BridgeInitPayload(
    val version: String,
    val capabilities: List<String>,
    val agent: BridgeAgent,
    val bufferedEvents: List<String>, // serialized event JSONs
)

@Serializable
data class BridgeAgent(
    val id: String,
    val name: String,
)

// --- State Snapshot ---

@Serializable
data class BridgeState(
    val connection: BridgeConnectionState,
    val agentStatus: String,
    val call: BridgeCallState? = null,
)

@Serializable
data class BridgeConnectionState(
    val state: String,
    val attempt: Int,
)

@Serializable
data class BridgeCallState(
    val callId: String,
    val number: String,
    val direction: String,
    val state: String,
    val isMuted: Boolean,
    val isOnHold: Boolean,
    val duration: Int,
)

@Serializable
data class BridgeVersionInfo(
    val version: String,
    val capabilities: List<String>,
)
