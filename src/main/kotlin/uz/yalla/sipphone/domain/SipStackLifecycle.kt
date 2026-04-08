package uz.yalla.sipphone.domain

interface SipStackLifecycle {
    suspend fun initialize(): Result<Unit>
    suspend fun shutdown()
}
