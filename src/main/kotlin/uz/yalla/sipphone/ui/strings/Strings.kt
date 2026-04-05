package uz.yalla.sipphone.ui.strings

object Strings {
    const val APP_TITLE = "Yalla SIP Phone"
    const val REGISTRATION_TITLE = "SIP Registration"

    const val BUTTON_CONNECT = "Connect"
    const val BUTTON_DISCONNECT = "Disconnect"
    const val BUTTON_CANCEL = "Cancel"
    const val BUTTON_RETRY = "Retry"
    const val BUTTON_CONNECTING = "Connecting..."
    const val BUTTON_CALL = "Call"
    const val BUTTON_ANSWER = "Answer"
    const val BUTTON_REJECT = "Reject"
    const val BUTTON_END = "End"
    const val BUTTON_MUTE = "Mute"
    const val BUTTON_UNMUTE = "Unmute"
    const val BUTTON_HOLD = "Hold"
    const val BUTTON_RESUME = "Resume"

    const val STATUS_READY = "READY"
    const val STATUS_ACTIVE = "ACTIVE"
    const val STATUS_ON_HOLD = "ON HOLD"
    const val STATUS_CALLING = "CALLING\u2026"
    const val STATUS_INCOMING_CALL = "INCOMING CALL"
    const val STATUS_ENDING_CALL = "Ending call..."
    const val STATUS_CONNECTION_LOST = "Connection lost \u2014 returning..."
    const val STATUS_SPACE_HINT = " (Space)"

    const val REG_STATUS_REGISTERING = "Registering..."
    const val REG_STATUS_REGISTERED = "Registered"
    const val REG_STATUS_FAILED = "Connection Failed"
    const val REG_DETAIL_CONNECTING = "Connecting to server..."

    const val PLACEHOLDER_PHONE = "Phone number"
    const val PLACEHOLDER_SERVER = "192.168.0.22"
    const val PLACEHOLDER_USERNAME = "102"

    const val LABEL_SERVER = "SIP Server"
    const val LABEL_PORT = "Port"
    const val LABEL_USERNAME = "Username"
    const val LABEL_PASSWORD = "Password"

    const val ERROR_INIT_TITLE = "Yalla SIP Phone - Error"
    fun errorInitMessage(reason: String?): String =
        "Failed to initialize SIP engine:\n$reason"

    // Login
    const val LOGIN_TITLE = "Yalla SIP Phone"
    const val LOGIN_PASSWORD_LABEL = "Password"
    const val LOGIN_BUTTON = "Login"
    const val LOGIN_MANUAL_CONNECTION = "Manual connection"

    // Toolbar
    const val STATUS_RINGING = "Ringing..."
    const val CALL_QUALITY_EXCELLENT = "Excellent"
    const val CALL_QUALITY_GOOD = "Good"
    const val CALL_QUALITY_FAIR = "Fair"
    const val CALL_QUALITY_POOR = "Poor"

    // Settings
    const val SETTINGS_TITLE = "Settings"
    const val SETTINGS_THEME = "Theme"
    const val SETTINGS_LOGOUT = "Logout"
    const val SETTINGS_LOGOUT_CONFIRM = "Logout and close Yalla SIP Phone?"
    const val SETTINGS_LOGOUT_CONFIRM_TITLE = "Confirm Logout"

}
