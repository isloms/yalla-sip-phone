package uz.yalla.sipphone.domain

interface AuthRepository {
    suspend fun login(pinCode: String): Result<AuthResult>
    suspend fun logout(): Result<Unit>
}
