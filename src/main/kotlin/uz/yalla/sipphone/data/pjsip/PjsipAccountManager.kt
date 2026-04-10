package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.pjsua_stun_use
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

interface IncomingCallListener {
    fun onIncomingCall(accountId: String, callId: Int)
}

interface AccountRegistrationListener {
    fun onAccountRegistrationState(accountId: String, state: PjsipRegistrationState)
}

interface AccountProvider {
    fun getAccount(accountId: String): PjsipAccount?
    fun getFirstConnectedAccount(): PjsipAccount?
    val lastRegisteredServer: String?
}

class PjsipAccountManager(
    private val isDestroyed: () -> Boolean,
    private val pjScope: CoroutineScope,
) : AccountProvider {

    private val _accountStates = mutableMapOf<String, MutableStateFlow<PjsipRegistrationState>>()

    private val accounts: MutableMap<String, PjsipAccount> = mutableMapOf()

    override var lastRegisteredServer: String? = null
        private set

    var incomingCallListener: IncomingCallListener? = null
    var accountRegistrationListener: AccountRegistrationListener? = null

    private val lastRegisterAttemptMs = mutableMapOf<String, Long>()

    fun getAccountStateFlow(accountId: String): StateFlow<PjsipRegistrationState> {
        return _accountStates.getOrPut(accountId) {
            MutableStateFlow(PjsipRegistrationState.Idle)
        }.asStateFlow()
    }

    fun updateRegistrationState(accountId: String, state: PjsipRegistrationState) {
        if (state is PjsipRegistrationState.Registered) {
            lastRegisteredServer = state.uri
        }
        val flow = _accountStates.getOrPut(accountId) { MutableStateFlow(PjsipRegistrationState.Idle) }
        flow.value = state
        accountRegistrationListener?.onAccountRegistrationState(accountId, state)
    }

    fun isAccountDestroyed(): Boolean = isDestroyed()

    fun handleIncomingCall(accountId: String, callId: Int) {
        incomingCallListener?.onIncomingCall(accountId, callId)
    }

    override fun getAccount(accountId: String): PjsipAccount? = accounts[accountId]

    override fun getFirstConnectedAccount(): PjsipAccount? {
        return accounts.entries.firstOrNull { (id, _) ->
            _accountStates[id]?.value is PjsipRegistrationState.Registered
        }?.value
    }

    suspend fun register(accountId: String, credentials: SipCredentials): Result<Unit> {
        val stateFlow = _accountStates.getOrPut(accountId) { MutableStateFlow(PjsipRegistrationState.Idle) }

        if (stateFlow.value is PjsipRegistrationState.Registering) {
            return Result.failure(IllegalStateException("Registration already in progress for $accountId"))
        }

        val now = System.currentTimeMillis()
        val lastAttempt = lastRegisterAttemptMs[accountId] ?: 0L
        val elapsed = now - lastAttempt
        if (elapsed < SipConstants.RATE_LIMIT_MS) {
            delay(SipConstants.RATE_LIMIT_MS - elapsed)
        }
        lastRegisterAttemptMs[accountId] = System.currentTimeMillis()

        val wasRegistered = stateFlow.value is PjsipRegistrationState.Registered
        stateFlow.value = PjsipRegistrationState.Registering

        accounts[accountId]?.let { prevAccount ->
            try {
                prevAccount.setRegistration(false)
            } catch (_: Exception) {
                logger.warn { "[$accountId] setRegistration(false) threw — continuing teardown" }
            }

            if (wasRegistered) {
                try {
                    withTimeoutOrNull(SipConstants.Timeout.UNREGISTER_BEFORE_REREGISTER_MS) {
                        stateFlow.first { it is PjsipRegistrationState.Idle }
                    }
                } catch (_: Exception) {}
            }
            stateFlow.value = PjsipRegistrationState.Registering

            try { prevAccount.delete() } catch (_: Exception) {}
            accounts.remove(accountId)
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
            accountConfig.regConfig.retryIntervalSec = 0  // disable pjsip built-in retry — we handle reconnect
            accountConfig.sipConfig.authCreds.add(authCred)
            accountConfig.natConfig.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
            accountConfig.natConfig.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED

            val account = PjsipAccount(accountId, credentials.server, this, pjScope).apply {
                create(accountConfig, true)
            }
            accounts[accountId] = account

            logger.info { "[$accountId] Account created, awaiting registration callback" }
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "[$accountId] Registration failed" }
            stateFlow.value = PjsipRegistrationState.Failed(SipError.fromException(e))
            return Result.failure(e)
        } finally {
            accountConfig.delete()
            authCred.delete()
        }
    }

    suspend fun unregister(accountId: String) {
        val acc = accounts[accountId] ?: return
        val stateFlow = _accountStates[accountId] ?: return
        try {
            acc.setRegistration(false)
            withTimeoutOrNull(SipConstants.Timeout.UNREGISTER_MS) {
                stateFlow.first { it is PjsipRegistrationState.Idle }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "[$accountId] Unregistration timed out" }
        } catch (e: Exception) {
            logger.error(e) { "[$accountId] Unregister error" }
        } finally {
            try { acc.delete() } catch (_: Exception) {}
            accounts.remove(accountId)
            stateFlow.value = PjsipRegistrationState.Idle
        }
    }

    suspend fun unregisterAll() {
        val accountIds = accounts.keys.toList()
        for (id in accountIds) {
            unregister(id)
        }
    }

    suspend fun destroy() {
        val accountEntries = accounts.entries.toList()
        for ((id, acc) in accountEntries) {
            try {
                acc.setRegistration(false)
            } catch (_: Exception) {}
        }
        delay(SipConstants.UNREGISTER_DELAY_MS)
        for ((id, acc) in accountEntries) {
            try {
                acc.delete()
            } catch (_: Exception) {}
        }
        accounts.clear()
        _accountStates.values.forEach { it.value = PjsipRegistrationState.Idle }
        _accountStates.clear()
        lastRegisterAttemptMs.clear()
    }
}
