package uz.yalla.sipphone.domain

/**
 * Authenticates the agent against the backend and retrieves SIP credentials.
 */
interface AuthRepository {
    /**
     * Authenticates with [pinCode] and returns a populated [AuthResult] on success.
     *
     * @return [Result.failure] on network errors or invalid credentials.
     */
    suspend fun login(pinCode: String): Result<AuthResult>

    /**
     * Logs out the current session. Best-effort — network errors are acceptable.
     */
    suspend fun logout(): Result<Unit>
}
