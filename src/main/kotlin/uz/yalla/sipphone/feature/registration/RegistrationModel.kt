package uz.yalla.sipphone.feature.registration

data class FormState(
    val server: String = "",
    val port: String = "5060",
    val username: String = "",
    val password: String = "",
)

data class FormErrors(
    val server: String? = null,
    val port: String? = null,
    val username: String? = null,
    val password: String? = null,
) {
    val hasErrors: Boolean get() = listOfNotNull(server, port, username, password).isNotEmpty()
}

fun validateForm(form: FormState): FormErrors = FormErrors(
    server = if (form.server.isBlank()) "Server required" else null,
    port = when (val portInt = form.port.toIntOrNull()) {
        null -> "Invalid port"
        !in 1..65535 -> "Port must be 1-65535"
        else -> null
    },
    username = if (form.username.isBlank()) "Username required" else null,
    password = if (form.password.isBlank()) "Password required" else null,
)
