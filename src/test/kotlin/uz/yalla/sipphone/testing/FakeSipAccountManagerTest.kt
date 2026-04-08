package uz.yalla.sipphone.testing

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.SipAccountInfo
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.domain.SipCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeSipAccountManagerTest {

    private val manager = FakeSipAccountManager()
    private val creds = SipCredentials("sip.yalla.uz", 5060, "1001", "pass")
    private val accountInfo = SipAccountInfo(1001, "sip.yalla.uz", "Operator-1", creds)

    @Test
    fun `registerAll adds accounts in Connected state`() = runTest {
        manager.registerAll(listOf(accountInfo))

        val accounts = manager.accounts.value
        assertEquals(1, accounts.size)
        assertEquals("1001@sip.yalla.uz", accounts[0].id)
        assertIs<SipAccountState.Connected>(accounts[0].state)
    }

    @Test
    fun `disconnect changes account to Disconnected`() = runTest {
        manager.registerAll(listOf(accountInfo))

        manager.disconnect("1001@sip.yalla.uz")

        assertIs<SipAccountState.Disconnected>(manager.accounts.value[0].state)
    }

    @Test
    fun `connect changes Disconnected account to Connected`() = runTest {
        manager.registerAll(listOf(accountInfo))
        manager.disconnect("1001@sip.yalla.uz")

        manager.connect("1001@sip.yalla.uz")

        assertIs<SipAccountState.Connected>(manager.accounts.value[0].state)
    }

    @Test
    fun `unregisterAll clears all accounts`() = runTest {
        manager.registerAll(listOf(accountInfo))

        manager.unregisterAll()

        assertTrue(manager.accounts.value.isEmpty())
    }

    @Test
    fun `simulateAccountState changes specific account state`() = runTest {
        manager.registerAll(listOf(accountInfo))

        manager.simulateAccountState("1001@sip.yalla.uz", SipAccountState.Reconnecting(1, 2000))

        assertIs<SipAccountState.Reconnecting>(manager.accounts.value[0].state)
    }

    @Test
    fun `accounts flow emits after registerAll`() = runTest {
        manager.accounts.test {
            assertEquals(emptyList(), awaitItem())

            manager.registerAll(listOf(accountInfo))
            val accounts = awaitItem()
            assertEquals(1, accounts.size)
        }
    }

    @Test
    fun `registerAll failure does not add accounts`() = runTest {
        manager.registerAllResult = Result.failure(Exception("fail"))

        manager.registerAll(listOf(accountInfo))

        assertTrue(manager.accounts.value.isEmpty())
    }

    @Test
    fun `registerAll tracks call count and last accounts`() = runTest {
        assertEquals(0, manager.registerAllCallCount)

        manager.registerAll(listOf(accountInfo))

        assertEquals(1, manager.registerAllCallCount)
        assertEquals(listOf(accountInfo), manager.lastRegisteredAccounts)
    }

    @Test
    fun `unregisterAll tracks call count`() = runTest {
        assertEquals(0, manager.unregisterAllCallCount)

        manager.unregisterAll()

        assertEquals(1, manager.unregisterAllCallCount)
    }
}
