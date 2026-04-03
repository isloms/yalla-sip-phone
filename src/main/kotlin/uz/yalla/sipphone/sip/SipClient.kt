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
            username = credentials.username, realm = challenge.realm,
            password = credentials.password, nonce = challenge.nonce,
            method = "REGISTER", uri = uri,
            qop = challenge.qop,
            nc = if (challenge.qop != null) nc else null,
            cnonce = if (challenge.qop != null) cnonce else null
        )

        val authHeader = DigestAuth.buildAuthorizationHeader(
            username = credentials.username, challenge = challenge,
            method = "REGISTER", uri = uri, response = digestResponse,
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
        credentials: SipCredentials, response: SipResponse, authHeader: String
    ) {
        val minExpires = response.header("min-expires")?.toIntOrNull() ?: 60
        val retryMessage = messageBuilder!!.buildRegister(expires = minExpires, authorization = authHeader)
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
