package uz.yalla.sipphone.domain

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SipErrorTest {

    @Test
    fun `401 maps to AuthFailed`() {
        val error = SipError.fromSipStatus(401, "Unauthorized")
        assertIs<SipError.AuthFailed>(error)
        assertEquals(401, error.code)
    }

    @Test
    fun `403 maps to AuthFailed`() {
        val error = SipError.fromSipStatus(403, "Forbidden")
        assertIs<SipError.AuthFailed>(error)
        assertEquals(403, error.code)
    }

    @Test
    fun `408 maps to RequestTimeout`() {
        val error = SipError.fromSipStatus(408, "Request Timeout")
        assertIs<SipError.RequestTimeout>(error)
    }

    @Test
    fun `503 maps to NetworkError`() {
        val error = SipError.fromSipStatus(503, "Service Unavailable")
        assertIs<SipError.NetworkError>(error)
    }

    @Test
    fun `500 maps to ServerError`() {
        val error = SipError.fromSipStatus(500, "Internal Server Error")
        assertIs<SipError.ServerError>(error)
        assertEquals(500, error.code)
    }

    @Test
    fun `fromException maps to InternalError`() {
        val error = SipError.fromException(RuntimeException("boom"))
        assertIs<SipError.InternalError>(error)
        assertTrue(error.displayMessage.contains("boom"))
    }

    @Test
    fun `displayMessage is human readable`() {
        val error = SipError.AuthFailed(403, "Forbidden")
        assertEquals("Authentication failed: 403 Forbidden", error.displayMessage)
    }

    @Test
    fun `SipConstants buildCallUri formats correctly`() {
        assertEquals("sip:102@192.168.0.22", SipConstants.buildCallUri("102", "192.168.0.22"))
    }

    @Test
    fun `SipConstants extractHostFromUri extracts after at sign`() {
        assertEquals("192.168.0.22", SipConstants.extractHostFromUri("sip:102@192.168.0.22"))
    }

    @Test
    fun `SipConstants extractHostFromUri handles null`() {
        assertEquals("", SipConstants.extractHostFromUri(null))
    }

    @Test
    fun `SipConstants extractHostFromUri handles no at sign`() {
        assertEquals("192.168.0.22", SipConstants.extractHostFromUri("192.168.0.22"))
    }
}
