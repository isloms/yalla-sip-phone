package uz.yalla.sipphone.data.network

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiResponseTest {

    @Test
    fun `errorMessage returns errors string when errors is a string primitive`() {
        val response = ApiResponse<Unit>(
            status = false, code = 401, message = "Error",
            errors = JsonPrimitive("employee not found"),
        )
        assertEquals("employee not found", response.errorMessage())
    }

    @Test
    fun `errorMessage returns joined fields when errors is an object`() {
        val response = ApiResponse<Unit>(
            status = false, code = 422, message = "Validation failed",
            errors = buildJsonObject { put("pin_code", "required") },
        )
        assertEquals("pin_code: required", response.errorMessage())
    }

    @Test
    fun `errorMessage returns message when errors is null`() {
        val response = ApiResponse<Unit>(
            status = false, code = 500, message = "Internal error",
            errors = null,
        )
        assertEquals("Internal error", response.errorMessage())
    }

    @Test
    fun `errorMessage returns fallback when both errors and message are null`() {
        val response = ApiResponse<Unit>(status = false, code = 500)
        assertEquals("Unknown error", response.errorMessage())
    }
}
