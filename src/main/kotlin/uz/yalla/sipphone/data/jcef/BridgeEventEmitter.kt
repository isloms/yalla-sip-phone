package uz.yalla.sipphone.data.jcef

import kotlinx.serialization.encodeToString
import org.cef.browser.CefBrowser
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.SipConstants
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BridgeEventEmitter(
    private val auditLog: BridgeAuditLog,
) {
    private val seqCounter = AtomicInteger(0)
    private val handshakeComplete = AtomicBoolean(false)
    private val bufferedEvents = CopyOnWriteArrayList<String>()
    private var currentBrowser: CefBrowser? = null

    var agentInfo: AgentInfo = AgentInfo("", "")
    var version: String = SipConstants.APP_VERSION
    var capabilities: List<String> = listOf("call", "agentStatus", "callQuality")

    fun nextSeq(): Int = seqCounter.incrementAndGet()
    fun now(): Long = System.currentTimeMillis()

    fun resetHandshake() {
        handshakeComplete.set(false)
        bufferedEvents.clear()
    }

    fun injectBridgeScript(browser: CefBrowser) {
        currentBrowser = browser
        browser.executeJavaScript(BRIDGE_SCRIPT, browser.url ?: "", 0)
    }

    fun completeHandshake(): String {
        handshakeComplete.set(true)
        val init = BridgeInitPayload(
            version = version,
            capabilities = capabilities,
            agent = BridgeAgent(id = agentInfo.id, name = agentInfo.name),
            bufferedEvents = bufferedEvents.toList(),
        )
        bufferedEvents.clear()
        return bridgeJson.encodeToString(init)
    }

    fun emit(eventName: String, payloadJson: String) {
        auditLog.logEvent(eventName, payloadJson)

        if (!handshakeComplete.get()) {
            bufferedEvents.add("""{"event":"$eventName","data":$payloadJson}""")
            return
        }

        val browser = currentBrowser ?: return

        val js = "window.__yallaSipEmit && window.__yallaSipEmit('$eventName', $payloadJson);"
        browser.executeJavaScript(js, browser.url ?: "", 0)
    }

    fun emitIncomingCall(callId: String, number: String) {
        val event = BridgeEvent.IncomingCall(callId, number, seq = nextSeq(), timestamp = now())
        emit("incomingCall", bridgeJson.encodeToString(event))
    }

    fun emitOutgoingCall(callId: String, number: String) {
        val event = BridgeEvent.OutgoingCall(callId, number, seq = nextSeq(), timestamp = now())
        emit("outgoingCall", bridgeJson.encodeToString(event))
    }

    fun emitCallConnected(callId: String, number: String, direction: String) {
        val event = BridgeEvent.CallConnected(callId, number, direction, seq = nextSeq(), timestamp = now())
        emit("callConnected", bridgeJson.encodeToString(event))
    }

    fun emitCallEnded(callId: String, number: String, direction: String, duration: Int, reason: String) {
        val event = BridgeEvent.CallEnded(callId, number, direction, duration, reason, seq = nextSeq(), timestamp = now())
        emit("callEnded", bridgeJson.encodeToString(event))
    }

    fun emitCallMuteChanged(callId: String, isMuted: Boolean) {
        val event = BridgeEvent.CallMuteChanged(callId, isMuted, seq = nextSeq(), timestamp = now())
        emit("callMuteChanged", bridgeJson.encodeToString(event))
    }

    fun emitCallHoldChanged(callId: String, isOnHold: Boolean) {
        val event = BridgeEvent.CallHoldChanged(callId, isOnHold, seq = nextSeq(), timestamp = now())
        emit("callHoldChanged", bridgeJson.encodeToString(event))
    }

    fun emitAgentStatusChanged(status: String, previousStatus: String) {
        val event = BridgeEvent.AgentStatusChanged(status, previousStatus, seq = nextSeq(), timestamp = now())
        emit("agentStatusChanged", bridgeJson.encodeToString(event))
    }

    fun emitConnectionChanged(state: String, attempt: Int, accountId: String = "") {
        val event = BridgeEvent.ConnectionChanged(state, attempt, accountId = accountId, seq = nextSeq(), timestamp = now())
        emit("connectionChanged", bridgeJson.encodeToString(event))
    }

    fun emitAccountStatusChanged(accountId: String, name: String, status: String) {
        val event = BridgeEvent.AccountStatusChanged(
            accountId = accountId,
            name = name,
            status = status,
            seq = nextSeq(),
            timestamp = now(),
        )
        emit("accountStatusChanged", bridgeJson.encodeToString(event))
    }

    fun emitCallQualityUpdate(callId: String, quality: String) {
        val event = BridgeEvent.CallQualityUpdate(callId, quality, seq = nextSeq(), timestamp = now())
        emit("callQualityUpdate", bridgeJson.encodeToString(event))
    }

    fun emitThemeChanged(theme: String) {
        val event = BridgeEvent.ThemeChanged(theme, seq = nextSeq(), timestamp = now())
        emit("themeChanged", bridgeJson.encodeToString(event))
    }

    fun emitError(code: String, message: String, severity: String) {
        val event = BridgeEvent.BridgeError(code, message, severity, seq = nextSeq(), timestamp = now())
        emit("error", bridgeJson.encodeToString(event))
    }

    fun emitCallRejectedBusy(number: String) {
        val event = BridgeEvent.CallRejectedBusy(number, seq = nextSeq(), timestamp = now())
        emit("callRejectedBusy", bridgeJson.encodeToString(event))
    }

    companion object {
        private val BRIDGE_SCRIPT = """
(function() {
    var listeners = {};

    window.__yallaSipEmit = function(event, data) {
        var handlers = listeners[event];
        if (handlers) {
            for (var i = 0; i < handlers.length; i++) {
                try { handlers[i](data); } catch(e) { console.error('YallaSIP handler error:', e); }
            }
        }
    };

    // CefMessageRouter uses callbacks, not Promises — wrap in Promise
    function query(cmd) {
        return new Promise(function(resolve, reject) {
            window.yallaSipQuery({
                request: JSON.stringify(cmd),
                onSuccess: function(response) { resolve(JSON.parse(response)); },
                onFailure: function(code, msg) { reject(new Error(code + ': ' + msg)); }
            });
        });
    }

    window.YallaSIP = {
        on: function(event, handler) {
            if (!listeners[event]) listeners[event] = [];
            listeners[event].push(handler);
            return function() {
                var idx = listeners[event].indexOf(handler);
                if (idx >= 0) listeners[event].splice(idx, 1);
            };
        },
        off: function(event, handler) {
            if (!listeners[event]) return;
            var idx = listeners[event].indexOf(handler);
            if (idx >= 0) listeners[event].splice(idx, 1);
        },
        ready: function() { return query({ command: '_ready' }); },
        makeCall: function(number) { return query({ command: 'makeCall', params: { number: number } }); },
        answer: function(callId) { return query({ command: 'answer', params: { callId: callId } }); },
        reject: function(callId) { return query({ command: 'reject', params: { callId: callId } }); },
        hangup: function(callId) { return query({ command: 'hangup', params: { callId: callId } }); },
        setMute: function(callId, muted) { return query({ command: 'setMute', params: { callId: callId, muted: String(muted) } }); },
        setHold: function(callId, onHold) { return query({ command: 'setHold', params: { callId: callId, onHold: String(onHold) } }); },
        sendDtmf: function(callId, digits) { return query({ command: 'sendDtmf', params: { callId: callId, digits: digits } }); },
        transferCall: function(callId, destination) { return query({ command: 'transferCall', params: { callId: callId, destination: destination } }); },
        setAgentStatus: function(status) { return query({ command: 'setAgentStatus', params: { status: status } }); },
        getState: function() { return query({ command: 'getState' }).then(function(r) { return r.data; }); },
        getVersion: function() { return query({ command: 'getVersion' }).then(function(r) { return r.data; }); },

        // --- Simulator for DevTools testing ---
        simulate: (function() {
            var seq = 0;
            var call = null;
            var timer = null;

            function emit(event, data) {
                data.seq = ++seq;
                data.timestamp = Date.now();
                window.__yallaSipEmit(event, data);
                console.log('[simulate] ' + event, data);
            }

            return {
                // Incoming call: simulate.incoming('998901234567')
                incoming: function(number) {
                    if (call) { console.warn('[simulate] Already in call, hangup first'); return; }
                    call = { callId: 'sim-' + Date.now(), number: number || '+998901234567', direction: 'inbound', start: 0 };
                    emit('incomingCall', { callId: call.callId, number: call.number, direction: 'inbound' });
                    return call.callId;
                },

                // Outgoing call: simulate.outgoing('102')
                outgoing: function(number) {
                    if (call) { console.warn('[simulate] Already in call, hangup first'); return; }
                    call = { callId: 'sim-' + Date.now(), number: number || '102', direction: 'outbound', start: 0 };
                    emit('outgoingCall', { callId: call.callId, number: call.number, direction: 'outbound' });
                    return call.callId;
                },

                // Answer/connect: simulate.answer()
                answer: function() {
                    if (!call) { console.warn('[simulate] No call to answer'); return; }
                    call.start = Date.now();
                    emit('callConnected', { callId: call.callId, number: call.number, direction: call.direction });
                },

                // Mute toggle: simulate.mute() / simulate.mute(false)
                mute: function(muted) {
                    if (!call) { console.warn('[simulate] No active call'); return; }
                    call.isMuted = muted !== false;
                    emit('callMuteChanged', { callId: call.callId, isMuted: call.isMuted });
                },

                // Hold toggle: simulate.hold() / simulate.hold(false)
                hold: function(onHold) {
                    if (!call) { console.warn('[simulate] No active call'); return; }
                    call.isOnHold = onHold !== false;
                    emit('callHoldChanged', { callId: call.callId, isOnHold: call.isOnHold });
                },

                // Hangup: simulate.hangup('missed')
                hangup: function(reason) {
                    if (!call) { console.warn('[simulate] No call to hangup'); return; }
                    var duration = call.start ? Math.floor((Date.now() - call.start) / 1000) : 0;
                    emit('callEnded', { callId: call.callId, number: call.number, direction: call.direction, duration: duration, reason: reason || 'hangup' });
                    call = null;
                },

                // Busy reject: simulate.busy('998907654321')
                busy: function(number) {
                    emit('callRejectedBusy', { number: number || '+998907654321' });
                },

                // Connection change: simulate.disconnect() / simulate.reconnect() / simulate.connect()
                disconnect: function() { emit('connectionChanged', { state: 'disconnected', attempt: 0 }); },
                reconnect: function(attempt) { emit('connectionChanged', { state: 'reconnecting', attempt: attempt || 1 }); },
                connect: function() { emit('connectionChanged', { state: 'connected', attempt: 0 }); },

                // Full scenario: simulate.callFlow() — incoming → answer → mute → unmute → hold → unhold → hangup
                callFlow: function(number) {
                    var self = this;
                    var steps = [
                        function() { self.incoming(number); },
                        function() { self.answer(); },
                        function() { self.mute(); },
                        function() { self.mute(false); },
                        function() { self.hold(); },
                        function() { self.hold(false); },
                        function() { self.hangup(); },
                    ];
                    var i = 0;
                    console.log('[simulate] Starting call flow (' + steps.length + ' steps, 2s interval)');
                    timer = setInterval(function() {
                        if (i < steps.length) { steps[i++](); } else { clearInterval(timer); timer = null; console.log('[simulate] Call flow complete'); }
                    }, 2000);
                },

                // Busy day: simulate.busyDay(5) — N incoming calls with random timing
                busyDay: function(count) {
                    var self = this;
                    var n = count || 5;
                    var i = 0;
                    console.log('[simulate] Busy day: ' + n + ' calls');
                    function nextCall() {
                        if (i >= n) { console.log('[simulate] Busy day complete'); return; }
                        i++;
                        var num = '+99890' + (1000000 + Math.floor(Math.random() * 9000000));
                        self.incoming(num);
                        setTimeout(function() {
                            self.answer();
                            var duration = 3000 + Math.floor(Math.random() * 7000);
                            if (Math.random() > 0.5) setTimeout(function() { self.mute(); setTimeout(function() { self.mute(false); }, 1000); }, 1000);
                            setTimeout(function() { self.hangup(); setTimeout(nextCall, 1000 + Math.floor(Math.random() * 2000)); }, duration);
                        }, 1500 + Math.floor(Math.random() * 2000));
                    }
                    nextCall();
                },

                // Stop any running scenario
                stop: function() { if (timer) { clearInterval(timer); timer = null; } call = null; console.log('[simulate] Stopped'); },

                // Current simulated state
                state: function() { return call ? JSON.parse(JSON.stringify(call)) : null; }
            };
        })()
    };

    console.log('[YallaSIP] Bridge ready. Test with: YallaSIP.simulate.callFlow()');
})();
""".trimIndent()
    }
}
