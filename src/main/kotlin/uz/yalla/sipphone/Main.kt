package uz.yalla.sipphone

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.startKoin
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.di.appModule
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent
import uz.yalla.sipphone.navigation.RootComponent
import uz.yalla.sipphone.navigation.RootContent
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

private val logger = KotlinLogging.logger {}

fun main() {
    val koin = startKoin { modules(appModule) }.koin

    val registrationEngine: RegistrationEngine = koin.get()
    val callEngine: CallEngine = koin.get()
    val initResult = runBlocking { registrationEngine.init() }

    if (initResult.isFailure) {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Failed to initialize SIP engine:\n${initResult.exceptionOrNull()?.message}",
            "Yalla SIP Phone - Error",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        return
    }

    val lifecycle = LifecycleRegistry()
    val appSettings: AppSettings = koin.get()
    val rootComponent = runOnUiThread {
        RootComponent(
            componentContext = DefaultComponentContext(lifecycle = lifecycle),
            registrationFactory = { ctx, onRegistered ->
                RegistrationComponent(ctx, registrationEngine, appSettings, onRegistered)
            },
            dialerFactory = { ctx, onDisconnected ->
                DialerComponent(ctx, registrationEngine, callEngine, onDisconnected)
            },
        )
    }

    application {
        val windowState = rememberWindowState(
            size = DpSize(420.dp, 600.dp),
            position = WindowPosition(Alignment.Center),
        )

        Window(
            onCloseRequest = {
                runBlocking { withTimeoutOrNull(3000) { registrationEngine.destroy() } }
                exitApplication()
            },
            title = "Yalla SIP Phone",
            state = windowState,
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(380, 480)
            }

            LifecycleController(lifecycle, windowState)

            YallaSipPhoneTheme {
                RootContent(rootComponent)
            }
        }
    }
}

private fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()

    var error: Throwable? = null
    var result: T? = null

    SwingUtilities.invokeAndWait {
        try {
            result = block()
        } catch (e: Throwable) {
            error = e
        }
    }

    error?.let { throw it }

    @Suppress("UNCHECKED_CAST")
    return result as T
}
