package uz.yalla.sipphone.data.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenProviderTest {

    @Test
    fun `initially returns null`() = runTest {
        val provider = InMemoryTokenProvider()
        assertNull(provider.getToken())
    }

    @Test
    fun `stores and retrieves token`() = runTest {
        val provider = InMemoryTokenProvider()
        provider.setToken("jwt-123")
        assertEquals("jwt-123", provider.getToken())
    }

    @Test
    fun `clearToken removes token`() = runTest {
        val provider = InMemoryTokenProvider()
        provider.setToken("jwt-123")
        provider.clearToken()
        assertNull(provider.getToken())
    }
}
