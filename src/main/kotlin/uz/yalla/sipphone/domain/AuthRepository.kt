package uz.yalla.sipphone.domain

/**
 * Authenticates the agent against the Oktell backend and retrieves SIP credentials.
 *
 * The returned [AuthResult] contains the SIP credentials needed to call
 * [RegistrationEngine.register]. Implementations are responsible for persisting
 * the session token if required.
 */
interface AuthRepository {
    /**
     * Authenticates with [password] and returns a populated [AuthResult] on success.
     *
     * @return [Result.failure] on network errors or invalid credentials.
     */
    suspend fun login(password: String): Result<AuthResult>
}
