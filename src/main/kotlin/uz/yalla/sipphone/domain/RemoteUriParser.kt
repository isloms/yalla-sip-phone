package uz.yalla.sipphone.domain

data class CallerInfo(
    val displayName: String?,
    val number: String,
)

private val WITH_NAME = Regex("""^"([^"]+)"\s*<sip:([^@]+)@[^>]+>$""")
private val WITHOUT_NAME = Regex("""^<?sip:([^@]+)@[^>]+>?$""")

fun parseRemoteUri(uri: String): CallerInfo {
    WITH_NAME.find(uri)?.let { match ->
        return CallerInfo(
            displayName = match.groupValues[1],
            number = match.groupValues[2],
        )
    }
    WITHOUT_NAME.find(uri)?.let { match ->
        return CallerInfo(
            displayName = null,
            number = match.groupValues[1],
        )
    }
    return CallerInfo(displayName = null, number = uri)
}
