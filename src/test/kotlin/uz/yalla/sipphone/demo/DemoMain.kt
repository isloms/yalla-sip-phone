package uz.yalla.sipphone.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.feature.main.toolbar.ToolbarComponent
import uz.yalla.sipphone.feature.main.toolbar.ToolbarContent
import uz.yalla.sipphone.testing.engine.ScriptableCallEngine
import uz.yalla.sipphone.testing.engine.ScriptableRegistrationEngine
import uz.yalla.sipphone.testing.scenario.ScenarioRunner
import uz.yalla.sipphone.testing.scenario.busyOperatorDay
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Standalone visual demo: opens a Compose Desktop window showing ONLY the
 * toolbar strip, driven by [busyOperatorDay] scenario with real delays.
 *
 * Run with:  ./gradlew runDemo
 *
 * No Koin, no JCEF, no pjsip, no login — just the toolbar + fake engines.
 */
fun main() {
    val callEngine = ScriptableCallEngine()
    val registrationEngine = ScriptableRegistrationEngine()

    val toolbar = ToolbarComponent(
        callEngine = callEngine,
        registrationEngine = registrationEngine,
    )

    val runner = ScenarioRunner(callEngine, registrationEngine)
    val demoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logger = DemoLogger()

    // Observe state changes and print to console
    demoScope.launch {
        var previousCallState: CallState = CallState.Idle
        callEngine.callState.collect { newState ->
            val prev = previousCallState
            previousCallState = newState
            logger.logCallTransition(prev, newState)
        }
    }

    demoScope.launch {
        var previousRegState: RegistrationState = RegistrationState.Idle
        registrationEngine.registrationState.collect { newState ->
            val prev = previousRegState
            previousRegState = newState
            logger.logRegistrationTransition(prev, newState)
        }
    }

    // Launch the scenario
    demoScope.launch {
        logger.header()

        runner.run {
            busyOperatorDay(random = Random(42)) // seeded for reproducibility
        }

        logger.footer()
    }

    application {
        var isDarkTheme by remember { mutableStateOf(false) }

        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 80.dp),
            position = WindowPosition(Alignment.TopCenter),
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "DEMO MODE \u2014 Busy Operator Simulation",
            state = windowState,
            alwaysOnTop = true,
            resizable = true,
        ) {
            YallaSipPhoneTheme(isDark = isDarkTheme) {
                ToolbarContent(
                    component = toolbar,
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = { isDarkTheme = !isDarkTheme },
                    onLogout = { /* no-op in demo */ },
                )
            }
        }
    }
}

/**
 * Pretty-prints state transitions to the console with timestamps and emoji.
 */
private class DemoLogger {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun header() {
        println()
        println("=".repeat(60))
        println("  YALLA SIP PHONE \u2014 DEMO MODE")
        println("  Simulating a busy operator's day...")
        println("=".repeat(60))
        println()
    }

    fun footer() {
        println()
        println("=".repeat(60))
        println("  Demo complete. Close the window to exit.")
        println("=".repeat(60))
        println()
    }

    fun logCallTransition(prev: CallState, new: CallState) {
        val ts = timestamp()
        when {
            // Idle -> Ringing (inbound)
            prev is CallState.Idle && new is CallState.Ringing && !new.isOutbound -> {
                val name = new.callerName?.let { " ($it)" } ?: ""
                log("\uD83D\uDCDE", ts, "Incoming call from ${new.callerNumber}$name")
            }
            // Idle -> Ringing (outbound)
            prev is CallState.Idle && new is CallState.Ringing && new.isOutbound -> {
                val name = new.callerName?.let { " ($it)" } ?: ""
                log("\uD83D\uDCF1", ts, "Outbound call to ${new.callerNumber}$name")
            }
            // Ringing -> Active (answered)
            prev is CallState.Ringing && new is CallState.Active -> {
                log("\u2705", ts, "Call connected")
            }
            // Active -> Active (mute changed)
            prev is CallState.Active && new is CallState.Active && prev.isMuted != new.isMuted -> {
                if (new.isMuted) {
                    log("\uD83D\uDD07", ts, "Muted")
                } else {
                    log("\uD83D\uDD0A", ts, "Unmuted")
                }
            }
            // Active -> Active (hold changed)
            prev is CallState.Active && new is CallState.Active && prev.isOnHold != new.isOnHold -> {
                if (new.isOnHold) {
                    log("\u23F8\uFE0F", ts, "On hold")
                } else {
                    log("\u25B6\uFE0F", ts, "Resumed from hold")
                }
            }
            // Any -> Ending
            new is CallState.Ending -> {
                log("\uD83D\uDCF4", ts, "Call ending...")
            }
            // Ringing -> Idle (missed/abandoned)
            prev is CallState.Ringing && new is CallState.Idle -> {
                log("\u274C", ts, "Call missed (no answer)")
            }
            // Ending -> Idle (call ended normally)
            prev is CallState.Ending && new is CallState.Idle -> {
                log("\uD83D\uDCF4", ts, "Call ended")
            }
            // Active -> Idle (direct hangup without Ending, e.g. network drop)
            prev is CallState.Active && new is CallState.Idle -> {
                log("\uD83D\uDCF4", ts, "Call dropped")
            }
        }
    }

    fun logRegistrationTransition(prev: RegistrationState, new: RegistrationState) {
        val ts = timestamp()
        when {
            new is RegistrationState.Registered -> {
                val server = new.server.removePrefix("sip:")
                log("\uD83D\uDFE2", ts, "Registered as $server")
            }
            new is RegistrationState.Registering && prev !is RegistrationState.Registering -> {
                log("\uD83D\uDD04", ts, "Reconnecting...")
            }
            new is RegistrationState.Idle && prev is RegistrationState.Registered -> {
                log("\uD83D\uDD34", ts, "Network disconnected!")
            }
            new is RegistrationState.Idle && prev is RegistrationState.Failed -> {
                log("\uD83D\uDD34", ts, "Disconnected")
            }
            new is RegistrationState.Failed -> {
                log("\uD83D\uDD34", ts, "Registration failed: ${new.error.displayMessage}")
            }
        }
    }

    private fun timestamp(): String = LocalTime.now().format(timeFormat)

    private fun log(emoji: String, ts: String, message: String) {
        println("$emoji [$ts] $message")
    }
}
