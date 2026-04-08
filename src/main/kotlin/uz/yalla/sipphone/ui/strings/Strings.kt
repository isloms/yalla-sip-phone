package uz.yalla.sipphone.ui.strings

object Strings {
    const val APP_TITLE = "Yalla SIP Phone"

    const val ERROR_INIT_TITLE = "Yalla SIP Phone - Error"
    fun errorInitMessage(reason: String?): String =
        "Failed to initialize SIP engine:\n$reason"

    const val SETTINGS_LOGOUT_CONFIRM = "Logout and close Yalla SIP Phone?"
    const val SETTINGS_LOGOUT_CONFIRM_TITLE = "Confirm Logout"
}
