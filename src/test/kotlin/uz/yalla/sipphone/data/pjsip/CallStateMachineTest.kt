package uz.yalla.sipphone.data.pjsip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import uz.yalla.sipphone.domain.CallState

class CallStateMachineTest {

    @Test
    fun `Idle plus OutgoingDial yields outbound Ringing`() {
        val m = CallStateMachine()

        val next = m.dispatch(
            CallEvent.OutgoingDial(
                callId = "c1",
                remoteNumber = "101",
                accountId = "acc1",
            ),
        )

        assertIs<CallState.Ringing>(next)
        assertEquals("c1", next.callId)
        assertEquals("101", next.callerNumber)
        assertEquals(true, next.isOutbound)
        assertEquals("acc1", next.accountId)
    }

    @Test
    fun `Idle plus IncomingRing yields inbound Ringing`() {
        val m = CallStateMachine()

        val next = m.dispatch(
            CallEvent.IncomingRing(
                callId = "c2",
                remoteNumber = "555",
                remoteName = "Alice",
                accountId = "acc1",
                remoteUri = "sip:555@pbx",
            ),
        )

        assertIs<CallState.Ringing>(next)
        assertEquals(false, next.isOutbound)
        assertEquals("Alice", next.callerName)
        assertEquals("sip:555@pbx", next.remoteUri)
    }

    @Test
    fun `Ringing plus Answered yields Active carrying Ringing fields`() {
        val m = CallStateMachine()
        m.dispatch(CallEvent.OutgoingDial("c1", "101", "acc1"))

        val next = m.dispatch(CallEvent.Answered)

        assertIs<CallState.Active>(next)
        assertEquals("c1", next.callId)
        assertEquals("101", next.remoteNumber)
        assertEquals(true, next.isOutbound)
        assertEquals("acc1", next.accountId)
        assertEquals(false, next.isMuted)
        assertEquals(false, next.isOnHold)
    }

    @Test
    fun `Ringing plus LocalHangup yields Ending`() {
        val m = CallStateMachine()
        m.dispatch(CallEvent.OutgoingDial("c1", "101", "acc1"))

        val next = m.dispatch(CallEvent.LocalHangup)

        assertIs<CallState.Ending>(next)
        assertEquals("c1", next.callId)
        assertEquals("acc1", next.accountId)
    }

    @Test
    fun `Active plus LocalHangup yields Ending`() {
        val m = CallStateMachine()
        m.dispatch(CallEvent.OutgoingDial("c1", "101", "acc1"))
        m.dispatch(CallEvent.Answered)

        val next = m.dispatch(CallEvent.LocalHangup)

        assertIs<CallState.Ending>(next)
        assertEquals("c1", next.callId)
    }

    @Test
    fun `RemoteDisconnect yields Idle from Active`() {
        val m = CallStateMachine()
        m.dispatch(CallEvent.OutgoingDial("c1", "101", "acc1"))
        m.dispatch(CallEvent.Answered)

        val next = m.dispatch(CallEvent.RemoteDisconnect)

        assertEquals(CallState.Idle, next)
    }

    @Test
    fun `MuteChanged on Active toggles isMuted`() {
        val m = CallStateMachine()
        m.dispatch(CallEvent.OutgoingDial("c1", "101", "acc1"))
        m.dispatch(CallEvent.Answered)

        val next = m.dispatch(CallEvent.MuteChanged(true))

        assertIs<CallState.Active>(next)
        assertEquals(true, next.isMuted)
    }

    @Test
    fun `HoldChanged on Active toggles isOnHold`() {
        val m = CallStateMachine()
        m.dispatch(CallEvent.OutgoingDial("c1", "101", "acc1"))
        m.dispatch(CallEvent.Answered)

        val next = m.dispatch(CallEvent.HoldChanged(true))

        assertIs<CallState.Active>(next)
        assertEquals(true, next.isOnHold)
    }

    @Test
    fun `Answered is ignored when not in Ringing`() {
        val m = CallStateMachine()

        val next = m.dispatch(CallEvent.Answered)

        assertEquals(CallState.Idle, next)
    }

    @Test
    fun `MuteChanged is ignored when not in Active`() {
        val m = CallStateMachine()
        m.dispatch(CallEvent.OutgoingDial("c1", "101", "acc1"))

        val next = m.dispatch(CallEvent.MuteChanged(true))

        assertIs<CallState.Ringing>(next)
    }

    @Test
    fun `dispatch emits to state flow`() {
        val m = CallStateMachine()

        m.dispatch(CallEvent.OutgoingDial("c1", "101", "acc1"))

        assertIs<CallState.Ringing>(m.state.value)
    }
}
