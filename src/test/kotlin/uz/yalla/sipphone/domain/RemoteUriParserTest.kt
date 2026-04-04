package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteUriParserTest {

    @Test
    fun `parses display name and number from standard URI`() {
        val (name, number) = parseRemoteUri(""""Alex Petrov" <sip:102@192.168.0.22>""")
        assertEquals("Alex Petrov", name)
        assertEquals("102", number)
    }

    @Test
    fun `parses URI without display name`() {
        val (name, number) = parseRemoteUri("<sip:+998901234567@192.168.0.22>")
        assertNull(name)
        assertEquals("+998901234567", number)
    }

    @Test
    fun `parses URI with port in host`() {
        val (name, number) = parseRemoteUri(""""Operator" <sip:201@10.0.0.1:5060>""")
        assertEquals("Operator", name)
        assertEquals("201", number)
    }

    @Test
    fun `parses bare sip URI without angle brackets`() {
        val (name, number) = parseRemoteUri("sip:100@server.local")
        assertNull(name)
        assertEquals("100", number)
    }

    @Test
    fun `handles empty string gracefully`() {
        val (name, number) = parseRemoteUri("")
        assertNull(name)
        assertEquals("", number)
    }

    @Test
    fun `handles malformed URI — returns raw input as number`() {
        val (name, number) = parseRemoteUri("not-a-sip-uri")
        assertNull(name)
        assertEquals("not-a-sip-uri", number)
    }

    @Test
    fun `parses display name with special characters`() {
        val (name, number) = parseRemoteUri(""""O'Brien, John" <sip:300@host>""")
        assertEquals("O'Brien, John", name)
        assertEquals("300", number)
    }

    @Test
    fun `parses URI with transport parameter`() {
        val (name, number) = parseRemoteUri(""""Test" <sip:102@host;transport=udp>""")
        assertEquals("Test", name)
        assertEquals("102", number)
    }
}
