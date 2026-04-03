package uz.yalla.sipphone.feature.registration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class RegistrationModelTest {

    @Test
    fun `empty server shows error`() {
        val errors = validateForm(FormState(server = "", port = "5060", username = "102", password = "pass"))
        assertNotNull(errors.server)
        assertEquals("Server required", errors.server)
    }

    @Test
    fun `blank server shows error`() {
        val errors = validateForm(FormState(server = "   ", port = "5060", username = "102", password = "pass"))
        assertNotNull(errors.server)
    }

    @Test
    fun `empty port shows error`() {
        val errors = validateForm(FormState(server = "192.168.0.22", port = "", username = "102", password = "pass"))
        assertNotNull(errors.port)
        assertEquals("Invalid port", errors.port)
    }

    @Test
    fun `non-numeric port shows error`() {
        val errors = validateForm(FormState(server = "192.168.0.22", port = "abc", username = "102", password = "pass"))
        assertNotNull(errors.port)
        assertEquals("Invalid port", errors.port)
    }

    @Test
    fun `port 0 shows error`() {
        val errors = validateForm(FormState(server = "192.168.0.22", port = "0", username = "102", password = "pass"))
        assertNotNull(errors.port)
        assertEquals("Port must be 1-65535", errors.port)
    }

    @Test
    fun `port 65536 shows error`() {
        val errors = validateForm(
            FormState(server = "192.168.0.22", port = "65536", username = "102", password = "pass"),
        )
        assertNotNull(errors.port)
        assertEquals("Port must be 1-65535", errors.port)
    }

    @Test
    fun `port 1 is valid`() {
        val errors = validateForm(FormState(server = "192.168.0.22", port = "1", username = "102", password = "pass"))
        assertNull(errors.port)
    }

    @Test
    fun `port 65535 is valid`() {
        val errors = validateForm(
            FormState(server = "192.168.0.22", port = "65535", username = "102", password = "pass"),
        )
        assertNull(errors.port)
    }

    @Test
    fun `empty username shows error`() {
        val errors = validateForm(FormState(server = "192.168.0.22", port = "5060", username = "", password = "pass"))
        assertNotNull(errors.username)
        assertEquals("Username required", errors.username)
    }

    @Test
    fun `empty password shows error`() {
        val errors = validateForm(FormState(server = "192.168.0.22", port = "5060", username = "102", password = ""))
        assertNotNull(errors.password)
        assertEquals("Password required", errors.password)
    }

    @Test
    fun `valid form produces no errors`() {
        val errors = validateForm(
            FormState(server = "192.168.0.22", port = "5060", username = "102", password = "pass"),
        )
        assertNull(errors.server)
        assertNull(errors.port)
        assertNull(errors.username)
        assertNull(errors.password)
    }

    @Test
    fun `FormErrors hasErrors is true when any error present`() {
        val errors = FormErrors(server = "Server required")
        assertTrue(errors.hasErrors)
    }

    @Test
    fun `FormErrors hasErrors is false when no errors`() {
        val errors = FormErrors()
        assertFalse(errors.hasErrors)
    }

    @Test
    fun `default FormState has expected defaults`() {
        val state = FormState()
        assertEquals("", state.server)
        assertEquals("5060", state.port)
        assertEquals("", state.username)
        assertEquals("", state.password)
    }

    @Test
    fun `multiple errors returned at once`() {
        val errors = validateForm(FormState(server = "", port = "abc", username = "", password = ""))
        assertNotNull(errors.server)
        assertNotNull(errors.port)
        assertNotNull(errors.username)
        assertNotNull(errors.password)
    }
}
