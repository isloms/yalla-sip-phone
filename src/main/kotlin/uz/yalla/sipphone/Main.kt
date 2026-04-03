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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.startKoin
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.di.appModule
import uz.yalla.sipphone.domain.SipEngine
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent
import uz.yalla.sipphone.navigation.RootComponent
import uz.yalla.sipphone.navigation.RootContent
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

private val logger = KotlinLogging.logger {}

fun main() {
    // 1. Start Koin
    val koin = startKoin {
        modules(appModule)
    }.koin

    // 2. Init pjsip (with error handling)
    val sipEngine: SipEngine = koin.get()
    val initResult = runBlocking { sipEngine.init() }

    if (initResult.isFailure) {
        // Show error dialog before any Compose window
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Failed to initialize SIP engine:\n${initResult.exceptionOrNull()?.message}",
            "Yalla SIP Phone - Error",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        return
    }

    // 3. Add shutdown hook (defense against force-kill)
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            withTimeoutOrNull(2000) { sipEngine.destroy() }
        }
    })

    // 4. Create Decompose lifecycle + root component
    val lifecycle = LifecycleRegistry()
    val appSettings: AppSettings = koin.get()
    val rootComponent = RootComponent(
        componentContext = DefaultComponentContext(lifecycle = lifecycle),
        registrationFactory = { ctx, onRegistered ->
            RegistrationComponent(ctx, sipEngine, appSettings, onRegistered)
        },
        dialerFactory = { ctx, onDisconnected ->
            DialerComponent(ctx, sipEngine, onDisconnected)
        },
    )

    // 5. Launch Compose window
    application {
        val windowState = rememberWindowState(
            size = DpSize(420.dp, 600.dp),
            position = WindowPosition(Alignment.Center),
        )

        Window(
            onCloseRequest = {
                runBlocking {
                    withTimeoutOrNull(3000) { sipEngine.destroy() }
                }
                exitApplication()
            },
            title = "Yalla SIP Phone",
            state = windowState,
        ) {
            // Enforce minimum window size
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
