package uz.yalla.sipphone.domain

interface AuthRepository {
    suspend fun login(password: String): Result<AuthResult>
}
