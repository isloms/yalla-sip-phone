# JCEF Webview + JS Bridge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the WebviewPlaceholder with a real JCEF Chromium browser loading the dispatcher URL, and implement the full bidirectional JS Bridge API (`window.YallaSIP`) for SIP ↔ Web communication.

**Architecture:** JCEF (JetBrains Runtime bundled Chromium) embedded via Compose Desktop's `SwingPanel`. JS Bridge uses `CefMessageRouter` for Web→Native commands (Promise-based) and `CefBrowser.executeJavaScript()` for Native→Web events (EventEmitter pattern). Bridge security via origin checking, rate limiting, and JSON serialization. All JCEF operations on Swing EDT.

**Tech Stack:** JBR-JCEF 21 (macOS arm64), Compose Desktop SwingPanel, kotlinx-serialization-json, CefMessageRouter/CefKeyboardHandler

**Spec:** `docs/superpowers/specs/2026-04-05-ui-redesign-design.md` (Sections 6, 7, 16.5, 16.8)
**API Doc:** `docs/JS_BRIDGE_API.md`

**Current state:** JBR 21.0.4 `nomod` installed (no JCEF). Need `jcef` variant.

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `data/jcef/JcefManager.kt` | CefApp/CefClient/CefBrowser lifecycle, EDT threading |
| `data/jcef/BridgeEventEmitter.kt` | Native→Web: emit events via executeJavaScript, JSON serialization |
| `data/jcef/BridgeRouter.kt` | Web→Native: CefMessageRouter handler, command dispatch, Promise resolution |
| `data/jcef/BridgeSecurity.kt` | Rate limiter, phone number validation (delegates to domain) |
| `data/jcef/BridgeAuditLog.kt` | Command/event logging with PII masking |
| `data/jcef/BridgeProtocol.kt` | Shared JSON models: event payloads, command results, error envelope |
| `feature/main/webview/WebviewPanel.kt` | SwingPanel composable wrapping CefBrowser |
| `di/WebviewModule.kt` | Koin bindings for JCEF + Bridge components |

### Modified Files

| File | Changes |
|------|---------|
| `build.gradle.kts` | JBR-JCEF javaHome config, `kotlinx-serialization-json` already present |
| `feature/main/MainComponent.kt` | Add BridgeEventEmitter, observe call/registration state, emit bridge events |
| `feature/main/MainScreen.kt` | Replace WebviewPlaceholder with WebviewPanel |
| `di/AppModule.kt` | Add webviewModule |
| `Main.kt` | JCEF shutdown in close handler, keyboard shortcuts via CefKeyboardHandler |
| `gradle.properties` | `org.gradle.java.home` pointing to JBR-JCEF |

### Test Files

| File | Tests |
|------|-------|
| `test/.../data/jcef/BridgeProtocolTest.kt` | JSON serialization of all event/command payloads |
| `test/.../data/jcef/BridgeSecurityTest.kt` | Rate limiter, validation |
| `test/.../data/jcef/BridgeAuditLogTest.kt` | PII masking in logs |

---

## Task 1: JBR-JCEF Setup

**Files:**
- Modify: `gradle.properties` (create if not exists)
- Modify: `build.gradle.kts`

- [ ] **Step 1: Download JBR-JCEF variant**

```bash
cd /Users/macbookpro/Library/Java/JavaVirtualMachines
# Download JBR 21.0.5 with JCEF for macOS ARM64
curl -L -o jbr_jcef-21.tar.gz "https://cache-redirector.jetbrains.com/intellij-jbr/jbr_jcef-21.0.5-osx-aarch64-b631.8.tar.gz"
# If the above URL doesn't work, try the GitHub releases:
# curl -L -o jbr_jcef-21.tar.gz "https://github.com/JetBrains/JetBrainsRuntime/releases/download/jbr-21.0.5b631.8/jbr_jcef-21.0.5-osx-aarch64-b631.8.tar.gz"
tar -xzf jbr_jcef-21.tar.gz
# This creates a directory like jbr_jcef-21.0.5-osx-aarch64-b631.8/ or jbr-21.0.5/
# Rename for clarity:
mv jbr_jcef-21.0.5* jbr-jcef-21 2>/dev/null || mv jbr-21.0.5* jbr-jcef-21 2>/dev/null
```

NOTE: Exact URL may vary. If curl fails, manually download from https://github.com/JetBrains/JetBrainsRuntime/releases — find the `jbr_jcef` variant for `osx-aarch64`. The key is the `jcef` in the name — `nomod` and `jbr` (without jcef) do NOT include Chromium.

- [ ] **Step 2: Verify JCEF classes exist in downloaded JBR**

```bash
find /Users/macbookpro/Library/Java/JavaVirtualMachines/jbr-jcef-21 -name "*.jar" | xargs -I{} jar tf {} 2>/dev/null | grep "org/cef" | head -5
# Should show: org/cef/CefApp.class, org/cef/CefClient.class, etc.
# Also check native libs:
ls /Users/macbookpro/Library/Java/JavaVirtualMachines/jbr-jcef-21/Contents/Home/lib/*cef* 2>/dev/null || ls /Users/macbookpro/Library/Java/JavaVirtualMachines/jbr-jcef-21/Contents/Frameworks/*cef* 2>/dev/null
```

- [ ] **Step 3: Configure Gradle to use JBR-JCEF**

Create/update `gradle.properties`:
```properties
org.gradle.java.home=/Users/macbookpro/Library/Java/JavaVirtualMachines/jbr-jcef-21/Contents/Home
```

- [ ] **Step 4: Verify JCEF classes are on classpath**

```bash
cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone
./gradlew compileKotlin 2>&1 | tail -5
```

Then create a minimal test file to verify JCEF is available:
```kotlin
// Temporary test — delete after verification
// src/main/kotlin/uz/yalla/sipphone/JcefCheck.kt
package uz.yalla.sipphone
fun checkJcef() {
    val clazz = Class.forName("org.cef.CefApp")
    println("JCEF available: ${clazz.name}")
}
```

Run: `./gradlew compileKotlin` — if it compiles, JCEF is on classpath. Delete the test file.

- [ ] **Step 5: Commit**

```bash
git add gradle.properties build.gradle.kts && git commit -m "chore: configure JBR-JCEF runtime for Chromium webview"
```

---

## Task 2: Bridge Protocol — JSON Models

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeProtocol.kt`
- Test: `src/test/kotlin/uz/yalla/sipphone/data/jcef/BridgeProtocolTest.kt`

- [ ] **Step 1: Write BridgeProtocol test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/jcef/BridgeProtocolTest.kt
package uz.yalla.sipphone.data.jcef

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeProtocolTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `serialize incoming call event`() {
        val event = BridgeEvent.IncomingCall(
            callId = "uuid-123",
            number = "+998901234567",
            seq = 1,
            timestamp = 1712300000000,
        )
        val jsonStr = json.encodeToString(BridgeEvent.IncomingCall.serializer(), event)
        assertTrue(jsonStr.contains("uuid-123"))
        assertTrue(jsonStr.contains("+998901234567"))
        assertTrue(jsonStr.contains("inbound"))
    }

    @Test
    fun `serialize call ended event with reason`() {
        val event = BridgeEvent.CallEnded(
            callId = "uuid-123",
            number = "+998901234567",
            direction = "inbound",
            duration = 42,
            reason = "hangup",
            seq = 5,
            timestamp = 1712300042000,
        )
        val jsonStr = json.encodeToString(BridgeEvent.CallEnded.serializer(), event)
        assertTrue(jsonStr.contains("hangup"))
        assertTrue(jsonStr.contains("42"))
    }

    @Test
    fun `serialize command success result`() {
        val result = CommandResult.success(mapOf("callId" to "uuid-456"))
        val jsonStr = json.encodeToString(CommandResult.serializer(), result)
        assertTrue(jsonStr.contains("true"))
        assertTrue(jsonStr.contains("uuid-456"))
    }

    @Test
    fun `serialize command error result`() {
        val result = CommandResult.error("ALREADY_IN_CALL", "Active call exists", false)
        val jsonStr = json.encodeToString(CommandResult.serializer(), result)
        assertTrue(jsonStr.contains("false"))
        assertTrue(jsonStr.contains("ALREADY_IN_CALL"))
    }

    @Test
    fun `serialize init payload`() {
        val init = BridgeInitPayload(
            version = "1.0.0",
            capabilities = listOf("call", "agentStatus", "callQuality"),
            agent = BridgeAgent(id = "agent-042", name = "Alisher"),
            bufferedEvents = emptyList(),
        )
        val jsonStr = json.encodeToString(BridgeInitPayload.serializer(), init)
        assertTrue(jsonStr.contains("1.0.0"))
        assertTrue(jsonStr.contains("Alisher"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "uz.yalla.sipphone.data.jcef.BridgeProtocolTest" 2>&1 | tail -10`

- [ ] **Step 3: Implement BridgeProtocol**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeProtocol.kt
package uz.yalla.sipphone.data.jcef

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val bridgeJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

// --- Events (Native → Web) ---

object BridgeEvent {
    @Serializable
    data class IncomingCall(
        val callId: String,
        val number: String,
        val direction: String = "inbound",
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class OutgoingCall(
        val callId: String,
        val number: String,
        val direction: String = "outbound",
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallConnected(
        val callId: String,
        val number: String,
        val direction: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallEnded(
        val callId: String,
        val number: String,
        val direction: String,
        val duration: Int,
        val reason: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallMuteChanged(
        val callId: String,
        val isMuted: Boolean,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallHoldChanged(
        val callId: String,
        val isOnHold: Boolean,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class AgentStatusChanged(
        val status: String,
        val previousStatus: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class ConnectionChanged(
        val state: String,
        val attempt: Int,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallQualityUpdate(
        val callId: String,
        val quality: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class ThemeChanged(
        val theme: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class BridgeError(
        val code: String,
        val message: String,
        val severity: String,
        val seq: Int,
        val timestamp: Long,
    )

    @Serializable
    data class CallRejectedBusy(
        val number: String,
        val seq: Int,
        val timestamp: Long,
    )
}

// --- Command Results ---

@Serializable
data class CommandResult(
    val success: Boolean,
    val data: Map<String, String>? = null,
    val error: CommandError? = null,
) {
    companion object {
        fun success(data: Map<String, String>? = null) = CommandResult(success = true, data = data)
        fun error(code: String, message: String, recoverable: Boolean) = CommandResult(
            success = false,
            error = CommandError(code, message, recoverable),
        )
    }
}

@Serializable
data class CommandError(
    val code: String,
    val message: String,
    val recoverable: Boolean,
)

// --- Command Request (from web) ---

@Serializable
data class BridgeCommand(
    val command: String,
    val params: Map<String, String> = emptyMap(),
)

// --- Init Payload ---

@Serializable
data class BridgeInitPayload(
    val version: String,
    val capabilities: List<String>,
    val agent: BridgeAgent,
    val bufferedEvents: List<String>, // serialized event JSONs
)

@Serializable
data class BridgeAgent(
    val id: String,
    val name: String,
)

// --- State Snapshot ---

@Serializable
data class BridgeState(
    val connection: BridgeConnectionState,
    val agentStatus: String,
    val call: BridgeCallState? = null,
)

@Serializable
data class BridgeConnectionState(
    val state: String,
    val attempt: Int,
)

@Serializable
data class BridgeCallState(
    val callId: String,
    val number: String,
    val direction: String,
    val state: String,
    val isMuted: Boolean,
    val isOnHold: Boolean,
    val duration: Int,
)

@Serializable
data class BridgeVersionInfo(
    val version: String,
    val capabilities: List<String>,
)
```

- [ ] **Step 4: Run test to verify it passes**

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(bridge): add BridgeProtocol JSON models for all events, commands, and state"
```

---

## Task 3: Bridge Security — Rate Limiter + Audit Log

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeSecurity.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeAuditLog.kt`
- Test: `src/test/kotlin/uz/yalla/sipphone/data/jcef/BridgeSecurityTest.kt`
- Test: `src/test/kotlin/uz/yalla/sipphone/data/jcef/BridgeAuditLogTest.kt`

- [ ] **Step 1: Write BridgeSecurity test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/jcef/BridgeSecurityTest.kt
package uz.yalla.sipphone.data.jcef

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BridgeSecurityTest {
    private val security = BridgeSecurity()

    @Test
    fun `allows first command`() {
        assertTrue(security.checkRateLimit("makeCall"))
    }

    @Test
    fun `blocks after exceeding limit`() {
        repeat(5) { security.checkRateLimit("makeCall") }
        assertFalse(security.checkRateLimit("makeCall"))
    }

    @Test
    fun `different commands have separate limits`() {
        repeat(5) { security.checkRateLimit("makeCall") }
        assertTrue(security.checkRateLimit("getState")) // separate limit
    }

    @Test
    fun `getState has higher limit`() {
        repeat(59) { assertTrue(security.checkRateLimit("getState")) }
        assertTrue(security.checkRateLimit("getState")) // 60th still OK
    }
}
```

- [ ] **Step 2: Write BridgeAuditLog test**

```kotlin
// src/test/kotlin/uz/yalla/sipphone/data/jcef/BridgeAuditLogTest.kt
package uz.yalla.sipphone.data.jcef

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BridgeAuditLogTest {
    private val log = BridgeAuditLog()

    @Test
    fun `logCommand masks phone number in params`() {
        val entry = log.formatEntry("makeCall", mapOf("number" to "+998901234567"))
        assertFalse(entry.contains("+998901234567"))
        assertTrue(entry.contains("***"))
    }

    @Test
    fun `logCommand does not mask non-phone params`() {
        val entry = log.formatEntry("setAgentStatus", mapOf("status" to "away"))
        assertTrue(entry.contains("away"))
    }
}
```

- [ ] **Step 3: Implement BridgeSecurity**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeSecurity.kt
package uz.yalla.sipphone.data.jcef

import java.util.concurrent.ConcurrentHashMap
import java.util.ArrayDeque

class BridgeSecurity {
    private val commandTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private val limits = mapOf(
        "makeCall" to RateLimit(max = 5, windowMs = 60_000),
        "hangup" to RateLimit(max = 10, windowMs = 60_000),
        "answer" to RateLimit(max = 10, windowMs = 60_000),
        "reject" to RateLimit(max = 10, windowMs = 60_000),
        "setMute" to RateLimit(max = 30, windowMs = 60_000),
        "setHold" to RateLimit(max = 20, windowMs = 60_000),
        "setAgentStatus" to RateLimit(max = 10, windowMs = 60_000),
        "getState" to RateLimit(max = 60, windowMs = 60_000),
        "getVersion" to RateLimit(max = 60, windowMs = 60_000),
    )

    fun checkRateLimit(command: String): Boolean {
        val limit = limits[command] ?: RateLimit(max = 30, windowMs = 60_000)
        val now = System.currentTimeMillis()
        val timestamps = commandTimestamps.getOrPut(command) { ArrayDeque() }

        synchronized(timestamps) {
            while (timestamps.isNotEmpty() && timestamps.first() < now - limit.windowMs) {
                timestamps.removeFirst()
            }
            if (timestamps.size >= limit.max) return false
            timestamps.addLast(now)
            return true
        }
    }

    private data class RateLimit(val max: Int, val windowMs: Long)
}
```

- [ ] **Step 4: Implement BridgeAuditLog**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeAuditLog.kt
package uz.yalla.sipphone.data.jcef

import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.util.PhoneNumberMasker

private val logger = KotlinLogging.logger {}

class BridgeAuditLog {
    private val phoneParamKeys = setOf("number", "phone", "callerNumber")

    fun logCommand(command: String, params: Map<String, String>, result: String) {
        val masked = formatEntry(command, params)
        logger.info { "BRIDGE CMD: $masked → $result" }
    }

    fun logEvent(eventName: String, payloadJson: String) {
        logger.debug { "BRIDGE EVT: $eventName" }
    }

    fun formatEntry(command: String, params: Map<String, String>): String {
        val maskedParams = params.mapValues { (key, value) ->
            if (key in phoneParamKeys) PhoneNumberMasker.mask(value) else value
        }
        return "$command($maskedParams)"
    }
}
```

- [ ] **Step 5: Run tests, verify pass**

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(bridge): add BridgeSecurity rate limiter and BridgeAuditLog with PII masking"
```

---

## Task 4: JcefManager — Browser Lifecycle

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/jcef/JcefManager.kt`

- [ ] **Step 1: Implement JcefManager**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/jcef/JcefManager.kt
package uz.yalla.sipphone.data.jcef

import io.github.oshai.kotlinlogging.KotlinLogging
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.SwingUtilities

private val logger = KotlinLogging.logger {}

class JcefManager {
    private var cefApp: CefApp? = null
    private var cefClient: CefClient? = null
    private var browser: CefBrowser? = null
    private var bridgeRouter: BridgeRouter? = null
    private var eventEmitter: BridgeEventEmitter? = null
    private var isBrowserClosed = false

    val isInitialized: Boolean get() = cefApp != null

    fun initialize(debugPort: Int = 0) {
        if (cefApp != null) return
        logger.info { "Initializing JCEF..." }

        SwingUtilities.invokeAndWait {
            if (!CefApp.startup(emptyArray())) {
                throw IllegalStateException("CefApp.startup() failed")
            }

            val settings = CefSettings().apply {
                windowless_rendering_enabled = false
                log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
                if (debugPort > 0) {
                    remote_debugging_port = debugPort
                }
            }

            cefApp = CefApp.getInstance(settings)
            cefClient = cefApp!!.createClient()

            // Block popups
            cefClient!!.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: CefBrowser?, frame: CefFrame?,
                    target_url: String?, target_frame_name: String?
                ): Boolean = true // block all popups
            })

            logger.info { "JCEF initialized successfully" }
        }
    }

    fun createBrowser(url: String): CefBrowser {
        val client = cefClient ?: throw IllegalStateException("JCEF not initialized")
        isBrowserClosed = false

        SwingUtilities.invokeAndWait {
            browser = client.createBrowser(url, false, false)
        }

        logger.info { "Browser created for URL: $url" }
        return browser!!
    }

    fun setupBridge(
        router: BridgeRouter,
        emitter: BridgeEventEmitter,
    ) {
        bridgeRouter = router
        eventEmitter = emitter

        val client = cefClient ?: return
        router.install(client)

        // Inject bridge script on page load
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    logger.info { "Page loaded (status=$httpStatusCode), injecting bridge script" }
                    emitter.injectBridgeScript(browser)
                }
            }

            override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: org.cef.network.CefRequest.TransitionType?) {
                if (frame.isMain) {
                    logger.info { "Page load started, resetting handshake" }
                    emitter.resetHandshake()
                }
            }
        })
    }

    fun getBrowser(): CefBrowser? = browser

    fun isClosed(): Boolean = isBrowserClosed

    fun shutdown() {
        logger.info { "Shutting down JCEF..." }
        SwingUtilities.invokeAndWait {
            bridgeRouter?.let { router ->
                cefClient?.removeMessageRouter(router.getMessageRouter())
                router.dispose()
            }
            bridgeRouter = null

            browser?.let { b ->
                b.stopLoad()
                b.close(true)
                isBrowserClosed = true
            }
            browser = null

            cefClient?.dispose()
            cefClient = null

            cefApp?.dispose()
            cefApp = null
        }
        logger.info { "JCEF shutdown complete" }
    }
}
```

- [ ] **Step 2: Verify build**

```bash
./gradlew compileKotlin 2>&1 | tail -5
```

This will fail if JCEF classes aren't on classpath — that means Task 1 JBR setup needs fixing first.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(jcef): add JcefManager — CefApp/CefClient/CefBrowser lifecycle"
```

---

## Task 5: BridgeEventEmitter — Native → Web Events

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeEventEmitter.kt`

- [ ] **Step 1: Implement BridgeEventEmitter**

This class:
1. Injects the `window.YallaSIP` JavaScript object on page load
2. Emits events via `cefBrowser.executeJavaScript()`
3. Buffers events before handshake completes
4. Handles `ready()` command to complete handshake
5. Manages monotonic sequence counter

```kotlin
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
        browser.executeJavaScript(js, browser.url, 0)
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
        browser.executeJavaScript(js, browser.url, 0)
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
```

- [ ] **Step 2: Verify build**

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(bridge): add BridgeEventEmitter — native-to-web events with EventEmitter pattern"
```

---

## Task 6: BridgeRouter — Web → Native Commands

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeRouter.kt`

- [ ] **Step 1: Implement BridgeRouter**

This class handles all Web→Native commands via CefMessageRouter. Each command is dispatched to the appropriate SIP engine method.

```kotlin
// src/main/kotlin/uz/yalla/sipphone/data/jcef/BridgeRouter.kt
package uz.yalla.sipphone.data.jcef

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.misc.CefMessageRouter
import org.cef.misc.CefMessageRouterConfig
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.PhoneNumberValidator
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState

private val logger = KotlinLogging.logger {}

class BridgeRouter(
    private val callEngine: CallEngine,
    private val registrationEngine: RegistrationEngine,
    private val eventEmitter: BridgeEventEmitter,
    private val security: BridgeSecurity,
    private val auditLog: BridgeAuditLog,
    private val onAgentStatusChange: (AgentStatus) -> Unit,
) {
    private var messageRouter: CefMessageRouter? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(client: CefClient) {
        val config = CefMessageRouterConfig().apply {
            jsQueryFunction = "yallaSipQuery"
            jsCancelFunction = "yallaSipQueryCancel"
        }
        messageRouter = CefMessageRouter.create(config)
        messageRouter!!.addHandler(RouterHandler(), false)
        client.addMessageRouter(messageRouter!!)
        logger.info { "BridgeRouter installed" }
    }

    fun getMessageRouter(): CefMessageRouter? = messageRouter

    fun dispose() {
        messageRouter?.dispose()
        messageRouter = null
    }

    private inner class RouterHandler : CefMessageRouterHandlerAdapter() {
        override fun onQuery(
            browser: CefBrowser,
            frame: CefFrame,
            queryId: Long,
            request: String,
            persistent: Boolean,
            callback: CefQueryCallback,
        ): Boolean {
            scope.launch {
                try {
                    val cmd = bridgeJson.decodeFromString<BridgeCommand>(request)

                    // Rate limit check (skip for _ready)
                    if (cmd.command != "_ready" && !security.checkRateLimit(cmd.command)) {
                        val result = CommandResult.error("RATE_LIMITED", "Too many requests", true)
                        callback.success(bridgeJson.encodeToString(result))
                        return@launch
                    }

                    auditLog.logCommand(cmd.command, cmd.params, "processing")

                    val result = withTimeout(30_000) {
                        dispatch(cmd)
                    }

                    val json = bridgeJson.encodeToString(result)
                    auditLog.logCommand(cmd.command, cmd.params, if (result.success) "OK" else result.error?.code ?: "ERROR")
                    callback.success(json)
                } catch (e: Exception) {
                    logger.error(e) { "Bridge command failed: $request" }
                    val result = CommandResult.error("INTERNAL_ERROR", e.message ?: "Unknown error", false)
                    callback.success(bridgeJson.encodeToString(result))
                }
            }
            return true
        }
    }

    private suspend fun dispatch(cmd: BridgeCommand): CommandResult {
        return when (cmd.command) {
            "_ready" -> handleReady()
            "makeCall" -> handleMakeCall(cmd.params)
            "answer" -> handleAnswer(cmd.params)
            "reject" -> handleReject(cmd.params)
            "hangup" -> handleHangup(cmd.params)
            "setMute" -> handleSetMute(cmd.params)
            "setHold" -> handleSetHold(cmd.params)
            "setAgentStatus" -> handleSetAgentStatus(cmd.params)
            "getState" -> handleGetState()
            "getVersion" -> handleGetVersion()
            else -> CommandResult.error("INTERNAL_ERROR", "Unknown command: ${cmd.command}", false)
        }
    }

    private fun handleReady(): CommandResult {
        val initJson = eventEmitter.completeHandshake()
        logger.info { "Bridge handshake complete" }
        // Return init payload directly (not wrapped in CommandResult)
        return CommandResult(success = true, data = mapOf("_raw" to initJson))
    }

    private suspend fun handleMakeCall(params: Map<String, String>): CommandResult {
        val number = params["number"] ?: return CommandResult.error("INVALID_NUMBER", "Missing number", true)
        val validation = PhoneNumberValidator.validate(number)
        if (validation.isFailure) {
            return CommandResult.error("INVALID_NUMBER", validation.exceptionOrNull()?.message ?: "Invalid", true)
        }
        if (callEngine.callState.value !is CallState.Idle) {
            return CommandResult.error("ALREADY_IN_CALL", "Active call exists", false)
        }
        if (registrationEngine.registrationState.value !is RegistrationState.Registered) {
            return CommandResult.error("NOT_REGISTERED", "SIP not connected", false)
        }
        val result = callEngine.makeCall(validation.getOrThrow())
        return if (result.isSuccess) {
            val callId = (callEngine.callState.value as? CallState.Ringing)?.callId ?: "unknown"
            CommandResult.success(mapOf("callId" to callId))
        } else {
            CommandResult.error("INTERNAL_ERROR", result.exceptionOrNull()?.message ?: "Call failed", false)
        }
    }

    private suspend fun handleAnswer(params: Map<String, String>): CommandResult {
        if (callEngine.callState.value !is CallState.Ringing) {
            return CommandResult.error("NO_INCOMING_CALL", "No incoming call", false)
        }
        callEngine.answerCall()
        return CommandResult.success()
    }

    private suspend fun handleReject(params: Map<String, String>): CommandResult {
        if (callEngine.callState.value !is CallState.Ringing) {
            return CommandResult.error("NO_INCOMING_CALL", "No incoming call", false)
        }
        callEngine.hangupCall()
        return CommandResult.success()
    }

    private suspend fun handleHangup(params: Map<String, String>): CommandResult {
        val state = callEngine.callState.value
        if (state is CallState.Idle) {
            return CommandResult.error("NO_ACTIVE_CALL", "No call to hangup", false)
        }
        callEngine.hangupCall()
        return CommandResult.success()
    }

    private suspend fun handleSetMute(params: Map<String, String>): CommandResult {
        val callId = params["callId"] ?: return CommandResult.error("NO_ACTIVE_CALL", "Missing callId", false)
        val muted = params["muted"]?.toBooleanStrictOrNull() ?: return CommandResult.error("INTERNAL_ERROR", "Missing muted", false)
        callEngine.setMute(callId, muted)
        return CommandResult.success(mapOf("isMuted" to muted.toString()))
    }

    private suspend fun handleSetHold(params: Map<String, String>): CommandResult {
        val callId = params["callId"] ?: return CommandResult.error("NO_ACTIVE_CALL", "Missing callId", false)
        val onHold = params["onHold"]?.toBooleanStrictOrNull() ?: return CommandResult.error("INTERNAL_ERROR", "Missing onHold", false)
        callEngine.setHold(callId, onHold)
        return CommandResult.success(mapOf("isOnHold" to onHold.toString()))
    }

    private fun handleSetAgentStatus(params: Map<String, String>): CommandResult {
        val statusStr = params["status"] ?: return CommandResult.error("INTERNAL_ERROR", "Missing status", false)
        val status = AgentStatus.entries.find { it.name.equals(statusStr, ignoreCase = true) }
            ?: return CommandResult.error("INTERNAL_ERROR", "Invalid status: $statusStr", false)
        onAgentStatusChange(status)
        return CommandResult.success(mapOf("status" to status.name.lowercase()))
    }

    private fun handleGetState(): CommandResult {
        val callState = callEngine.callState.value
        val regState = registrationEngine.registrationState.value

        val connectionState = when (regState) {
            is RegistrationState.Registered -> "connected"
            is RegistrationState.Registering -> "reconnecting"
            else -> "disconnected"
        }

        val call = when (callState) {
            is CallState.Ringing -> BridgeCallState(
                callId = callState.callId, number = callState.callerNumber,
                direction = if (callState.isOutbound) "outbound" else "inbound",
                state = if (callState.isOutbound) "outgoing" else "incoming",
                isMuted = false, isOnHold = false, duration = 0,
            )
            is CallState.Active -> BridgeCallState(
                callId = callState.callId, number = callState.remoteNumber,
                direction = if (callState.isOutbound) "outbound" else "inbound",
                state = if (callState.isOnHold) "on_hold" else "active",
                isMuted = callState.isMuted, isOnHold = callState.isOnHold, duration = 0,
            )
            else -> null
        }

        val state = BridgeState(
            connection = BridgeConnectionState(state = connectionState, attempt = 0),
            agentStatus = "ready", // TODO: get from toolbar component
            call = call,
        )

        val json = bridgeJson.encodeToString(state)
        return CommandResult(success = true, data = mapOf("_raw" to json))
    }

    private fun handleGetVersion(): CommandResult {
        val info = BridgeVersionInfo(version = "1.0.0", capabilities = listOf("call", "agentStatus", "callQuality"))
        val json = bridgeJson.encodeToString(info)
        return CommandResult(success = true, data = mapOf("_raw" to json))
    }
}
```

- [ ] **Step 2: Verify build**

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(bridge): add BridgeRouter — Web-to-Native command dispatch via CefMessageRouter"
```

---

## Task 7: WebviewPanel — Compose Embedding + DI

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/feature/main/webview/WebviewPanel.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/di/WebviewModule.kt`

- [ ] **Step 1: Implement WebviewPanel**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/feature/main/webview/WebviewPanel.kt
package uz.yalla.sipphone.feature.main.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import uz.yalla.sipphone.data.jcef.JcefManager
import java.awt.Component

@Composable
fun WebviewPanel(
    jcefManager: JcefManager,
    dispatcherUrl: String,
    modifier: Modifier = Modifier,
) {
    val browser = remember(dispatcherUrl) {
        jcefManager.createBrowser(dispatcherUrl)
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            browser.uiComponent
        },
    )
}
```

- [ ] **Step 2: Implement WebviewModule**

```kotlin
// src/main/kotlin/uz/yalla/sipphone/di/WebviewModule.kt
package uz.yalla.sipphone.di

import org.koin.dsl.module
import uz.yalla.sipphone.data.jcef.BridgeAuditLog
import uz.yalla.sipphone.data.jcef.BridgeEventEmitter
import uz.yalla.sipphone.data.jcef.BridgeSecurity
import uz.yalla.sipphone.data.jcef.JcefManager

val webviewModule = module {
    single { JcefManager() }
    single { BridgeSecurity() }
    single { BridgeAuditLog() }
    single { BridgeEventEmitter(auditLog = get()) }
}
```

- [ ] **Step 3: Update AppModule**

Add `webviewModule` to `appModules` list.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(webview): add WebviewPanel composable and WebviewModule DI"
```

---

## Task 8: Integration — Wire Everything Together

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/main/MainComponent.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/main/MainScreen.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/Main.kt`

- [ ] **Step 1: Update MainComponent — add bridge event observation**

Add `JcefManager`, `BridgeEventEmitter`, `BridgeRouter`, and `BridgeSecurity` to MainComponent. Observe `callState` and `registrationState` changes and emit bridge events. Set up the bridge with router + emitter. Create browser with dispatcher URL.

Key additions:
- Observe `callEngine.callState` and emit appropriate bridge events (incomingCall, outgoingCall, callConnected, callEnded, callMuteChanged, callHoldChanged)
- Observe `registrationEngine.registrationState` and emit `connectionChanged`
- Observe `agentStatus` and emit `agentStatusChanged`
- Wire `BridgeRouter` with agent status callback to toolbar

- [ ] **Step 2: Update MainScreen — replace placeholder with WebviewPanel**

Replace `WebviewPlaceholder` with `WebviewPanel`. Pass `jcefManager` and `dispatcherUrl`.

- [ ] **Step 3: Update Main.kt**

- Initialize JCEF after Koin setup (before application block)
- Add DevTools port (9222 for debug)
- Add JCEF shutdown to close handler
- Move keyboard shortcuts to `CefKeyboardHandler` (intercept before Chromium consumes them)

- [ ] **Step 4: Run full build**

```bash
./gradlew build 2>&1 | tail -10
```

- [ ] **Step 5: Test with real dispatcher**

```bash
./gradlew run
```

Verify:
1. Login with "test123"
2. Main screen shows real dispatcher page at `http://192.168.0.234:5173/orders`
3. Toolbar still works
4. Browser DevTools accessible at `http://localhost:9222`
5. Open DevTools console → type `window.YallaSIP` → bridge object exists
6. `window.YallaSIP.getVersion().then(console.log)` → shows version info
7. `window.YallaSIP.getState().then(console.log)` → shows state

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(integration): wire JCEF webview + JS Bridge to MainComponent and SIP engine"
```

---

## Task 9: Bridge Call State Observation — Full Event Emission

**Files:**
- Modify: `src/main/kotlin/uz/yalla/sipphone/feature/main/MainComponent.kt`

- [ ] **Step 1: Add comprehensive call state observer**

In MainComponent's init, add a `callEngine.callState` collector that tracks previous state and emits the correct bridge event for each transition:

```
Idle → Ringing(inbound) → emitIncomingCall
Idle → Ringing(outbound) → emitOutgoingCall
Ringing → Active → emitCallConnected
Active(muted=false) → Active(muted=true) → emitCallMuteChanged
Active(onHold=false) → Active(onHold=true) → emitCallHoldChanged
Active/Ringing → Idle → emitCallEnded (with duration and reason)
```

Track `previousCallState` to calculate duration and determine reason.

- [ ] **Step 2: Add registration state observer for connectionChanged**

- [ ] **Step 3: Add agent status observer for agentStatusChanged**

- [ ] **Step 4: Add theme change emission**

When `onThemeToggle` is called, also call `eventEmitter.emitThemeChanged(if (isDark) "dark" else "light")`.

- [ ] **Step 5: Test with dispatcher**

Run app, make a call from toolbar, verify in browser DevTools console that events appear.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(bridge): add full call state observation and event emission to web"
```

---

## Task 10: Final Integration Test + Cleanup

- [ ] **Step 1: Run full build + tests**

```bash
./gradlew build 2>&1 | tail -10
```

- [ ] **Step 2: Manual smoke test**

1. Login → main screen → dispatcher loads in webview
2. Open DevTools (localhost:9222) → Console
3. `window.YallaSIP.getState()` → returns state
4. `window.YallaSIP.getVersion()` → returns version
5. Subscribe to events: `window.YallaSIP.on('incomingCall', (d) => console.log('CALL:', d))`
6. Make a call from toolbar → check console for events
7. Theme toggle → `themeChanged` event appears
8. Agent status change → `agentStatusChanged` event appears
9. Logout → login window returns to small size

- [ ] **Step 3: Delete WebviewPlaceholder**

Remove `src/main/kotlin/uz/yalla/sipphone/feature/main/placeholder/WebviewPlaceholder.kt` — no longer needed.

- [ ] **Step 4: Update VISION.md**

Add Session 2 completion entry.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(session2): complete JCEF webview + JS Bridge integration

- JCEF Chromium browser embedded via SwingPanel
- Full JS Bridge API (12 events, 8 commands, 2 queries)
- BridgeRouter with CefMessageRouter for Web→Native
- BridgeEventEmitter for Native→Web
- BridgeSecurity rate limiting + BridgeAuditLog PII masking
- DevTools on port 9222 (debug builds)
- Keyboard shortcuts via CefKeyboardHandler"
```

---

## Summary

| Task | Description | Risk |
|------|-------------|------|
| 1 | JBR-JCEF setup (download + gradle config) | **HIGH** — dependency resolution |
| 2 | BridgeProtocol JSON models | Low |
| 3 | BridgeSecurity + AuditLog | Low |
| 4 | JcefManager lifecycle | **HIGH** — JCEF native integration |
| 5 | BridgeEventEmitter (Native→Web) | Medium |
| 6 | BridgeRouter (Web→Native) | Medium |
| 7 | WebviewPanel + DI | Medium |
| 8 | Integration — wire everything | **HIGH** — multi-component |
| 9 | Full event observation | Medium |
| 10 | Final test + cleanup | Low |
