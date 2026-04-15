package uz.yalla.sipphone.demo.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.update.UpdateState
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Prints timestamped transitions of [UpdateDemoDriver.state] and
 * [UpdateDemoDriver.callState] to stdout. Same vibe as the existing
 * DemoLogger in the SIP demo.
 */
class UpdateDemoConsoleLogger(
    private val driver: UpdateDemoDriver,
    private val scope: CoroutineScope,
) {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun start() {
        header()

        var previousStateKey = ""
        driver.state.onEach { newState ->
            val key = describe(newState)
            if (key != previousStateKey) {
                previousStateKey = key
                log(key)
            }
        }.launchIn(scope)

        var previousCallIdle = true
        driver.callState.onEach { newState ->
            val isIdle = newState is CallState.Idle
            if (isIdle != previousCallIdle) {
                previousCallIdle = isIdle
                log(if (isIdle) "Call ended — install enabled" else "Call became active — install deferred")
            }
        }.launchIn(scope)
    }

    private fun header() {
        println()
        println("=".repeat(60))
        println("  YALLA SIP PHONE — UPDATE DEMO")
        println("  Every state of the auto-update UI, no network calls.")
        println("=".repeat(60))
        println()
    }

    private fun describe(state: UpdateState): String = when (state) {
        is UpdateState.Idle -> "Idle"
        is UpdateState.Checking -> "Checking for updates..."
        is UpdateState.Downloading -> {
            val percent = if (state.total > 0) (state.bytesRead * 100 / state.total) else 0
            "Downloading v${state.release.version} ($percent%)"
        }
        is UpdateState.Verifying -> "Verifying v${state.release.version}"
        is UpdateState.ReadyToInstall -> "ReadyToInstall v${state.release.version}"
        is UpdateState.Installing -> "Installing v${state.release.version}"
        is UpdateState.Failed -> "Failed(${state.stage}): ${state.reason}"
    }

    private fun log(message: String) {
        println("[${LocalTime.now().format(timeFormat)}] $message")
    }
}
