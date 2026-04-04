package uz.yalla.sipphone.domain

enum class AgentStatus(val displayName: String, val colorHex: String) {
    READY("Ready", "#2E7D32"),
    AWAY("Away", "#F59E0B"),
    BREAK("Break", "#F97316"),
    WRAP_UP("Wrap-Up", "#8B5CF6"),
    OFFLINE("Offline", "#98A2B3"),
}
