package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

interface AccountProvider {
    fun getAccount(accountId: String): PjsipAccount?
    fun getFirstConnectedAccount(): PjsipAccount?
}

class PjsipAccountManager(
    private val isDestroyed: () -> Boolean,
) : AccountProvider {

    private val _accountStates = mutableMapOf<String, MutableStateFlow<PjsipRegistrationState>>()
    private val accounts: MutableMap<String, PjsipAccount> = mutableMapOf()
    private val rateLimiter = RegisterRateLimiter()

    var incomingCallHandler: ((accountId: String, callId: Int) -> Unit)? = null

    private val _registrationEvents = MutableSharedFlow<Pair<String, PjsipRegistrationState>>(
        extraBufferCapacity = 64,
    )
    val registrationEvents: SharedFlow<Pair<String, PjsipRegistrationState>> =
        _registrationEvents.asSharedFlow()

    fun updateRegistrationState(accountId: String, state: PjsipRegistrationState) {
        stateFlowFor(accountId).value = state
        _registrationEvents.tryEmit(accountId to state)
    }

    fun isAccountDestroyed(): Boolean = isDestroyed()

    fun handleIncomingCall(accountId: String, callId: Int) {
        incomingCallHandler?.invoke(accountId, callId)
    }

    override fun getAccount(accountId: String): PjsipAccount? = accounts[accountId]

    override fun getFirstConnectedAccount(): PjsipAccount? =
        accounts.entries.firstOrNull { (id, _) ->
            _accountStates[id]?.value is PjsipRegistrationState.Registered
        }?.value

    suspend fun register(accountId: String, credentials: SipCredentials): Result<Unit> {
        val stateFlow = stateFlowFor(accountId)

        if (stateFlow.value is PjsipRegistrationState.Registering) {
            return Result.failure(IllegalStateException("Registration already in progress for $accountId"))
        }

        rateLimiter.awaitSlot(accountId)

        val wasRegistered = stateFlow.value is PjsipRegistrationState.Registered
        stateFlow.value = PjsipRegistrationState.Registering

        accounts[accountId]?.let { prev ->
            teardownPrevious(accountId, prev, stateFlow, wasRegistered)
            stateFlow.value = PjsipRegistrationState.Registering
        }

        return AccountConfigBuilder.build(credentials).use { config ->
            runCatching {
                val account = PjsipAccount(accountId, credentials.server, this).apply {
                    create(config, true)
                }
                accounts[accountId] = account
                logger.info { "[$accountId] Account created, awaiting registration callback" }
            }.onFailure { e ->
                logger.error(e) { "[$accountId] Registration failed" }
                stateFlow.value = PjsipRegistrationState.Failed(SipError.fromException(e))
            }
        }
    }

    suspend fun unregister(accountId: String) {
        val acc = accounts[accountId] ?: return
        val stateFlow = _accountStates[accountId] ?: return
        runCatching {
            acc.setRegistration(false)
            withTimeoutOrNull(SipConstants.Timeout.UNREGISTER_MS) {
                stateFlow.first { it is PjsipRegistrationState.Idle }
            }
        }.onFailure { logger.warn(it) { "[$accountId] Unregister error" } }
        acc.safeDelete()
        accounts.remove(accountId)
        stateFlow.value = PjsipRegistrationState.Idle
    }

    suspend fun unregisterAll() {
        accounts.keys.toList().forEach { unregister(it) }
    }

    suspend fun destroy() {
        val activeAccounts = accounts.values.toList()
        activeAccounts.forEach { acc ->
            runCatching { acc.setRegistration(false) }
                .onFailure { logger.warn(it) { "setRegistration(false) failed during destroy" } }
        }
        // Wait for PJSIP to report Idle on every account, bounded by DESTROY_MS.
        withTimeoutOrNull(SipConstants.Timeout.DESTROY_MS) {
            coroutineScope {
                _accountStates.values
                    .map { flow -> async { flow.first { it is PjsipRegistrationState.Idle } } }
                    .awaitAll()
            }
        } ?: logger.warn { "destroy: timed out waiting for Idle" }
        accounts.values.forEach { it.safeDelete() }
        accounts.clear()
        _accountStates.values.forEach { it.value = PjsipRegistrationState.Idle }
        _accountStates.clear()
        rateLimiter.clear()
    }

    private fun stateFlowFor(accountId: String): MutableStateFlow<PjsipRegistrationState> =
        _accountStates.getOrPut(accountId) { MutableStateFlow(PjsipRegistrationState.Idle) }

    private suspend fun teardownPrevious(
        accountId: String,
        prev: PjsipAccount,
        stateFlow: MutableStateFlow<PjsipRegistrationState>,
        wasRegistered: Boolean,
    ) {
        runCatching { prev.setRegistration(false) }
            .onFailure { logger.warn(it) { "[$accountId] setRegistration(false) threw — continuing teardown" } }
        if (wasRegistered) {
            withTimeoutOrNull(SipConstants.Timeout.UNREGISTER_BEFORE_REREGISTER_MS) {
                stateFlow.first { it is PjsipRegistrationState.Idle }
            }
        }
        prev.safeDelete()
        accounts.remove(accountId)
    }

}
