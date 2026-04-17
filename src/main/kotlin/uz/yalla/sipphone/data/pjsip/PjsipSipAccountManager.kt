package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.SipAccount
import uz.yalla.sipphone.domain.SipAccountInfo
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.domain.SipError

private val logger = KotlinLogging.logger {}

class PjsipSipAccountManager(
    private val accountManager: PjsipAccountManager,
    private val callEngine: CallEngine,
    private val pjDispatcher: CoroutineDispatcher,
) : SipAccountManager {

    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)

    private val credentialCache = mutableMapOf<String, SipAccountInfo>()
    private val reconnectJobs = mutableMapOf<String, Job>()
    private val reconnectAttempts = mutableMapOf<String, Int>()

    private val _accounts = MutableStateFlow<List<SipAccount>>(emptyList())
    override val accounts: StateFlow<List<SipAccount>> = _accounts.asStateFlow()

    init {
        scope.launch {
            accountManager.registrationEvents.collect { (id, state) ->
                handleRegistrationState(id, state)
            }
        }
    }

    private fun handleRegistrationState(accountId: String, state: PjsipRegistrationState) {
        when (state) {
            is PjsipRegistrationState.Registered -> {
                cancelReconnect(accountId)
                reconnectAttempts.remove(accountId)
                updateAccountState(accountId, SipAccountState.Connected)
                logger.info { "[$accountId] Connected" }
            }
            is PjsipRegistrationState.Failed -> {
                if (state.error is SipError.AuthFailed) {
                    cancelReconnect(accountId)
                    reconnectAttempts.remove(accountId)
                    updateAccountState(accountId, SipAccountState.Disconnected)
                    logger.warn { "[$accountId] Auth failed — skipping reconnection" }
                } else {
                    updateAccountState(accountId, SipAccountState.Disconnected)
                    scheduleReconnect(accountId)
                }
            }
            is PjsipRegistrationState.Idle, is PjsipRegistrationState.Registering -> {}
        }
    }

    override suspend fun registerAll(accounts: List<SipAccountInfo>): Result<Unit> {
        if (accounts.isEmpty()) {
            return Result.failure(IllegalArgumentException("No accounts to register"))
        }
        accounts.forEach { info -> credentialCache[info.id] = info }

        _accounts.value = accounts.map { info ->
            SipAccount(
                id = info.id,
                name = info.name,
                credentials = info.credentials,
                state = SipAccountState.Disconnected,
            )
        }

        var successCount = 0
        var lastError: Throwable? = null
        accounts.forEachIndexed { index, info ->
            if (index > 0) delay(REGISTER_DELAY_MS)
            val result = withContext(pjDispatcher) {
                accountManager.register(info.id, info.credentials)
            }
            if (result.isSuccess) {
                successCount++
            } else {
                lastError = result.exceptionOrNull()
                logger.warn { "[${info.id}] Registration call failed: ${lastError?.message}" }
            }
        }
        return if (successCount > 0) {
            logger.info { "registerAll: $successCount/${accounts.size} accounts submitted successfully" }
            Result.success(Unit)
        } else {
            logger.error { "registerAll: all ${accounts.size} accounts failed" }
            Result.failure(lastError ?: IllegalStateException("All accounts failed to register"))
        }
    }

    override suspend fun connect(accountId: String): Result<Unit> {
        val info = credentialCache[accountId]
            ?: return Result.failure(IllegalStateException("No cached credentials for $accountId"))
        cancelReconnect(accountId)
        reconnectAttempts.remove(accountId)
        return withContext(pjDispatcher) {
            accountManager.register(accountId, info.credentials)
        }
    }

    override suspend fun disconnect(accountId: String): Result<Unit> {
        if (callEngine.callState.value.activeAccountId == accountId) {
            return Result.failure(
                IllegalStateException("Cannot disconnect account $accountId — active call in progress"),
            )
        }
        cancelReconnect(accountId)
        reconnectAttempts.remove(accountId)
        withContext(pjDispatcher) { accountManager.unregister(accountId) }
        updateAccountState(accountId, SipAccountState.Disconnected)
        return Result.success(Unit)
    }

    override suspend fun unregisterAll() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        reconnectAttempts.clear()
        withContext(pjDispatcher) { accountManager.unregisterAll() }
        _accounts.update { list -> list.map { it.copy(state = SipAccountState.Disconnected) } }
        credentialCache.clear()
    }

    fun destroy() {
        scope.cancel()
    }

    private fun updateAccountState(accountId: String, state: SipAccountState) {
        _accounts.update { list ->
            list.map { account ->
                if (account.id == accountId) account.copy(state = state) else account
            }
        }
    }

    private fun scheduleReconnect(accountId: String) {
        if (reconnectJobs[accountId]?.isActive == true) return
        val info = credentialCache[accountId] ?: run {
            logger.error { "[$accountId] Cannot reconnect — no cached credentials" }
            return
        }
        reconnectJobs[accountId] = scope.launch {
            while (true) {
                val attempt = (reconnectAttempts[accountId] ?: 0) + 1
                reconnectAttempts[accountId] = attempt
                val backoffMs = calculateBackoff(attempt)
                updateAccountState(accountId, SipAccountState.Reconnecting(attempt, backoffMs))
                logger.info { "[$accountId] Reconnecting (attempt $attempt, backoff ${backoffMs / 1000}s)" }
                delay(backoffMs)
                val result = withContext(pjDispatcher) {
                    accountManager.register(accountId, info.credentials)
                }
                if (result.isSuccess) break
                logger.warn { "[$accountId] Reconnect attempt $attempt failed: ${result.exceptionOrNull()?.message}" }
            }
        }
    }

    private fun cancelReconnect(accountId: String) {
        reconnectJobs[accountId]?.cancel()
        reconnectJobs.remove(accountId)
    }

    companion object {
        private const val BASE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 30_000L
        private const val JITTER_BOUND_MS = 500
        private const val REGISTER_DELAY_MS = 500L

        internal fun calculateBackoff(attempt: Int): Long {
            val exponential = BASE_DELAY_MS * (1L shl min(attempt - 1, 20))
            val capped = min(exponential, MAX_DELAY_MS)
            val jitter = Random.nextLong(JITTER_BOUND_MS.toLong())
            return capped + jitter
        }
    }
}
