package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SipAccountTest {

    private val credentials = SipCredentials(
        server = "sip.yalla.uz",
        port = 5060,
        username = "1001",
        password = "secret",
    )

    @Test
    fun `SipAccountInfo generates stable id from extension and server`() {
        val info = SipAccountInfo(
            extensionNumber = 1001,
            serverUrl = "sip.yalla.uz",
            sipName = "Operator-1",
            credentials = credentials,
        )
        assertEquals("1001@sip.yalla.uz", info.id)
        assertEquals("Operator-1", info.name)
    }

    @Test
    fun `SipAccountInfo uses fallback name when sipName is null`() {
        val info = SipAccountInfo(
            extensionNumber = 1001,
            serverUrl = "sip.yalla.uz",
            sipName = null,
            credentials = credentials,
        )
        assertEquals("SIP 1001", info.name)
    }

    @Test
    fun `SipAccount default state is Disconnected`() {
        val account = SipAccount(
            id = "1001@sip.yalla.uz",
            name = "Operator-1",
            credentials = credentials,
            state = SipAccountState.Disconnected,
        )
        assertIs<SipAccountState.Disconnected>(account.state)
    }

    @Test
    fun `SipAccountState Reconnecting carries attempt info`() {
        val state = SipAccountState.Reconnecting(attempt = 3, nextRetryMs = 8000)
        assertEquals(3, state.attempt)
        assertEquals(8000, state.nextRetryMs)
    }
}
