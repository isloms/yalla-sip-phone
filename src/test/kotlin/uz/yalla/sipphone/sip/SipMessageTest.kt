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
            server = "192.168.0.22", username = "102",
            localIp = "192.168.0.100", localPort = 5060
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
        val raw = "SIP/2.0 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"test\", nonce=\"abc\"\r\nContent-Length: 0\r\n\r\n"
        val response = SipMessageBuilder.parseResponse(raw)
        assertEquals(401, response.statusCode)
        assertNotNull(response.header("www-authenticate"))
        assertNotNull(response.header("WWW-Authenticate"))
    }

    @Test
    fun `parseResponse extracts Contact expires`() {
        val raw = "SIP/2.0 200 OK\r\nContact: <sip:102@192.168.0.100:5060>;expires=1800\r\nExpires: 3600\r\n\r\n"
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
