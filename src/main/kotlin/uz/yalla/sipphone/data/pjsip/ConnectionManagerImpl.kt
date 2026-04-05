package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.ConnectionManager
import uz.yalla.sipphone.domain.ConnectionState
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipCredentials
import kotlin.math.min
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

class ConnectionManagerImpl(
    private val registrationEngine: RegistrationEngine,
) : ConnectionManager {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var monitorScope: CoroutineScope? = null
    private var retryJob: Job? = null
    private var cachedCredentials: SipCredentials? = null
    private var attempt = 0

    override fun startMonitoring(credentials: SipCredentials) {
        stopMonitoring()
        cachedCredentials = credentials
        attempt = 0

        val scope = CoroutineScope(SupervisorJob())
        monitorScope = scope

        scope.launch {
            registrationEngine.registrationState.collect { state ->
                handleRegistrationState(state)
            }
        }

        logger.info { "Connection monitoring started for ${credentials.username}@${credentials.server}" }
    }

    override fun stopMonitoring() {
        retryJob?.cancel()
        retryJob = null
        monitorScope?.cancel()
        monitorScope = null
        attempt = 0
        _connectionState.value = ConnectionState.Disconnected
        logger.info { "Connection monitoring stopped" }
    }

    private fun handleRegistrationState(state: RegistrationState) {
        when (state) {
            is RegistrationState.Registered -> {
                retryJob?.cancel()
                retryJob = null
                attempt = 0
                _connectionState.value = ConnectionState.Connected
                logger.info { "Connection established — registered on ${state.server}" }
            }

            is RegistrationState.Failed -> {
                if (retryJob?.isActive == true) return
                _connectionState.value = ConnectionState.Disconnected
                logger.warn { "Registration failed: ${state.error} — starting reconnect" }
                scheduleReconnect()
            }

            is RegistrationState.Idle,
            is RegistrationState.Registering -> {
                // No action needed — transient states during registration flow
            }
        }
    }

    private fun scheduleReconnect() {
        val credentials = cachedCredentials ?: run {
            logger.error { "Cannot reconnect — no cached credentials" }
            return
        }
        val scope = monitorScope ?: return

        retryJob?.cancel()
        retryJob = scope.launch {
            while (true) {
                attempt++
                val backoffMs = calculateBackoff(attempt)
                _connectionState.value = ConnectionState.Reconnecting(attempt, backoffMs)
                logger.info { "Reconnect attempt $attempt — waiting ${backoffMs}ms" }

                delay(backoffMs)

                logger.info { "Reconnect attempt $attempt — registering..." }
                val result = registrationEngine.register(credentials)

                if (result.isSuccess) {
                    // Registration call succeeded (account created).
                    // Actual registration result comes via registrationState callback,
                    // so we break and let the collector handle Registered/Failed.
                    break
                }

                // register() itself threw — bump attempt and loop
                logger.warn { "Reconnect attempt $attempt failed: ${result.exceptionOrNull()?.message}" }
            }
        }
    }

    companion object {
        private const val BASE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 30_000L
        private const val JITTER_BOUND_MS = 500

        internal fun calculateBackoff(attempt: Int): Long {
            val exponential = BASE_DELAY_MS * (1L shl min(attempt - 1, 20))
            val capped = min(exponential, MAX_DELAY_MS)
            val jitter = Random.nextLong(JITTER_BOUND_MS.toLong())
            return capped + jitter
        }
    }
}
