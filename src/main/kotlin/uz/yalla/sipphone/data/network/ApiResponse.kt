package uz.yalla.sipphone.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ApiResponse<T>(
    val status: Boolean,
    val code: Int,
    val message: String? = null,
    val result: T? = null,
    val errors: JsonElement? = null,
)

fun ApiResponse<*>.errorMessage(): String {
    return when (val e = errors) {
        is JsonPrimitive -> e.contentOrNull ?: message ?: "Unknown error"
        is JsonObject -> e.entries.joinToString { "${it.key}: ${it.value.jsonPrimitive.content}" }
        else -> message ?: "Unknown error"
    }
}
