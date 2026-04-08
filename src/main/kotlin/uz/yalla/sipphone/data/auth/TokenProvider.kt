package uz.yalla.sipphone.data.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TokenProvider {
    suspend fun getToken(): String?
    suspend fun setToken(token: String)
    suspend fun clearToken()
}

class InMemoryTokenProvider : TokenProvider {
    @Volatile
    private var token: String? = null
    private val mutex = Mutex()

    override suspend fun getToken(): String? = token

    override suspend fun setToken(token: String) {
        mutex.withLock { this.token = token }
    }

    override suspend fun clearToken() {
        mutex.withLock { token = null }
    }
}
