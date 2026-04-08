package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipAccount
import uz.yalla.sipphone.domain.SipAccountInfo
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.domain.SipError
import kotlin.math.min
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

/**
 * Production implementation of [SipAccountManager] that wraps [PjsipAccountManager]
 * and adds per-account reconnection with exponential backoff, state management via
 * [StateFlow], active call protection, and credential caching for reconnection.
 *
 * All pjsip operations are dispatched to [pjDispatcher] (the single pjsip event-loop thread).
 * State exposed via [accounts] can be collected from any thread.
 *
 * Key behaviors:
 * - [registerAll]: registers accounts sequentially with 500ms delay between each.
 *   Returns success if at least one account succeeds. Failed accounts get auto-reconnection.
 * - [connect]: uses cached credentials to re-register a specific account.
 * - [disconnect]: blocks if the account has an active call.
 * - Auth failures ([SipError.AuthFailed]) skip reconnection — credentials are wrong, retrying won't help.
 * - Exponential backoff: base 1s, max 30s, jitter 500ms.
 */
class PjsipSipAccountManager(
    private val accountManager: PjsipAccountManager,
    private val callEngine: CallEngine,
    private val pjDispatcher: CoroutineDispatcher,
) : SipAccountManager, AccountRegistrationListener {

    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)

    /** Cached credentials per accountId for reconnection. */
    private val credentialCache = mutableMapOf<String, SipAccountInfo>()

    /** Per-account reconnection jobs. */
    private val reconnectJobs = mutableMapOf<String, Job>()

    /** Per-account reconnection attempt counters. */
    private val reconnectAttempts = mutableMapOf<String, Int>()

    /** Master account list state. Thread-safe via StateFlow. */
    private val _accounts = MutableStateFlow<List<SipAccount>>(emptyList())
    override val accounts: StateFlow<List<SipAccount>> = _accounts.asStateFlow()

    init {
        accountManager.accountRegistrationListener = this
    }

    /**
     * Called by [PjsipAccountManager] on the pjsip thread when a per-account registration
     * state changes. Maps pjsip [RegistrationState] to domain [SipAccountState] and
     * triggers reconnection on failure.
     */
    override fun onAccountRegistrationState(accountId: String, state: RegistrationState) {
        when (state) {
            is RegistrationState.Registered -> {
                cancelReconnect(accountId)
                reconnectAttempts.remove(accountId)
                updateAccountState(accountId, SipAccountState.Connected)
                logger.info { "[$accountId] Connected" }
            }

            is RegistrationState.Failed -> {
                val error = state.error
                if (error is SipError.AuthFailed) {
                    // Auth failures are permanent — don't reconnect
                    cancelReconnect(accountId)
                    reconnectAttempts.remove(accountId)
                    updateAccountState(accountId, SipAccountState.Disconnected)
                    logger.warn { "[$accountId] Auth failed — skipping reconnection" }
                } else {
                    // Transient failure — schedule reconnection
                    updateAccountState(accountId, SipAccountState.Disconnected)
                    scheduleReconnect(accountId)
                }
            }

            is RegistrationState.Idle -> {
                // Idle after explicit unregister — don't reconnect
                // State is already handled by disconnect/unregister methods
            }

            is RegistrationState.Registering -> {
                // Transient — no action needed
            }
        }
    }

    override suspend fun registerAll(accounts: List<SipAccountInfo>): Result<Unit> {
        val accountInfos = accounts
        if (accountInfos.isEmpty()) {
            return Result.failure(IllegalArgumentException("No accounts to register"))
        }

        // Cache credentials for all accounts
        for (info in accountInfos) {
            credentialCache[info.id] = info
        }

        // Initialize account list with Disconnected state
        _accounts.value = accountInfos.map { info ->
            SipAccount(
                id = info.id,
                name = info.name,
                credentials = info.credentials,
                state = SipAccountState.Disconnected,
            )
        }

        var successCount = 0
        var lastError: Throwable? = null

        for ((index, info) in accountInfos.withIndex()) {
            if (index > 0) {
                delay(REGISTER_DELAY_MS)
            }

            val result = withContext(pjDispatcher) {
                accountManager.register(info.id, info.credentials)
            }

            if (result.isSuccess) {
                successCount++
            } else {
                lastError = result.exceptionOrNull()
                logger.warn { "[${info.id}] Registration call failed: ${lastError?.message}" }
                // Failed accounts will get auto-reconnection via onAccountRegistrationState
            }
        }

        return if (successCount > 0) {
            logger.info { "registerAll: $successCount/${accountInfos.size} accounts submitted successfully" }
            Result.success(Unit)
        } else {
            logger.error { "registerAll: all ${accountInfos.size} accounts failed" }
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
        // Active call protection: can't disconnect account with active call
        val currentCallState = callEngine.callState.value
        val callAccountId = when (currentCallState) {
            is CallState.Ringing -> currentCallState.accountId
            is CallState.Active -> currentCallState.accountId
            is CallState.Ending -> currentCallState.accountId
            is CallState.Idle -> null
        }
        if (callAccountId == accountId) {
            return Result.failure(
                IllegalStateException("Cannot disconnect account $accountId — active call in progress"),
            )
        }

        cancelReconnect(accountId)
        reconnectAttempts.remove(accountId)

        withContext(pjDispatcher) {
            accountManager.unregister(accountId)
        }
        updateAccountState(accountId, SipAccountState.Disconnected)
        return Result.success(Unit)
    }

    override suspend fun unregisterAll() {
        // Cancel all reconnection jobs
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        reconnectAttempts.clear()

        withContext(pjDispatcher) {
            accountManager.unregisterAll()
        }

        // Update all accounts to Disconnected
        _accounts.value = _accounts.value.map { it.copy(state = SipAccountState.Disconnected) }
        credentialCache.clear()
    }

    /**
     * Updates the [SipAccountState] for a specific account in the [_accounts] list.
     */
    private fun updateAccountState(accountId: String, state: SipAccountState) {
        _accounts.value = _accounts.value.map { account ->
            if (account.id == accountId) account.copy(state = state) else account
        }
    }

    /**
     * Schedules exponential backoff reconnection for a specific account.
     * No-op if a reconnection job is already active for this account.
     */
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
                logger.info { "[$accountId] Reconnect attempt $attempt — waiting ${backoffMs}ms" }

                delay(backoffMs)

                logger.info { "[$accountId] Reconnect attempt $attempt — registering..." }
                val result = withContext(pjDispatcher) {
                    accountManager.register(accountId, info.credentials)
                }

                if (result.isSuccess) {
                    // Registration call succeeded (account created).
                    // Actual result comes via onAccountRegistrationState callback.
                    break
                }

                // register() itself threw — bump attempt and loop
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
