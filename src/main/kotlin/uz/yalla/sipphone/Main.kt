package uz.yalla.sipphone

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.AWTEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.startKoin
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.data.auth.AuthEventBus
import uz.yalla.sipphone.data.auth.LogoutOrchestrator
import uz.yalla.sipphone.data.jcef.JcefManager
import uz.yalla.sipphone.data.update.UpdateManager
import uz.yalla.sipphone.di.appModules
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.SipConstants
import uz.yalla.sipphone.domain.SipStackLifecycle
import uz.yalla.sipphone.navigation.ComponentFactory
import uz.yalla.sipphone.navigation.RootComponent
import uz.yalla.sipphone.navigation.RootContent
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.YallaSipPhoneTheme

private val logger = KotlinLogging.logger {}

fun main() {
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("compose.layers.type", "WINDOW")

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        logger.error(throwable) { "Uncaught exception on ${thread.name}" }
    }

    val koin = startKoin { modules(appModules) }.koin

    val updateManager: UpdateManager = koin.get()
    updateManager.start()

    val lifecycle: SipStackLifecycle = koin.get()
    val initResult = runBlocking { lifecycle.initialize() }

    if (initResult.isFailure) {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            Strings.errorInitMessage(initResult.exceptionOrNull()?.message),
            Strings.ERROR_INIT_TITLE,
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        return
    }

    val jcefManager: JcefManager = koin.get()
    jcefManager.initialize(debugPort = 9222)

    val jcefShutdownDone = AtomicBoolean(false)

    fun gracefulShutdown() {
        updateManager.stop()
        runBlocking {
            withTimeoutOrNull(SipConstants.Timeout.DESTROY_MS) { lifecycle.shutdown() }
        }
        if (jcefShutdownDone.compareAndSet(false, true)) {
            jcefManager.shutdown()
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread(::gracefulShutdown))

    val decomposeLifecycle = LifecycleRegistry()
    val factory: ComponentFactory = koin.get()
    val authEventBus: AuthEventBus = koin.get()
    val logoutOrchestrator: LogoutOrchestrator = koin.get()
    val rootComponent = runOnUiThread {
        RootComponent(
            componentContext = DefaultComponentContext(lifecycle = decomposeLifecycle),
            factory = factory,
            authEventBus = authEventBus,
            logoutOrchestrator = logoutOrchestrator,
        )
    }

    val appSettings = koin.get<AppSettings>()

    application {
        var isDarkTheme by remember { mutableStateOf(appSettings.isDarkTheme) }
        var locale by remember { mutableStateOf(appSettings.locale) }

        val childStack by rootComponent.childStack.subscribeAsState()
        val isMainScreen = childStack.active.instance is RootComponent.Child.Main

        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 720.dp),
            position = WindowPosition(Alignment.Center),
        )

        val agentName = (childStack.active.instance as? RootComponent.Child.Main)
            ?.component?.agentInfo?.name.orEmpty()
        val windowTitle = if (isMainScreen) {
            "${Strings.APP_TITLE} \u2014 $agentName"
        } else {
            Strings.APP_TITLE
        }

        Window(
            onCloseRequest = {
                gracefulShutdown()
                exitApplication()
            },
            title = windowTitle,
            state = windowState,
            alwaysOnTop = false,
            resizable = isMainScreen,
        ) {
            LaunchedEffect(isMainScreen) {
                javax.swing.SwingUtilities.invokeLater {
                    window.minimumSize = java.awt.Dimension(1280, 720)
                }
            }

            // AWT-level shortcuts — bypasses Compose/JCEF focus issues
            DisposableEffect(Unit) {
                val listener = java.awt.event.AWTEventListener { event ->
                    if (event is KeyEvent && event.id == KeyEvent.KEY_PRESSED) {
                        handleKeyboardShortcut(event, rootComponent)
                    }
                }
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK)
                onDispose {
                    java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
                }
            }

            LifecycleController(decomposeLifecycle, windowState)

            YallaSipPhoneTheme(isDark = isDarkTheme, locale = locale) {
                RootContent(
                    root = rootComponent,
                    isDarkTheme = isDarkTheme,
                    locale = locale,
                    onThemeToggle = {
                        isDarkTheme = !isDarkTheme
                        appSettings.isDarkTheme = isDarkTheme
                    },
                    onLocaleChange = { newLocale ->
                        locale = newLocale
                        appSettings.locale = newLocale
                    },
                )
            }
        }
    }
}

private fun handleKeyboardShortcut(event: KeyEvent, rootComponent: RootComponent) {
    val ctrl = event.isControlDown
    val shift = event.isShiftDown

    val currentChild = rootComponent.childStack.value.active.instance
    if (currentChild !is RootComponent.Child.Main) return
    val toolbar = currentChild.component.toolbar
    val callState = toolbar.callState.value

    when {
        ctrl && event.keyCode == KeyEvent.VK_ENTER
            && callState is CallState.Ringing && !callState.isOutbound -> {
            toolbar.answerCall()
            event.consume()
        }
        ctrl && shift && event.keyCode == KeyEvent.VK_E -> {
            when (callState) {
                is CallState.Ringing -> toolbar.rejectCall()
                is CallState.Active -> toolbar.hangupCall()
                else -> {}
            }
            event.consume()
        }
        ctrl && !shift && event.keyCode == KeyEvent.VK_M && callState is CallState.Active -> {
            toolbar.toggleMute()
            event.consume()
        }
        ctrl && !shift && event.keyCode == KeyEvent.VK_H && callState is CallState.Active -> {
            toolbar.toggleHold()
            event.consume()
        }
        ctrl && !shift && event.keyCode == KeyEvent.VK_L && callState is CallState.Idle -> {
            toolbar.requestPhoneInputFocus()
            event.consume()
        }
        ctrl && shift && event.isAltDown && event.keyCode == KeyEvent.VK_B -> {
            // Hidden channel toggle (Ctrl+Shift+Alt+B) — cycles stable ↔ beta.
            val settings: AppSettings =
                org.koin.java.KoinJavaComponent.get(AppSettings::class.java)
            settings.updateChannel = if (settings.updateChannel == "beta") "stable" else "beta"
            logger.info { "Update channel toggled: ${settings.updateChannel}" }
            event.consume()
        }
        ctrl && shift && event.isAltDown && event.keyCode == KeyEvent.VK_D -> {
            // Hidden diagnostics toggle (Ctrl+Shift+Alt+D) — logs snapshot.
            val um: UpdateManager =
                org.koin.java.KoinJavaComponent.get(UpdateManager::class.java)
            val settings: AppSettings =
                org.koin.java.KoinJavaComponent.get(AppSettings::class.java)
            logger.info {
                "=== UPDATE DIAGNOSTICS ===\n" +
                    "installId=${settings.installId}\n" +
                    "channel=${settings.updateChannel}\n" +
                    "state=${um.state.value}\n" +
                    "lastCheck=${um.lastCheckMillis()}\n" +
                    "lastError=${um.lastErrorMessage()}\n"
            }
            event.consume()
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
