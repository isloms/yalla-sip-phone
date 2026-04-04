package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.pjsua_stun_use
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

interface IncomingCallListener {
    fun onIncomingCall(callId: Int)
}

interface AccountProvider {
    val currentAccount: PjsipAccount?
    val lastRegisteredServer: String?
}

class PjsipAccountManager(
    private val isDestroyed: () -> Boolean,
) : AccountProvider {

    private val _registrationState = MutableStateFlow<RegistrationState>(RegistrationState.Idle)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    override var currentAccount: PjsipAccount? = null
        private set

    override var lastRegisteredServer: String? = null
        private set

    var incomingCallListener: IncomingCallListener? = null
    private var lastRegisterAttemptMs = 0L

    fun updateRegistrationState(state: RegistrationState) {
        if (state is RegistrationState.Registered) {
            lastRegisteredServer = state.server
        }
        _registrationState.value = state
    }

    fun isAccountDestroyed(): Boolean = isDestroyed()

    fun handleIncomingCall(callId: Int) {
        incomingCallListener?.onIncomingCall(callId)
    }

    suspend fun register(credentials: SipCredentials): Result<Unit> {
        if (_registrationState.value is RegistrationState.Registering) {
            return Result.failure(IllegalStateException("Registration already in progress"))
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastRegisterAttemptMs
        if (elapsed < SipConstants.RATE_LIMIT_MS) {
            delay(SipConstants.RATE_LIMIT_MS - elapsed)
        }
        lastRegisterAttemptMs = System.currentTimeMillis()

        val wasRegistered = _registrationState.value is RegistrationState.Registered
        _registrationState.value = RegistrationState.Registering

        currentAccount?.let { prevAccount ->
            try {
                prevAccount.setRegistration(false)
            } catch (_: Exception) {
                logger.warn { "setRegistration(false) threw (PJSIP_EBUSY or similar) - continuing teardown" }
            }

            if (wasRegistered) {
                try {
                    withTimeoutOrNull(SipConstants.Timeout.UNREGISTER_BEFORE_REREGISTER_MS) {
                        _registrationState.first { it is RegistrationState.Idle }
                    }
                } catch (_: Exception) {}
            }
            _registrationState.value = RegistrationState.Registering

            try { prevAccount.delete() } catch (_: Exception) {}
            currentAccount = null
        }

        val accountConfig = AccountConfig()
        val authCred = AuthCredInfo(
            SipConstants.AUTH_SCHEME_DIGEST,
            SipConstants.AUTH_REALM_ANY,
            credentials.username,
            SipConstants.AUTH_DATA_TYPE_PLAINTEXT,
            credentials.password,
        )
        try {
            accountConfig.idUri = SipConstants.buildUserUri(credentials.username, credentials.server)
            accountConfig.regConfig.registrarUri = SipConstants.buildRegistrarUri(credentials.server, credentials.port)
            accountConfig.regConfig.retryIntervalSec = 0  // disable pjsip built-in retry — we handle reconnect ourselves
            accountConfig.sipConfig.authCreds.add(authCred)
            accountConfig.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
            accountConfig.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED

            val account = PjsipAccount(this).apply {
                create(accountConfig, true)
            }
            currentAccount = account

            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "Registration failed" }
            _registrationState.value = RegistrationState.Failed(SipError.fromException(e))
            return Result.failure(e)
        } finally {
            accountConfig.delete()
            authCred.delete()
        }
    }

    suspend fun unregister() {
        val acc = currentAccount ?: return
        try {
            acc.setRegistration(false)
            withTimeout(SipConstants.Timeout.UNREGISTER_MS) {
                _registrationState.first { it is RegistrationState.Idle }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "Unregistration timed out" }
        } catch (e: Exception) {
            logger.error(e) { "Unregister error" }
        } finally {
            try { acc.delete() } catch (_: Exception) {}
            currentAccount = null
            _registrationState.value = RegistrationState.Idle
        }
    }

    suspend fun destroy() {
        try {
            currentAccount?.setRegistration(false)
        } catch (_: Exception) {}
        delay(SipConstants.UNREGISTER_DELAY_MS)
        try {
            currentAccount?.delete()
        } catch (_: Exception) {}
        currentAccount = null
        _registrationState.value = RegistrationState.Idle
    }
}
