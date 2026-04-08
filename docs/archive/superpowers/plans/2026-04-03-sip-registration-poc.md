# SIP Registration PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Compose Desktop app that registers with an Oktell SIP server over UDP and displays connection status.

**Architecture:** Single-screen app with 4 states (Idle/Registering/Registered/Failed). SIP layer handles raw UDP REGISTER messages with Digest Authentication. UI uses Material 3 with professional blue theme.

**Tech Stack:** Kotlin 2.1.20, Compose Desktop 1.7.3, Material 3, kotlinx-coroutines 1.10.1, JDK DatagramSocket + MessageDigest.

**Project:** `/Users/macbookpro/Ildam/yalla/yalla-sip-phone/`

**Design Spec:** `docs/superpowers/specs/2026-04-03-sip-registration-poc-design.md`

---

## File Map

| File | Responsibility | Task |
|------|---------------|------|
| `domain/SipCredentials.kt` | Credentials data model | 1 |
| `domain/ConnectionState.kt` | UI state sealed class | 1 |
| `sip/DigestAuth.kt` | MD5 Digest Authentication | 2 |
| `sip/SipMessage.kt` | SIP REGISTER builder + response parser | 3 |
| `sip/SipTransport.kt` | UDP socket wrapper with coroutines | 4 |
| `sip/SipClient.kt` | REGISTER flow orchestration + state machine | 5 |
| `ui/theme/Theme.kt` | Material 3 color scheme | 6 |
| `ui/component/SipCredentialsForm.kt` | Form with 4 fields + validation | 7 |
| `ui/component/ConnectionStatusCard.kt` | Status display card | 7 |
| `ui/component/ConnectButton.kt` | Button with loading state | 7 |
| `ui/screen/MainScreen.kt` | Screen layout, state → UI | 8 |
| `App.kt` | Root composable, SipClient wiring | 9 |
| `Main.kt` | Window, theme, lifecycle cleanup | 9 |

| Test File | Tests For | Task |
|-----------|-----------|------|
| `test/.../sip/DigestAuthTest.kt` | MD5, challenge parsing, auth header | 2 |
| `test/.../sip/SipMessageTest.kt` | REGISTER building, response parsing | 3 |
| `test/.../sip/SipClientTest.kt` | Full registration flow with mock UDP | 5 |

---

### Task 1: Domain Models

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/SipCredentials.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/domain/ConnectionState.kt`

- [ ] **Step 1: Create SipCredentials**

```kotlin
package uz.yalla.sipphone.domain

data class SipCredentials(
    val server: String,
    val port: Int = 5060,
    val username: String,
    val password: String
)
```

- [ ] **Step 2: Create ConnectionState**

```kotlin
package uz.yalla.sipphone.domain

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Registering : ConnectionState()
    data class Registered(
        val server: String,
        val expiresIn: Int
    ) : ConnectionState()
    data class Failed(
        val message: String,
        val isRetryable: Boolean
    ) : ConnectionState()
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/domain/
git commit -m "feat(domain): add SipCredentials and ConnectionState models

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Digest Authentication (TDD)

**Files:**
- Create: `src/test/kotlin/uz/yalla/sipphone/sip/DigestAuthTest.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/sip/DigestAuth.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package uz.yalla.sipphone.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DigestAuthTest {

    @Test
    fun `md5Hex produces correct hash`() {
        // Known MD5: md5("") = d41d8cd98f00b204e9800998ecf8427e
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", DigestAuth.md5Hex(""))
        // md5("hello") = 5d41402abc4b2a76b9719d911017c592
        assertEquals("5d41402abc4b2a76b9719d911017c592", DigestAuth.md5Hex("hello"))
    }

    @Test
    fun `computeResponse without qop`() {
        // HA1 = MD5(user:realm:pass)
        // HA2 = MD5(REGISTER:sip:server)
        // response = MD5(HA1:nonce:HA2)
        val ha1 = DigestAuth.md5Hex("102:oktell:1234qwerQQ")
        val ha2 = DigestAuth.md5Hex("REGISTER:sip:192.168.0.22")
        val expected = DigestAuth.md5Hex("$ha1:testnonce123:$ha2")

        val result = DigestAuth.computeResponse(
            username = "102",
            realm = "oktell",
            password = "1234qwerQQ",
            nonce = "testnonce123",
            method = "REGISTER",
            uri = "sip:192.168.0.22"
        )
        assertEquals(expected, result)
    }

    @Test
    fun `computeResponse with qop auth`() {
        val ha1 = DigestAuth.md5Hex("102:oktell:1234qwerQQ")
        val ha2 = DigestAuth.md5Hex("REGISTER:sip:192.168.0.22")
        val expected = DigestAuth.md5Hex("$ha1:testnonce123:00000001:abc123:auth:$ha2")

        val result = DigestAuth.computeResponse(
            username = "102",
            realm = "oktell",
            password = "1234qwerQQ",
            nonce = "testnonce123",
            method = "REGISTER",
            uri = "sip:192.168.0.22",
            qop = "auth",
            nc = "00000001",
            cnonce = "abc123"
        )
        assertEquals(expected, result)
    }

    @Test
    fun `parseChallenge extracts all fields`() {
        val header = """Digest realm="oktell.local", nonce="abc123def", algorithm=MD5, qop="auth", opaque="xyz789""""
        val challenge = DigestAuth.parseChallenge(header)

        assertEquals("oktell.local", challenge.realm)
        assertEquals("abc123def", challenge.nonce)
        assertEquals("MD5", challenge.algorithm)
        assertEquals("auth", challenge.qop)
        assertEquals("xyz789", challenge.opaque)
    }

    @Test
    fun `parseChallenge handles minimal challenge`() {
        val header = """Digest realm="192.168.0.22", nonce="abcdef""""
        val challenge = DigestAuth.parseChallenge(header)

        assertEquals("192.168.0.22", challenge.realm)
        assertEquals("abcdef", challenge.nonce)
        assertEquals("MD5", challenge.algorithm)
        assertEquals(null, challenge.qop)
        assertEquals(null, challenge.opaque)
    }

    @Test
    fun `buildAuthorizationHeader formats correctly`() {
        val challenge = DigestChallenge(
            realm = "oktell",
            nonce = "testnonce",
            qop = "auth",
            opaque = "testopaque"
        )
        val header = DigestAuth.buildAuthorizationHeader(
            username = "102",
            challenge = challenge,
            method = "REGISTER",
            uri = "sip:192.168.0.22",
            response = "abcdef123456",
            nc = "00000001",
            cnonce = "mycnonce"
        )

        assert(header.startsWith("Digest "))
        assert("username=\"102\"" in header)
        assert("realm=\"oktell\"" in header)
        assert("nonce=\"testnonce\"" in header)
        assert("uri=\"sip:192.168.0.22\"" in header)
        assert("response=\"abcdef123456\"" in header)
        assert("algorithm=MD5" in header)
        assert("qop=auth" in header)
        assert("nc=00000001" in header)
        assert("cnonce=\"mycnonce\"" in header)
        assert("opaque=\"testopaque\"" in header)
    }

    @Test
    fun `buildAuthorizationHeader without qop`() {
        val challenge = DigestChallenge(realm = "oktell", nonce = "testnonce")
        val header = DigestAuth.buildAuthorizationHeader(
            username = "102",
            challenge = challenge,
            method = "REGISTER",
            uri = "sip:192.168.0.22",
            response = "abcdef123456"
        )

        assert("qop" !in header)
        assert("nc" !in header)
        assert("cnonce" !in header)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`
Expected: FAIL - `DigestAuth` not found

- [ ] **Step 3: Implement DigestAuth**

```kotlin
package uz.yalla.sipphone.sip

import java.security.MessageDigest

data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val algorithm: String = "MD5",
    val qop: String? = null,
    val opaque: String? = null
)

object DigestAuth {

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun computeResponse(
        username: String,
        realm: String,
        password: String,
        nonce: String,
        method: String,
        uri: String,
        qop: String? = null,
        nc: String? = null,
        cnonce: String? = null
    ): String {
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")
        return if (qop == "auth" && nc != null && cnonce != null) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }
    }

    fun parseChallenge(wwwAuthenticate: String): DigestChallenge {
        val body = wwwAuthenticate.removePrefix("Digest ").trim()
        val params = mutableMapOf<String, String>()
        // Parse comma-separated key=value pairs, respecting quoted values
        var remaining = body
        while (remaining.isNotBlank()) {
            val eqIndex = remaining.indexOf('=')
            if (eqIndex < 0) break
            val key = remaining.substring(0, eqIndex).trim()
            remaining = remaining.substring(eqIndex + 1).trim()

            val value: String
            if (remaining.startsWith("\"")) {
                val closeQuote = remaining.indexOf('"', 1)
                value = remaining.substring(1, closeQuote)
                remaining = remaining.substring(closeQuote + 1).trimStart(',').trim()
            } else {
                val commaIndex = remaining.indexOf(',')
                if (commaIndex >= 0) {
                    value = remaining.substring(0, commaIndex).trim()
                    remaining = remaining.substring(commaIndex + 1).trim()
                } else {
                    value = remaining.trim()
                    remaining = ""
                }
            }
            params[key] = value
        }

        return DigestChallenge(
            realm = params["realm"] ?: error("Missing realm in WWW-Authenticate"),
            nonce = params["nonce"] ?: error("Missing nonce in WWW-Authenticate"),
            algorithm = params["algorithm"] ?: "MD5",
            qop = params["qop"],
            opaque = params["opaque"]
        )
    }

    fun buildAuthorizationHeader(
        username: String,
        challenge: DigestChallenge,
        method: String,
        uri: String,
        response: String,
        nc: String? = null,
        cnonce: String? = null
    ): String = buildString {
        append("Digest username=\"$username\"")
        append(", realm=\"${challenge.realm}\"")
        append(", nonce=\"${challenge.nonce}\"")
        append(", uri=\"$uri\"")
        append(", response=\"$response\"")
        append(", algorithm=${challenge.algorithm}")
        challenge.opaque?.let { append(", opaque=\"$it\"") }
        if (challenge.qop != null && nc != null && cnonce != null) {
            append(", qop=${challenge.qop}")
            append(", nc=$nc")
            append(", cnonce=\"$cnonce\"")
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`
Expected: ALL PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/sip/DigestAuth.kt src/test/kotlin/uz/yalla/sipphone/sip/DigestAuthTest.kt
git commit -m "feat(sip): add Digest Authentication with MD5

Implements RFC 2617 Digest Auth: challenge parsing, response
computation (with/without qop), and Authorization header building.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: SIP Message Builder & Parser (TDD)

**Files:**
- Create: `src/test/kotlin/uz/yalla/sipphone/sip/SipMessageTest.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/sip/SipMessage.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package uz.yalla.sipphone.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SipMessageTest {

    @Test
    fun `buildRegister creates valid SIP message`() {
        val builder = SipMessageBuilder(
            server = "192.168.0.22",
            username = "102",
            localIp = "192.168.0.100",
            localPort = 5060
        )
        val message = builder.buildRegister()

        assertTrue(message.startsWith("REGISTER sip:192.168.0.22 SIP/2.0\r\n"))
        assertTrue("Via: SIP/2.0/UDP 192.168.0.100:5060;" in message)
        assertTrue("branch=z9hG4bK" in message)
        assertTrue(";rport\r\n" in message)
        assertTrue("Max-Forwards: 70\r\n" in message)
        assertTrue("From: <sip:102@192.168.0.22>;tag=" in message)
        assertTrue("To: <sip:102@192.168.0.22>\r\n" in message)
        assertTrue("Call-ID: " in message)
        assertTrue("CSeq: 1 REGISTER\r\n" in message)
        assertTrue("Contact: <sip:102@192.168.0.100:5060;transport=udp>\r\n" in message)
        assertTrue("Expires: 3600\r\n" in message)
        assertTrue("User-Agent: YallaSipPhone/1.0\r\n" in message)
        assertTrue("Content-Length: 0\r\n" in message)
        assertTrue(message.endsWith("\r\n\r\n"))
    }

    @Test
    fun `buildRegister increments CSeq`() {
        val builder = SipMessageBuilder("s", "u", "1.2.3.4", 5060)
        val msg1 = builder.buildRegister()
        val msg2 = builder.buildRegister()

        assertTrue("CSeq: 1 REGISTER" in msg1)
        assertTrue("CSeq: 2 REGISTER" in msg2)
    }

    @Test
    fun `buildRegister keeps same Call-ID across calls`() {
        val builder = SipMessageBuilder("s", "u", "1.2.3.4", 5060)
        val msg1 = builder.buildRegister()
        val msg2 = builder.buildRegister()

        val callId1 = extractHeader(msg1, "Call-ID")
        val callId2 = extractHeader(msg2, "Call-ID")
        assertEquals(callId1, callId2)
    }

    @Test
    fun `buildRegister generates unique branch per call`() {
        val builder = SipMessageBuilder("s", "u", "1.2.3.4", 5060)
        val msg1 = builder.buildRegister()
        val msg2 = builder.buildRegister()

        val via1 = extractHeader(msg1, "Via")!!
        val via2 = extractHeader(msg2, "Via")!!
        val branch1 = Regex("""branch=(z9hG4bK\w+)""").find(via1)!!.groupValues[1]
        val branch2 = Regex("""branch=(z9hG4bK\w+)""").find(via2)!!.groupValues[1]
        assertTrue(branch1 != branch2, "Branches must be unique per request")
    }

    @Test
    fun `buildRegister includes authorization when provided`() {
        val builder = SipMessageBuilder("s", "u", "1.2.3.4", 5060)
        val msg = builder.buildRegister(authorization = "Digest username=\"test\"")

        assertTrue("Authorization: Digest username=\"test\"\r\n" in msg)
    }

    @Test
    fun `buildRegister with custom expires`() {
        val builder = SipMessageBuilder("s", "u", "1.2.3.4", 5060)
        val msg = builder.buildRegister(expires = 0)

        assertTrue("Expires: 0\r\n" in msg)
    }

    @Test
    fun `parseResponse extracts status code and reason`() {
        val raw = "SIP/2.0 200 OK\r\nContent-Length: 0\r\n\r\n"
        val response = SipMessageBuilder.parseResponse(raw)

        assertEquals(200, response.statusCode)
        assertEquals("OK", response.reasonPhrase)
    }

    @Test
    fun `parseResponse extracts headers case-insensitively`() {
        val raw = "SIP/2.0 401 Unauthorized\r\n" +
                "WWW-Authenticate: Digest realm=\"test\", nonce=\"abc\"\r\n" +
                "Content-Length: 0\r\n\r\n"
        val response = SipMessageBuilder.parseResponse(raw)

        assertEquals(401, response.statusCode)
        assertNotNull(response.header("www-authenticate"))
        assertNotNull(response.header("WWW-Authenticate"))
    }

    @Test
    fun `parseResponse extracts Contact expires`() {
        val raw = "SIP/2.0 200 OK\r\n" +
                "Contact: <sip:102@192.168.0.100:5060>;expires=1800\r\n" +
                "Expires: 3600\r\n\r\n"
        val response = SipMessageBuilder.parseResponse(raw)

        assertEquals("<sip:102@192.168.0.100:5060>;expires=1800", response.header("contact"))
    }

    @Test
    fun `parseResponse handles 403 Forbidden`() {
        val raw = "SIP/2.0 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
        val response = SipMessageBuilder.parseResponse(raw)

        assertEquals(403, response.statusCode)
        assertEquals("Forbidden", response.reasonPhrase)
    }

    private fun extractHeader(message: String, name: String): String? {
        return message.split("\r\n")
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`
Expected: FAIL - `SipMessageBuilder` not found

- [ ] **Step 3: Implement SipMessage**

```kotlin
package uz.yalla.sipphone.sip

import java.util.UUID

data class SipResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    private val headers: Map<String, String>
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

class SipMessageBuilder(
    private val server: String,
    private val username: String,
    private val localIp: String,
    private val localPort: Int
) {
    private var cseq = 0
    private val callId = "${UUID.randomUUID()}@$localIp"
    private val fromTag = UUID.randomUUID().toString().take(8)

    fun buildRegister(
        expires: Int = 3600,
        authorization: String? = null
    ): String {
        cseq++
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"

        return buildString {
            append("REGISTER sip:$server SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:$localPort;branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <sip:$username@$server>;tag=$fromTag\r\n")
            append("To: <sip:$username@$server>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseq REGISTER\r\n")
            append("Contact: <sip:$username@$localIp:$localPort;transport=udp>\r\n")
            append("Expires: $expires\r\n")
            authorization?.let { append("Authorization: $it\r\n") }
            append("User-Agent: YallaSipPhone/1.0\r\n")
            append("Content-Length: 0\r\n")
            append("\r\n")
        }
    }

    companion object {
        fun parseResponse(raw: String): SipResponse {
            val lines = raw.split("\r\n")
            val statusLine = lines.first()
            val parts = statusLine.split(" ", limit = 3)
            val statusCode = parts[1].toInt()
            val reasonPhrase = parts.getOrElse(2) { "" }

            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) break
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim().lowercase()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }

            return SipResponse(statusCode, reasonPhrase, headers)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`
Expected: ALL PASS (10 tests from SipMessageTest)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/sip/SipMessage.kt src/test/kotlin/uz/yalla/sipphone/sip/SipMessageTest.kt
git commit -m "feat(sip): add SIP message builder and response parser

Builds REGISTER requests with proper CRLF, branch magic cookie,
CSeq incrementing, and Call-ID persistence. Parses SIP responses
with case-insensitive header lookup.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: SIP Transport (UDP Socket)

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/sip/SipTransport.kt`

- [ ] **Step 1: Implement SipTransport**

```kotlin
package uz.yalla.sipphone.sip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class SipTransport {
    private var socket: DatagramSocket? = null
    private var _localAddress: String = "127.0.0.1"

    val localPort: Int get() = socket?.localPort ?: 0
    val localAddress: String get() = _localAddress

    suspend fun open(port: Int = 0, targetHost: String = "8.8.8.8") = withContext(Dispatchers.IO) {
        close()
        socket = DatagramSocket(port)
        _localAddress = resolveLocalAddress(targetHost)
    }

    suspend fun send(message: String, host: String, port: Int) = withContext(Dispatchers.IO) {
        val data = message.toByteArray(Charsets.UTF_8)
        val address = InetAddress.getByName(host)
        val packet = DatagramPacket(data, data.size, address, port)
        socket?.send(packet) ?: error("Socket not opened. Call open() first.")
    }

    suspend fun receive(timeoutMs: Long = 5000): String? = withContext(Dispatchers.IO) {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        socket?.let { sock ->
            sock.soTimeout = timeoutMs.toInt()
            try {
                sock.receive(packet)
                String(packet.data, 0, packet.length, Charsets.UTF_8)
            } catch (_: SocketTimeoutException) {
                null
            }
        }
    }

    fun close() {
        socket?.close()
        socket = null
    }

    private fun resolveLocalAddress(targetHost: String): String = try {
        DatagramSocket().use { probe ->
            probe.connect(InetAddress.getByName(targetHost), 5060)
            probe.localAddress.hostAddress
        }
    } catch (_: Exception) {
        InetAddress.getLocalHost().hostAddress
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/sip/SipTransport.kt
git commit -m "feat(sip): add UDP transport with coroutine support

Wraps DatagramSocket with suspend functions, auto-resolves local
LAN address for SIP headers, handles timeouts gracefully.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: SIP Client (TDD)

**Files:**
- Create: `src/test/kotlin/uz/yalla/sipphone/sip/SipClientTest.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/sip/SipClient.kt`

- [ ] **Step 1: Write failing test with mock UDP server**

```kotlin
package uz.yalla.sipphone.sip

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import uz.yalla.sipphone.domain.ConnectionState
import uz.yalla.sipphone.domain.SipCredentials
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SipClientTest {

    private val testCredentials = SipCredentials(
        server = "127.0.0.1",
        port = 0,  // assigned by mock server
        username = "testuser",
        password = "testpass"
    )

    @Test
    fun `successful registration with 401 challenge`() = runTest {
        val mockServer = MockSipServer()
        mockServer.start()

        val transport = SipTransport()
        val client = SipClient(transport)

        val credentials = testCredentials.copy(port = mockServer.port)

        val job = launch { client.register(credentials) }

        // Wait for Registered state
        client.state.first { it is ConnectionState.Registered }

        val state = client.state.value
        assertIs<ConnectionState.Registered>(state)
        assertEquals("127.0.0.1:${mockServer.port}", state.server)

        job.cancel()
        client.close()
        mockServer.stop()
    }

    @Test
    fun `failed registration with 403`() = runTest {
        val mockServer = MockSipServer(rejectAuth = true)
        mockServer.start()

        val transport = SipTransport()
        val client = SipClient(transport)
        val credentials = testCredentials.copy(port = mockServer.port)

        val job = launch { client.register(credentials) }

        client.state.first { it is ConnectionState.Failed }

        val state = client.state.value
        assertIs<ConnectionState.Failed>(state)
        assert("Authentication failed" in state.message)

        job.cancel()
        client.close()
        mockServer.stop()
    }

    @Test
    fun `timeout when server unreachable`() = runTest {
        val transport = SipTransport()
        val client = SipClient(transport)

        // Use a port that nothing is listening on
        val credentials = testCredentials.copy(port = 19876)

        val job = launch { client.register(credentials) }

        client.state.first { it is ConnectionState.Failed }

        val state = client.state.value
        assertIs<ConnectionState.Failed>(state)
        assert("unreachable" in state.message.lowercase())

        job.cancel()
        client.close()
    }
}

/**
 * Simple mock SIP server for testing.
 * Responds to first REGISTER with 401, second (with auth) with 200 OK.
 */
class MockSipServer(private val rejectAuth: Boolean = false) {
    private var socket: DatagramSocket? = null
    val port: Int get() = socket?.localPort ?: 0
    private var running = false

    fun start() {
        socket = DatagramSocket(0) // random port
        socket!!.soTimeout = 10000
        running = true
        Thread {
            val buf = ByteArray(4096)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket?.receive(packet)
                    val request = String(packet.data, 0, packet.length)

                    val response = if ("Authorization:" !in request) {
                        // First REGISTER - send 401
                        buildString {
                            append("SIP/2.0 401 Unauthorized\r\n")
                            append("WWW-Authenticate: Digest realm=\"mock\", nonce=\"mocknonce123\"\r\n")
                            append("Content-Length: 0\r\n")
                            append("\r\n")
                        }
                    } else if (rejectAuth) {
                        "SIP/2.0 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
                    } else {
                        buildString {
                            append("SIP/2.0 200 OK\r\n")
                            append("Expires: 3600\r\n")
                            append("Content-Length: 0\r\n")
                            append("\r\n")
                        }
                    }

                    val responseBytes = response.toByteArray()
                    socket?.send(
                        DatagramPacket(
                            responseBytes, responseBytes.size,
                            packet.address, packet.port
                        )
                    )
                } catch (_: Exception) {
                    // timeout or closed
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        socket?.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test --tests "*.SipClientTest"`
Expected: FAIL - `SipClient` not found

- [ ] **Step 3: Implement SipClient**

```kotlin
package uz.yalla.sipphone.sip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uz.yalla.sipphone.domain.ConnectionState
import uz.yalla.sipphone.domain.SipCredentials
import java.util.UUID

class SipClient(private val transport: SipTransport) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var messageBuilder: SipMessageBuilder? = null
    private var lastCredentials: SipCredentials? = null

    private val retryDelays = listOf(500L, 1000L, 2000L)

    suspend fun register(credentials: SipCredentials) {
        lastCredentials = credentials
        _state.value = ConnectionState.Registering

        try {
            transport.open(targetHost = credentials.server)

            messageBuilder = SipMessageBuilder(
                server = credentials.server,
                username = credentials.username,
                localIp = transport.localAddress,
                localPort = transport.localPort
            )

            // Phase 1: Send initial REGISTER (expect 401 challenge)
            val initialMessage = messageBuilder!!.buildRegister()
            val rawResponse = sendWithRetry(initialMessage, credentials.server, credentials.port)
                ?: return fail("Server unreachable: check address and port")

            val response = SipMessageBuilder.parseResponse(rawResponse)

            when (response.statusCode) {
                200 -> succeed(credentials, response)
                401 -> {
                    val wwwAuth = response.header("www-authenticate")
                        ?: return fail("Server sent 401 without authentication challenge")
                    val challenge = DigestAuth.parseChallenge(wwwAuth)
                    authenticate(credentials, challenge)
                }
                403 -> fail("Authentication failed: check username and password")
                else -> fail("Server error: ${response.statusCode} ${response.reasonPhrase}")
            }
        } catch (e: CancellationException) {
            _state.value = ConnectionState.Idle
            throw e
        } catch (e: Exception) {
            fail("Network error: ${e.message}")
        }
    }

    private suspend fun authenticate(credentials: SipCredentials, challenge: DigestChallenge) {
        val uri = "sip:${credentials.server}"
        val nc = "00000001"
        val cnonce = UUID.randomUUID().toString().take(8)

        val digestResponse = DigestAuth.computeResponse(
            username = credentials.username,
            realm = challenge.realm,
            password = credentials.password,
            nonce = challenge.nonce,
            method = "REGISTER",
            uri = uri,
            qop = challenge.qop,
            nc = if (challenge.qop != null) nc else null,
            cnonce = if (challenge.qop != null) cnonce else null
        )

        val authHeader = DigestAuth.buildAuthorizationHeader(
            username = credentials.username,
            challenge = challenge,
            method = "REGISTER",
            uri = uri,
            response = digestResponse,
            nc = if (challenge.qop != null) nc else null,
            cnonce = if (challenge.qop != null) cnonce else null
        )

        val authMessage = messageBuilder!!.buildRegister(authorization = authHeader)
        val rawResponse = sendWithRetry(authMessage, credentials.server, credentials.port)
            ?: return fail("Server unreachable after authentication")

        val response = SipMessageBuilder.parseResponse(rawResponse)

        when (response.statusCode) {
            200 -> succeed(credentials, response)
            403 -> fail("Authentication failed: check username and password")
            423 -> handleIntervalTooBrief(credentials, response, authHeader)
            else -> fail("Registration failed: ${response.statusCode} ${response.reasonPhrase}")
        }
    }

    private suspend fun handleIntervalTooBrief(
        credentials: SipCredentials,
        response: SipResponse,
        authHeader: String
    ) {
        val minExpires = response.header("min-expires")?.toIntOrNull() ?: 60
        val retryMessage = messageBuilder!!.buildRegister(
            expires = minExpires,
            authorization = authHeader
        )
        val rawResponse = sendWithRetry(retryMessage, credentials.server, credentials.port)
            ?: return fail("Server unreachable")

        val retryResponse = SipMessageBuilder.parseResponse(rawResponse)
        if (retryResponse.statusCode == 200) {
            succeed(credentials, retryResponse)
        } else {
            fail("Registration failed: ${retryResponse.statusCode}")
        }
    }

    suspend fun unregister() {
        val credentials = lastCredentials ?: return
        val builder = messageBuilder ?: return

        try {
            val message = builder.buildRegister(expires = 0)
            sendWithRetry(message, credentials.server, credentials.port)
        } catch (_: Exception) {
            // Best effort - server cleans up on Expires timeout
        } finally {
            _state.value = ConnectionState.Idle
        }
    }

    fun close() {
        transport.close()
        messageBuilder = null
    }

    private suspend fun sendWithRetry(message: String, host: String, port: Int): String? {
        for ((index, retryDelay) in retryDelays.withIndex()) {
            transport.send(message, host, port)
            val response = transport.receive(timeoutMs = 5000)
            if (response != null) return response
            if (index < retryDelays.lastIndex) {
                delay(retryDelay)
            }
        }
        return null
    }

    private fun succeed(credentials: SipCredentials, response: SipResponse) {
        val expires = parseExpires(response)
        _state.value = ConnectionState.Registered(
            server = "${credentials.server}:${credentials.port}",
            expiresIn = expires
        )
    }

    private fun fail(message: String) {
        _state.value = ConnectionState.Failed(message = message, isRetryable = true)
    }

    private fun parseExpires(response: SipResponse): Int {
        response.header("contact")?.let { contact ->
            Regex("""expires=(\d+)""").find(contact)?.let {
                return it.groupValues[1].toInt()
            }
        }
        return response.header("expires")?.toIntOrNull() ?: 3600
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`
Expected: ALL PASS (including SipClientTest - 3 tests)

Note: The timeout test may take ~15s due to retry delays. This is expected.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/sip/SipClient.kt src/test/kotlin/uz/yalla/sipphone/sip/SipClientTest.kt
git commit -m "feat(sip): add SipClient with REGISTER flow and mock tests

Orchestrates REGISTER → 401 → Auth → 200 OK flow with 3x retry
(500ms/1s/2s). Handles 403, 423, timeouts. Includes MockSipServer
for integration testing.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Material 3 Theme

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/theme/Theme.kt`

- [ ] **Step 1: Create theme**

```kotlin
package uz.yalla.sipphone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A5276),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E6F1),
    onPrimaryContainer = Color(0xFF0A2A3F),
    secondary = Color(0xFF455A64),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFD8DC),
    onSecondaryContainer = Color(0xFF1C313A),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    outline = Color(0xFF79747E),
)

// Custom semantic colors for connection status
val SuccessContainer = Color(0xFFD4EDDA)
val OnSuccessContainer = Color(0xFF155724)

@Composable
fun YallaSipPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/ui/theme/Theme.kt
git commit -m "feat(ui): add Material 3 theme with professional blue scheme

Light-only theme with seed #1A5276. Custom success colors for
connection status card.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: UI Components

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/component/SipCredentialsForm.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/component/ConnectionStatusCard.kt`
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/component/ConnectButton.kt`

- [ ] **Step 1: Create SipCredentialsForm**

```kotlin
package uz.yalla.sipphone.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

data class FormState(
    val server: String = "",
    val port: String = "5060",
    val username: String = "",
    val password: String = ""
)

data class FormErrors(
    val server: String? = null,
    val port: String? = null,
    val username: String? = null,
    val password: String? = null
) {
    val hasErrors: Boolean get() = listOfNotNull(server, port, username, password).isNotEmpty()
}

fun validateForm(state: FormState): FormErrors = FormErrors(
    server = if (state.server.isBlank()) "Server address is required" else null,
    port = when {
        state.port.isBlank() -> "Port is required"
        state.port.toIntOrNull()?.let { it !in 1..65535 } == true -> "Port must be 1-65535"
        else -> null
    },
    username = if (state.username.isBlank()) "Username is required" else null,
    password = if (state.password.isBlank()) "Password is required" else null
)

@Composable
fun SipCredentialsForm(
    formState: FormState,
    errors: FormErrors,
    enabled: Boolean,
    onFormChange: (FormState) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val submitOnEnter = Modifier.onKeyEvent { event ->
        if (event.key == Key.Enter && enabled) {
            onSubmit()
            true
        } else false
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = formState.server,
            onValueChange = { onFormChange(formState.copy(server = it)) },
            label = { Text("SIP Server") },
            placeholder = { Text("192.168.0.22") },
            leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
            isError = errors.server != null,
            supportingText = errors.server?.let { { Text(it) } },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().then(submitOnEnter)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = formState.port,
                onValueChange = { newValue ->
                    if (newValue.all { it.isDigit() } && newValue.length <= 5) {
                        onFormChange(formState.copy(port = newValue))
                    }
                },
                label = { Text("Port") },
                leadingIcon = { Icon(Icons.Filled.Tag, contentDescription = null) },
                isError = errors.port != null,
                supportingText = errors.port?.let { { Text(it) } },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(160.dp).then(submitOnEnter)
            )
        }

        OutlinedTextField(
            value = formState.username,
            onValueChange = { onFormChange(formState.copy(username = it)) },
            label = { Text("Username") },
            placeholder = { Text("102") },
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            isError = errors.username != null,
            supportingText = errors.username?.let { { Text(it) } },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().then(submitOnEnter)
        )

        var passwordVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = formState.password,
            onValueChange = { onFormChange(formState.copy(password = it)) },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                        else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            isError = errors.password != null,
            supportingText = errors.password?.let { { Text(it) } },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().then(submitOnEnter)
        )
    }
}
```

- [ ] **Step 2: Create ConnectionStatusCard**

```kotlin
package uz.yalla.sipphone.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.ConnectionState
import uz.yalla.sipphone.ui.theme.OnSuccessContainer
import uz.yalla.sipphone.ui.theme.SuccessContainer

@Composable
fun ConnectionStatusCard(
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    val visible = state !is ConnectionState.Idle

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = tween(300)
        ),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        modifier = modifier
    ) {
        val containerColor by animateColorAsState(
            targetValue = when (state) {
                is ConnectionState.Registering -> MaterialTheme.colorScheme.secondaryContainer
                is ConnectionState.Registered -> SuccessContainer
                is ConnectionState.Failed -> MaterialTheme.colorScheme.errorContainer
                is ConnectionState.Idle -> Color.Transparent
            },
            animationSpec = tween(300)
        )
        val contentColor by animateColorAsState(
            targetValue = when (state) {
                is ConnectionState.Registering -> MaterialTheme.colorScheme.onSecondaryContainer
                is ConnectionState.Registered -> OnSuccessContainer
                is ConnectionState.Failed -> MaterialTheme.colorScheme.onErrorContainer
                is ConnectionState.Idle -> Color.Transparent
            },
            animationSpec = tween(300)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = containerColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state) {
                    is ConnectionState.Registering -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = contentColor
                        )
                    }
                    is ConnectionState.Registered -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = contentColor
                        )
                    }
                    is ConnectionState.Failed -> {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = null,
                            tint = contentColor
                        )
                    }
                    is ConnectionState.Idle -> {}
                }

                Column {
                    Text(
                        text = when (state) {
                            is ConnectionState.Registering -> "Registering..."
                            is ConnectionState.Registered -> "Registered"
                            is ConnectionState.Failed -> "Connection Failed"
                            is ConnectionState.Idle -> ""
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor
                    )
                    Text(
                        text = when (state) {
                            is ConnectionState.Registering -> "Connecting to server..."
                            is ConnectionState.Registered -> state.server
                            is ConnectionState.Failed -> state.message
                            is ConnectionState.Idle -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Create ConnectButton**

```kotlin
package uz.yalla.sipphone.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.ConnectionState

@Composable
fun ConnectButton(
    state: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
    ) {
        when (state) {
            is ConnectionState.Idle -> {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect")
                }
            }

            is ConnectionState.Registering -> {
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
                Button(onClick = {}, enabled = false) {
                    AnimatedContent(targetState = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Connecting...")
                        }
                    }
                }
            }

            is ConnectionState.Registered -> {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            }

            is ConnectionState.Failed -> {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry")
                }
            }
        }
    }
}
```

- [ ] **Step 4: Verify compilation**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/ui/component/
git commit -m "feat(ui): add Material 3 components - form, status card, button

SipCredentialsForm with validation, leading icons, password toggle.
ConnectionStatusCard with animated colors per state.
ConnectButton with loading spinner and state transitions.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Main Screen

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/ui/screen/MainScreen.kt`

- [ ] **Step 1: Create MainScreen**

```kotlin
package uz.yalla.sipphone.ui.screen

import androidx.compose.animation.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import uz.yalla.sipphone.domain.ConnectionState
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.ui.component.ConnectButton
import uz.yalla.sipphone.ui.component.ConnectionStatusCard
import uz.yalla.sipphone.ui.component.FormErrors
import uz.yalla.sipphone.ui.component.FormState
import uz.yalla.sipphone.ui.component.SipCredentialsForm
import uz.yalla.sipphone.ui.component.validateForm

@Composable
fun MainScreen(
    connectionState: ConnectionState,
    onConnect: (SipCredentials) -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit
) {
    var formState by remember {
        mutableStateOf(FormState(server = "192.168.0.22", username = "102"))
    }
    var formErrors by remember { mutableStateOf(FormErrors()) }

    val formEnabled = connectionState is ConnectionState.Idle
            || connectionState is ConnectionState.Failed
    val formAlpha by animateFloatAsState(
        targetValue = if (formEnabled) 1f else 0.6f,
        animationSpec = tween(300)
    )

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "SIP Registration",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(24.dp))

            SipCredentialsForm(
                formState = formState,
                errors = formErrors,
                enabled = formEnabled,
                onFormChange = { newState ->
                    formState = newState
                    // Clear errors for changed fields
                    formErrors = FormErrors()
                },
                onSubmit = {
                    val errors = validateForm(formState)
                    formErrors = errors
                    if (!errors.hasErrors) {
                        onConnect(
                            SipCredentials(
                                server = formState.server.trim(),
                                port = formState.port.toIntOrNull() ?: 5060,
                                username = formState.username.trim(),
                                password = formState.password
                            )
                        )
                    }
                },
                modifier = Modifier.alpha(formAlpha)
            )

            Spacer(Modifier.height(24.dp))

            ConnectButton(
                state = connectionState,
                onConnect = {
                    val errors = validateForm(formState)
                    formErrors = errors
                    if (!errors.hasErrors) {
                        onConnect(
                            SipCredentials(
                                server = formState.server.trim(),
                                port = formState.port.toIntOrNull() ?: 5060,
                                username = formState.username.trim(),
                                password = formState.password
                            )
                        )
                    }
                },
                onDisconnect = onDisconnect,
                onCancel = onCancel
            )

            Spacer(Modifier.height(16.dp))

            ConnectionStatusCard(state = connectionState)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/ui/screen/MainScreen.kt
git commit -m "feat(ui): add MainScreen with state-driven layout

Single screen composable: form fields with validation, connect/
disconnect buttons, animated status card. Form disables during
registration with alpha transition.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: App Wiring & Lifecycle

**Files:**
- Create: `src/main/kotlin/uz/yalla/sipphone/App.kt`
- Modify: `src/main/kotlin/uz/yalla/sipphone/Main.kt`

- [ ] **Step 1: Create App.kt**

```kotlin
package uz.yalla.sipphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uz.yalla.sipphone.sip.SipClient
import uz.yalla.sipphone.ui.screen.MainScreen

@Composable
fun App(sipClient: SipClient) {
    val connectionState by sipClient.state.collectAsState()
    val scope = rememberCoroutineScope()
    var registerJob: Job? = null

    MainScreen(
        connectionState = connectionState,
        onConnect = { credentials ->
            registerJob?.cancel()
            registerJob = scope.launch(Dispatchers.IO) {
                sipClient.register(credentials)
            }
        },
        onDisconnect = {
            scope.launch(Dispatchers.IO) {
                sipClient.unregister()
            }
        },
        onCancel = {
            registerJob?.cancel()
        }
    )
}
```

- [ ] **Step 2: Update Main.kt with lifecycle management**

Replace entire content of `src/main/kotlin/uz/yalla/sipphone/Main.kt`:

```kotlin
package uz.yalla.sipphone

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import uz.yalla.sipphone.sip.SipClient
import uz.yalla.sipphone.sip.SipTransport
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

fun main() {
    val transport = SipTransport()
    val sipClient = SipClient(transport)

    application {
        Window(
            onCloseRequest = {
                // Best-effort UNREGISTER + socket cleanup
                runBlocking(Dispatchers.IO) {
                    sipClient.unregister()
                }
                sipClient.close()
                exitApplication()
            },
            title = "Yalla SIP Phone",
            state = rememberWindowState(
                size = DpSize(420.dp, 600.dp),
                position = WindowPosition(Alignment.Center)
            )
        ) {
            YallaSipPhoneTheme {
                App(sipClient)
            }
        }
    }
}
```

- [ ] **Step 3: Verify compilation and run**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

Then manually verify the app launches:
Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew run`
Expected: Window opens with form fields, professional blue theme, all fields editable.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/uz/yalla/sipphone/App.kt src/main/kotlin/uz/yalla/sipphone/Main.kt
git commit -m "feat: wire App with SipClient and lifecycle management

App.kt connects UI to SipClient via coroutines. Main.kt handles
window setup, theme, and graceful cleanup (UNREGISTER + socket
close) on exit.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Integration Verification

**Files:** None created. This is a verification task.

- [ ] **Step 1: Run all tests**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew test`
Expected: ALL PASS

- [ ] **Step 2: Run the app and verify UI**

Run: `cd /Users/macbookpro/Ildam/yalla/yalla-sip-phone && ./gradlew run`

Verify manually:
- Window opens at correct size (420x600), centered
- Title: "Yalla SIP Phone"
- Professional blue theme applied
- Form fields visible: Server (pre-filled "192.168.0.22"), Port (5060), Username (pre-filled "102"), Password
- Password field has visibility toggle
- All fields have leading icons
- Enter key triggers connect
- Empty field shows error on connect attempt
- Connect button shows loading state when clicked

- [ ] **Step 3: Test against Oktell (if server reachable)**

Fill in the form:
- Server: `192.168.0.22`
- Port: `5060`
- Username: `102`
- Password: `1234qwerQQ`

Click Connect. Expected one of:
- **Green card:** "Registered - 192.168.0.22:5060" (success!)
- **Red card:** Clear error message (server unreachable, auth failed, etc.)

Both outcomes are valid PoC results - we proved we can attempt SIP registration from Kotlin Desktop.

- [ ] **Step 4: Test disconnect and close**

If registered:
- Click "Disconnect" → Status returns to Idle, form re-enables
- Close window → App exits cleanly (no hanging process)

If failed:
- Click "Retry" → Attempts registration again
- Close window → App exits cleanly

- [ ] **Step 5: Final commit with any fixes**

If any fixes were needed during verification:
```bash
git add -A
git commit -m "fix: integration test adjustments

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```
