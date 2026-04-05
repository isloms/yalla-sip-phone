// src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeEventEmitter.kt
package uz.yalla.sipphone.data.jcef

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import org.cef.browser.CefBrowser
import uz.yalla.sipphone.domain.AgentInfo
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

class BridgeEventEmitter(
    private val auditLog: BridgeAuditLog,
) {
    private val seqCounter = AtomicInteger(0)
    private val handshakeComplete = AtomicBoolean(false)
    private val bufferedEvents = CopyOnWriteArrayList<String>()
    private var currentBrowser: CefBrowser? = null

    // Agent info for init payload — set before browser loads
    var agentInfo: AgentInfo = AgentInfo("", "")
    var version: String = "1.0.0"
    var capabilities: List<String> = listOf("call", "agentStatus", "callQuality")

    fun nextSeq(): Int = seqCounter.incrementAndGet()
    fun now(): Long = System.currentTimeMillis()

    fun resetHandshake() {
        handshakeComplete.set(false)
        bufferedEvents.clear()
    }

    fun injectBridgeScript(browser: CefBrowser) {
        currentBrowser = browser
        val js = buildBridgeScript()
        browser.executeJavaScript(js, browser.url ?: "", 0)
        logger.info { "Bridge script injected" }
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
            // Buffer event as serialized JSON wrapper
            val wrapper = """{"event":"$eventName","data":$payloadJson}"""
            bufferedEvents.add(wrapper)
            logger.debug { "Buffered event: $eventName (handshake pending)" }
            return
        }

        val browser = currentBrowser
        if (browser == null) {
            logger.warn { "Cannot emit event $eventName — no browser" }
            return
        }

        // Safe: payloadJson is from kotlinx.serialization, eventName is from our enum
        val js = "window.__yallaSipEmit && window.__yallaSipEmit('$eventName', $payloadJson);"
        browser.executeJavaScript(js, browser.url ?: "", 0)
    }

    // --- Typed emit methods for each event ---

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

    fun emitConnectionChanged(state: String, attempt: Int) {
        val event = BridgeEvent.ConnectionChanged(state, attempt, seq = nextSeq(), timestamp = now())
        emit("connectionChanged", bridgeJson.encodeToString(event))
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

    private fun buildBridgeScript(): String = """
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
        ready: function() {
            return window.yallaSipQuery({ request: JSON.stringify({ command: '_ready' }) })
                .then(function(r) { return JSON.parse(r); });
        },
        makeCall: function(number) {
            return window.yallaSipQuery({ request: JSON.stringify({ command: 'makeCall', params: { number: number } }) })
                .then(function(r) { return JSON.parse(r); });
        },
        answer: function(callId) {
            return window.yallaSipQuery({ request: JSON.stringify({ command: 'answer', params: { callId: callId } }) })
                .then(function(r) { return JSON.parse(r); });
        },
        reject: function(callId) {
            return window.yallaSipQuery({ request: JSON.stringify({ command: 'reject', params: { callId: callId } }) })
                .then(function(r) { return JSON.parse(r); });
        },
        hangup: function(callId) {
            return window.yallaSipQuery({ request: JSON.stringify({ command: 'hangup', params: { callId: callId } }) })
                .then(function(r) { return JSON.parse(r); });
        },
        setMute: function(callId, muted) {
            return window.yallaSipQuery({ request: JSON.stringify({ command: 'setMute', params: { callId: callId, muted: String(muted) } }) })
                .then(function(r) { return JSON.parse(r); });
        },
        setHold: function(callId, onHold) {
            return window.yallaSipQuery({ request: JSON.stringify({ command: 'setHold', params: { callId: callId, onHold: String(onHold) } }) })
                .then(function(r) { return JSON.parse(r); });
        },
        setAgentStatus: function(status) {
            return window.yallaSipQuery({ request: JSON.stringify({ command: 'setAgentStatus', params: { status: status } }) })
                .then(function(r) { return JSON.parse(r); });
        },
        getState: function() {
            return window.yallaSipQuery({ request: JSON.stringify({ command: 'getState' }) })
                .then(function(r) { return JSON.parse(r); });
        },
        getVersion: function() {
            return window.yallaSipQuery({ request: JSON.stringify({ command: 'getVersion' }) })
                .then(function(r) { return JSON.parse(r); });
        }
    };

    console.log('[YallaSIP] Bridge script injected, version pending handshake');
})();
""".trimIndent()
}
