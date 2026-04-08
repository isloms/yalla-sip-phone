package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.pjsua_stun_use
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

/**
 * Listener for incoming calls, including the [accountId] that received the call.
 */
interface IncomingCallListener {
    fun onIncomingCall(accountId: String, callId: Int)
}

/**
 * Listener for per-account registration state changes.
 * Used by [PjsipSipAccountManager] to track individual account states.
 */
interface AccountRegistrationListener {
    fun onAccountRegistrationState(accountId: String, state: RegistrationState)
}

/**
 * Provides access to the pjsip account map for call routing.
 */
interface AccountProvider {
    fun getAccount(accountId: String): PjsipAccount?
    fun getFirstConnectedAccount(): PjsipAccount?
    val lastRegisteredServer: String?
}

/**
 * Manages multiple pjsip accounts (SIP registrations) on the pjsip event-loop thread.
 *
 * Each account is identified by a unique [accountId] (typically "extension@server").
 * All public methods must be called on the pjsip dispatcher thread.
 *
 * Rate limiting is applied per-account to prevent hammering the SIP server.
 */
class PjsipAccountManager(
    private val isDestroyed: () -> Boolean,
) : AccountProvider {

    /**
     * Per-account registration state flows. Keyed by accountId.
     * Each account gets its own state flow for independent monitoring.
     */
    private val _accountStates = mutableMapOf<String, MutableStateFlow<RegistrationState>>()

    /**
     * Map of active pjsip accounts. Keyed by accountId.
     */
    val accounts: MutableMap<String, PjsipAccount> = mutableMapOf()

    override var lastRegisteredServer: String? = null
        private set

    var incomingCallListener: IncomingCallListener? = null
    var accountRegistrationListener: AccountRegistrationListener? = null

    /** Per-account rate limiting timestamps. */
    private val lastRegisterAttemptMs = mutableMapOf<String, Long>()

    /**
     * Returns the per-account registration state flow, creating one if needed.
     */
    fun getAccountStateFlow(accountId: String): StateFlow<RegistrationState> {
        return _accountStates.getOrPut(accountId) {
            MutableStateFlow(RegistrationState.Idle)
        }.asStateFlow()
    }

    /**
     * Called by [PjsipAccount.onRegState] to update per-account registration state.
     */
    fun updateRegistrationState(accountId: String, state: RegistrationState) {
        if (state is RegistrationState.Registered) {
            lastRegisteredServer = state.server
        }
        val flow = _accountStates.getOrPut(accountId) { MutableStateFlow(RegistrationState.Idle) }
        flow.value = state
        accountRegistrationListener?.onAccountRegistrationState(accountId, state)
    }

    fun isAccountDestroyed(): Boolean = isDestroyed()

    /**
     * Called by [PjsipAccount.onIncomingCall] with the accountId that received the call.
     */
    fun handleIncomingCall(accountId: String, callId: Int) {
        incomingCallListener?.onIncomingCall(accountId, callId)
    }

    override fun getAccount(accountId: String): PjsipAccount? = accounts[accountId]

    override fun getFirstConnectedAccount(): PjsipAccount? {
        return accounts.entries.firstOrNull { (id, _) ->
            _accountStates[id]?.value is RegistrationState.Registered
        }?.value
    }

    /**
     * Registers a single account by [accountId] with the given [credentials].
     *
     * If an account with the same [accountId] already exists, it is unregistered first.
     * Rate limiting is applied per-account (minimum [SipConstants.RATE_LIMIT_MS] between attempts).
     *
     * Must be called on the pjsip dispatcher thread.
     */
    suspend fun register(accountId: String, credentials: SipCredentials): Result<Unit> {
        val stateFlow = _accountStates.getOrPut(accountId) { MutableStateFlow(RegistrationState.Idle) }

        if (stateFlow.value is RegistrationState.Registering) {
            return Result.failure(IllegalStateException("Registration already in progress for $accountId"))
        }

        // Per-account rate limiting
        val now = System.currentTimeMillis()
        val lastAttempt = lastRegisterAttemptMs[accountId] ?: 0L
        val elapsed = now - lastAttempt
        if (elapsed < SipConstants.RATE_LIMIT_MS) {
            delay(SipConstants.RATE_LIMIT_MS - elapsed)
        }
        lastRegisterAttemptMs[accountId] = System.currentTimeMillis()

        val wasRegistered = stateFlow.value is RegistrationState.Registered
        stateFlow.value = RegistrationState.Registering

        // Tear down existing account if present
        accounts[accountId]?.let { prevAccount ->
            try {
                prevAccount.setRegistration(false)
            } catch (_: Exception) {
                logger.warn { "[$accountId] setRegistration(false) threw — continuing teardown" }
            }

            if (wasRegistered) {
                try {
                    withTimeoutOrNull(SipConstants.Timeout.UNREGISTER_BEFORE_REREGISTER_MS) {
                        stateFlow.first { it is RegistrationState.Idle }
                    }
                } catch (_: Exception) {}
            }
            stateFlow.value = RegistrationState.Registering

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

            val account = PjsipAccount(accountId, this).apply {
                create(accountConfig, true)
            }
            accounts[accountId] = account

            logger.info { "[$accountId] Account created, awaiting registration callback" }
            return Result.success(Unit)
        } catch (e: Exception) {
            logger.error(e) { "[$accountId] Registration failed" }
            stateFlow.value = RegistrationState.Failed(SipError.fromException(e))
            return Result.failure(e)
        } finally {
            accountConfig.delete()
            authCred.delete()
        }
    }

    /**
     * Unregisters a specific account by [accountId].
     * Sends REGISTER with expires=0, waits for confirmation, then deletes the SWIG object.
     */
    suspend fun unregister(accountId: String) {
        val acc = accounts[accountId] ?: return
        val stateFlow = _accountStates[accountId] ?: return
        try {
            acc.setRegistration(false)
            withTimeoutOrNull(SipConstants.Timeout.UNREGISTER_MS) {
                stateFlow.first { it is RegistrationState.Idle }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn { "[$accountId] Unregistration timed out" }
        } catch (e: Exception) {
            logger.error(e) { "[$accountId] Unregister error" }
        } finally {
            try { acc.delete() } catch (_: Exception) {}
            accounts.remove(accountId)
            stateFlow.value = RegistrationState.Idle
        }
    }

    /**
     * Unregisters all accounts. Iterates a snapshot of the account map.
     */
    suspend fun unregisterAll() {
        val accountIds = accounts.keys.toList()
        for (id in accountIds) {
            unregister(id)
        }
    }

    /**
     * Destroys all accounts and cleans up. Called during engine shutdown.
     * Does a best-effort unregister (with short delay) then deletes all SWIG objects.
     */
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
        _accountStates.values.forEach { it.value = RegistrationState.Idle }
        _accountStates.clear()
        lastRegisterAttemptMs.clear()
    }
}
