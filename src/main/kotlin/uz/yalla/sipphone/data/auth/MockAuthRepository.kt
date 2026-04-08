package uz.yalla.sipphone.data.auth

import kotlinx.coroutines.delay
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.SipAccountInfo
import uz.yalla.sipphone.domain.SipCredentials

class MockAuthRepository : AuthRepository {
    override suspend fun login(pinCode: String): Result<AuthResult> {
        delay(1000)
        return if (pinCode == "test123") {
            Result.success(
                AuthResult(
                    token = "mock-jwt-token",
                    accounts = listOf(
                        SipAccountInfo(
                            extensionNumber = 103,
                            serverUrl = "192.168.30.103",
                            sipName = "Mock SIP",
                            credentials = SipCredentials(
                                server = "192.168.30.103",
                                port = 5060,
                                username = "103",
                                password = "callers103",
                                transport = "UDP",
                            ),
                        ),
                    ),
                    dispatcherUrl = "http://192.168.60.84:5173",
                    agent = AgentInfo("agent-042", "Islom"),
                )
            )
        } else {
            Result.failure(IllegalArgumentException("Invalid PIN"))
        }
    }

    override suspend fun logout(): Result<Unit> {
        delay(200)
        return Result.success(Unit)
    }
}
