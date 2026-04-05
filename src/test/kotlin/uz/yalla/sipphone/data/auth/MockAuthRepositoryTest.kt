package uz.yalla.sipphone.data.auth

import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.AuthRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockAuthRepositoryTest {
    private val repo: AuthRepository = MockAuthRepository()

    @Test
    fun `correct password returns success`() = runTest {
        val result = repo.login("test123")
        assertTrue(result.isSuccess)
        val auth = result.getOrThrow()
        assertEquals("101", auth.sipCredentials.username)
        assertEquals("192.168.0.22", auth.sipCredentials.server)
        assertEquals(5060, auth.sipCredentials.port)
        assertEquals("Alisher", auth.agent.name)
        assertEquals("agent-042", auth.agent.id)
        assertTrue(auth.dispatcherUrl.isNotEmpty())
    }

    @Test
    fun `wrong password returns failure`() = runTest {
        val result = repo.login("wrong")
        assertTrue(result.isFailure)
    }

    @Test
    fun `empty password returns failure`() = runTest {
        val result = repo.login("")
        assertTrue(result.isFailure)
    }
}
