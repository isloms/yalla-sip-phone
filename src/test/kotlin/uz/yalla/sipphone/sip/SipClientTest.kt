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
        port = 0,
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

class MockSipServer(private val rejectAuth: Boolean = false) {
    private var socket: DatagramSocket? = null
    val port: Int get() = socket?.localPort ?: 0
    private var running = false

    fun start() {
        socket = DatagramSocket(0)
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
                    socket?.send(DatagramPacket(responseBytes, responseBytes.size, packet.address, packet.port))
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        socket?.close()
    }
}
