package uz.yalla.sipphone.data.pjsip

import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReconnectController(
    private val scope: CoroutineScope,
    private val onAttempt: (attempt: Int, backoffMs: Long) -> Unit,
) {
    private var job: Job? = null
    private var attempts: Int = 0

    fun start(attemptBlock: suspend () -> Result<Unit>) {
        if (job?.isActive == true) return
        attempts = 0
        job = scope.launch {
            while (isActive) {
                val attempt = ++attempts
                val backoff = calculateBackoff(attempt)
                onAttempt(attempt, backoff)
                delay(backoff)
                if (attemptBlock().isSuccess) break
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        attempts = 0
    }

    companion object {
        private const val BASE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 30_000L
        private const val JITTER_BOUND_MS = 500L

        internal fun calculateBackoff(attempt: Int): Long {
            val exponential = BASE_DELAY_MS * (1L shl min(attempt - 1, 20))
            val capped = min(exponential, MAX_DELAY_MS)
            val jitter = Random.nextLong(JITTER_BOUND_MS)
            return capped + jitter
        }
    }
}
