package uz.yalla.sipphone.data.network

sealed class NetworkError(
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    data object Unauthorized : NetworkError("Session expired")

    data class ClientError(
        val code: Int,
        val serverMessage: String?,
    ) : NetworkError(serverMessage ?: "Client error ($code)")

    data class ServerError(
        val code: Int,
        val serverMessage: String?,
    ) : NetworkError(serverMessage ?: "Server error ($code)")

    data class NoConnection(
        override val cause: Throwable,
    ) : NetworkError("No connection: ${cause.message}", cause)

    data class ParseError(
        override val cause: Throwable,
    ) : NetworkError("Data format error: ${cause.message}", cause)
}
