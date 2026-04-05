// src/test/kotlin/uz/yalla/sipphone/data/jcef/BridgeProtocolTest.kt
package uz.yalla.sipphone.data.jcef

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeProtocolTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `serialize incoming call event`() {
        val event = BridgeEvent.IncomingCall(
            callId = "uuid-123",
            number = "+998901234567",
            seq = 1,
            timestamp = 1712300000000,
        )
        val jsonStr = json.encodeToString(BridgeEvent.IncomingCall.serializer(), event)
        assertTrue(jsonStr.contains("uuid-123"))
        assertTrue(jsonStr.contains("+998901234567"))
        assertTrue(jsonStr.contains("inbound"))
    }

    @Test
    fun `serialize call ended event with reason`() {
        val event = BridgeEvent.CallEnded(
            callId = "uuid-123",
            number = "+998901234567",
            direction = "inbound",
            duration = 42,
            reason = "hangup",
            seq = 5,
            timestamp = 1712300042000,
        )
        val jsonStr = json.encodeToString(BridgeEvent.CallEnded.serializer(), event)
        assertTrue(jsonStr.contains("hangup"))
        assertTrue(jsonStr.contains("42"))
    }

    @Test
    fun `serialize command success result`() {
        val result = CommandResult.success(mapOf("callId" to "uuid-456"))
        val jsonStr = json.encodeToString(CommandResult.serializer(), result)
        assertTrue(jsonStr.contains("true"))
        assertTrue(jsonStr.contains("uuid-456"))
    }

    @Test
    fun `serialize command error result`() {
        val result = CommandResult.error("ALREADY_IN_CALL", "Active call exists", false)
        val jsonStr = json.encodeToString(CommandResult.serializer(), result)
        assertTrue(jsonStr.contains("false"))
        assertTrue(jsonStr.contains("ALREADY_IN_CALL"))
    }

    @Test
    fun `serialize init payload`() {
        val init = BridgeInitPayload(
            version = "1.0.0",
            capabilities = listOf("call", "agentStatus", "callQuality"),
            agent = BridgeAgent(id = "agent-042", name = "Alisher"),
            bufferedEvents = emptyList(),
        )
        val jsonStr = json.encodeToString(BridgeInitPayload.serializer(), init)
        assertTrue(jsonStr.contains("1.0.0"))
        assertTrue(jsonStr.contains("Alisher"))
    }
}
