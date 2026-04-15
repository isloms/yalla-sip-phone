package uz.yalla.sipphone.demo.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scripted walk through the full update state catalog.
 * Supports Pause / Resume / Reset via an AtomicBoolean checked between
 * delay ticks. Total runtime ~40s.
 */
class UpdateDemoAutoPlay(
    private val driver: UpdateDemoDriver,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val paused = AtomicBoolean(false)

    fun start() {
        if (job?.isActive == true) return
        paused.set(false)
        job = scope.launch {
            runFullScript()
        }
    }

    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }

    fun reset() {
        job?.cancel()
        job = null
        paused.set(false)
        driver.reset()
    }

    private suspend fun runFullScript() {
        driver.reset()
        waitWhilePaused(500)

        driver.showChecking()
        waitWhilePaused(1_500)

        for (p in 0..100 step 10) {
            driver.showDownloading(p)
            waitWhilePaused(500)
        }

        driver.showVerifying()
        waitWhilePaused(1_500)

        driver.showReady()
        waitWhilePaused(3_000)

        driver.toggleCallActive()
        waitWhilePaused(3_000)

        driver.toggleCallActive()
        waitWhilePaused(2_000)

        driver.showInstalling()
        waitWhilePaused(3_000)

        driver.reset()
        waitWhilePaused(1_500)

        val failures: List<() -> Unit> = listOf(
            { driver.failMalformed() },
            { driver.failVerify() },
            { driver.failDownload() },
            { driver.failDiskFull() },
            { driver.failUntrustedUrl() },
        )
        for (fail in failures) {
            fail()
            waitWhilePaused(3_000)
            driver.reset()
            waitWhilePaused(1_000)
        }
    }

    private suspend fun waitWhilePaused(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0 && scope.isActive) {
            delay(100)
            if (!paused.get()) remaining -= 100
        }
    }
}
