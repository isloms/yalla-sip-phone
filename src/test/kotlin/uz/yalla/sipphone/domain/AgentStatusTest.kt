package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentStatusTest {
    @Test
    fun `all expected statuses exist`() {
        val expected = setOf("READY", "AWAY", "BUSY", "OFFLINE")
        val actual = AgentStatus.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }
}
