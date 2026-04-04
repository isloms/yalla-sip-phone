package uz.yalla.sipphone.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeRegistrationEngine : RegistrationEngine {

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    override val registrationState = _registrationState.asStateFlow()

    var initCalled = false
    var lastCredentials: SipCredentials? = null

    override suspend fun init() = Result.success(Unit).also { initCalled = true }

    override suspend fun register(credentials: SipCredentials): Result<Unit> {
        lastCredentials = credentials
        _registrationState.value = RegistrationState.Registering
        return Result.success(Unit)
    }

    override suspend fun unregister() {
        _registrationState.value = RegistrationState.Idle
    }

    override suspend fun destroy() {
        _registrationState.value = RegistrationState.Idle
    }

    fun simulateRegistered(server: String = "sip:102@192.168.0.22") {
        _registrationState.value = RegistrationState.Registered(server)
    }

    fun simulateFailed(message: String = "403 Forbidden") {
        _registrationState.value = RegistrationState.Failed(message)
    }
}
