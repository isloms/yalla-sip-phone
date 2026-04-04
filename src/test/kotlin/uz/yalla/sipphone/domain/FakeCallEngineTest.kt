package uz.yalla.sipphone.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeCallEngineTest {

    @Test
    fun `initial state is Idle`() {
        val engine = FakeCallEngine()
        assertIs<CallState.Idle>(engine.callState.value)
    }

    @Test
    fun `makeCall stores last number`() = runTest {
        val engine = FakeCallEngine()
        engine.makeCall("+998901234567")
        assertEquals("+998901234567", engine.lastCallNumber)
    }

    @Test
    fun `answerCall increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateRinging("102", "Alex")
        engine.answerCall()
        assertEquals(1, engine.answerCallCount)
    }

    @Test
    fun `hangupCall increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.hangupCall()
        assertEquals(1, engine.hangupCallCount)
    }

    @Test
    fun `toggleMute increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.toggleMute()
        assertEquals(1, engine.toggleMuteCount)
    }

    @Test
    fun `toggleHold increments counter`() = runTest {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.toggleHold()
        assertEquals(1, engine.toggleHoldCount)
    }

    @Test
    fun `simulateRinging sets Ringing state`() {
        val engine = FakeCallEngine()
        engine.simulateRinging("102", "Alex")
        val state = engine.callState.value
        assertIs<CallState.Ringing>(state)
        assertEquals("102", state.callerNumber)
        assertEquals("Alex", state.callerName)
    }

    @Test
    fun `simulateActive sets Active state`() {
        val engine = FakeCallEngine()
        engine.simulateActive(remoteNumber = "102", remoteName = "Alex", isOutbound = false)
        val state = engine.callState.value
        assertIs<CallState.Active>(state)
        assertEquals("102", state.remoteNumber)
        assertEquals(false, state.isOutbound)
    }

    @Test
    fun `simulateIdle resets to Idle`() {
        val engine = FakeCallEngine()
        engine.simulateActive()
        engine.simulateIdle()
        assertIs<CallState.Idle>(engine.callState.value)
    }

    @Test
    fun `makeCall returns success by default`() = runTest {
        val engine = FakeCallEngine()
        assertTrue(engine.makeCall("102").isSuccess)
    }
}
