package uz.yalla.sipphone.data.auth

import kotlinx.coroutines.delay
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult

class MockAuthRepository : AuthRepository {
    override suspend fun login(password: String): Result<AuthResult> {
        delay(1000) // simulate network
        return if (password == "test123") {
            Result.success(
                LoginResponse(
                    sipServer = "192.168.30.103",
                    sipPort = 5060,
                    sipUsername = "103",
                    sipPassword = "callers103",
                    sipTransport = "UDP",
                    dispatcherUrl = "http://192.168.60.84:5173",
                    agentId = "agent-042",
                    agentName = "Islom",
                ).toAuthResult()
            )
        } else {
            Result.failure(IllegalArgumentException("Invalid password"))
        }
    }
}
