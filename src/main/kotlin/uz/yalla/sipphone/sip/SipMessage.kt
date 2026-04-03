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
