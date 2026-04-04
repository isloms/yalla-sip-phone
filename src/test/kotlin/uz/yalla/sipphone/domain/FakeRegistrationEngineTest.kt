package uz.yalla.sipphone.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeRegistrationEngineTest {

    @Test
    fun `initial state is Idle`() {
        val engine = FakeRegistrationEngine()
        assertIs<RegistrationState.Idle>(engine.registrationState.value)
    }

    @Test
    fun `register transitions to Registering`() = runTest {
        val engine = FakeRegistrationEngine()
        val credentials = SipCredentials("192.168.0.22", 5060, "102", "pass")

        engine.register(credentials)

        assertIs<RegistrationState.Registering>(engine.registrationState.value)
        assertEquals("102", engine.lastCredentials?.username)
    }

    @Test
    fun `simulateRegistered transitions to Registered`() {
        val engine = FakeRegistrationEngine()
        engine.simulateRegistered("sip:102@192.168.0.22")

        val state = engine.registrationState.value
        assertIs<RegistrationState.Registered>(state)
        assertEquals("sip:102@192.168.0.22", state.server)
    }

    @Test
    fun `simulateFailed transitions to Failed`() {
        val engine = FakeRegistrationEngine()
        engine.simulateFailed("403 Forbidden")

        val state = engine.registrationState.value
        assertIs<RegistrationState.Failed>(state)
        assertIs<SipError.AuthFailed>(state.error)
    }

    @Test
    fun `unregister transitions to Idle`() = runTest {
        val engine = FakeRegistrationEngine()
        engine.simulateRegistered()

        engine.unregister()

        assertIs<RegistrationState.Idle>(engine.registrationState.value)
    }

    @Test
    fun `destroy transitions to Idle`() = runTest {
        val engine = FakeRegistrationEngine()
        engine.simulateRegistered()

        engine.destroy()

        assertIs<RegistrationState.Idle>(engine.registrationState.value)
    }

    @Test
    fun `init sets initCalled flag`() = runTest {
        val engine = FakeRegistrationEngine()

        val result = engine.init()

        assertTrue(result.isSuccess)
        assertTrue(engine.initCalled)
    }

    @Test
    fun `register stores last credentials`() = runTest {
        val engine = FakeRegistrationEngine()
        val creds = SipCredentials("10.0.0.1", 5080, "user1", "secret")

        engine.register(creds)

        assertEquals(creds, engine.lastCredentials)
    }

    @Test
    fun `lastCredentials is null before register`() {
        val engine = FakeRegistrationEngine()
        assertNull(engine.lastCredentials)
    }

    @Test
    fun `full lifecycle - register, registered, unregister`() = runTest {
        val engine = FakeRegistrationEngine()

        assertIs<RegistrationState.Idle>(engine.registrationState.value)

        engine.register(SipCredentials("server", 5060, "user", "pass"))
        assertIs<RegistrationState.Registering>(engine.registrationState.value)

        engine.simulateRegistered()
        assertIs<RegistrationState.Registered>(engine.registrationState.value)

        engine.unregister()
        assertIs<RegistrationState.Idle>(engine.registrationState.value)
    }
}
