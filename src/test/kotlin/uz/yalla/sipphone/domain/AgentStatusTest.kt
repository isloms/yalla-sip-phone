package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentStatusTest {
    @Test
    fun `all statuses have display names`() {
        AgentStatus.entries.forEach { status ->
            assertNotNull(status.displayName)
            assert(status.displayName.isNotBlank())
        }
    }

    @Test
    fun `wrap-up is distinct from other statuses`() {
        val wrapUp = AgentStatus.WRAP_UP
        assertEquals("Wrap-Up", wrapUp.displayName)
        assert(wrapUp != AgentStatus.READY)
    }

    @Test
    fun `all statuses have color hex`() {
        AgentStatus.entries.forEach { status ->
            assert(status.colorHex.startsWith("#"))
            assertEquals(7, status.colorHex.length)
        }
    }
}
