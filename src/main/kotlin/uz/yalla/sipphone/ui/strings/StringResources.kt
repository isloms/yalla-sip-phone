package uz.yalla.sipphone.ui.strings

import androidx.compose.runtime.staticCompositionLocalOf

interface StringResources {
    // Login
    val loginTitle: String
    val loginSubtitle: String
    val loginPasswordPlaceholder: String
    val loginButton: String
    val loginConnecting: String
    val loginRetry: String
    val loginManualConnection: String
    val errorWrongPassword: String
    val errorNetworkFailed: String

    // Agent Status
    val agentStatusOnline: String
    val agentStatusBusy: String
    val agentStatusOffline: String

    // SIP
    val sipConnected: String
    val sipReconnecting: String
    val sipDisconnected: String
    val sipDisconnectBlockedByCall: String
    val sipReconnectHint: String
    val sipRinging: String

    // Settings
    val settingsTitle: String
    val settingsTheme: String
    val settingsLocale: String
    val settingsLogout: String
    val settingsLogoutConfirm: String
    val settingsLogoutConfirmTitle: String

    // Call Actions
    val buttonCall: String
    val buttonAnswer: String
    val buttonReject: String
    val buttonHangup: String
    val buttonMute: String
    val buttonUnmute: String
    val buttonHold: String
    val buttonResume: String
    val placeholderPhone: String

    // General
    val appTitle: String
    val buttonConnect: String
    val buttonCancel: String
    val labelServer: String
    val labelPort: String
    val labelUsername: String
    val labelPassword: String
    val labelDispatcherUrl: String
    val placeholderServer: String
    val placeholderUsername: String
    val placeholderDispatcherUrl: String
}

val LocalStrings = staticCompositionLocalOf<StringResources> {
    error("StringResources not provided")
}
