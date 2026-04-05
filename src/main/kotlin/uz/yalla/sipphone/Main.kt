package uz.yalla.sipphone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.AWTEvent
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.startKoin
import uz.yalla.sipphone.data.jcef.JcefManager
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
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        logger.error(throwable) { "Uncaught exception on ${thread.name}" }
    }

    val koin = startKoin { modules(appModules) }.koin

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

    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            withTimeoutOrNull(SipConstants.Timeout.DESTROY_MS) { lifecycle.shutdown() }
        }
        jcefManager.shutdown()
    })

    val decomposeLifecycle = LifecycleRegistry()
    val factory: ComponentFactory = koin.get()
    val rootComponent = runOnUiThread {
        RootComponent(
            componentContext = DefaultComponentContext(lifecycle = decomposeLifecycle),
            factory = factory,
        )
    }

    application {
        var isDarkTheme by remember { mutableStateOf(false) }

        val childStack by rootComponent.childStack.subscribeAsState()
        val isMainScreen = childStack.active.instance is RootComponent.Child.Main
        val mainComponent = (childStack.active.instance as? RootComponent.Child.Main)?.component

        val windowState = rememberWindowState(
            size = DpSize(420.dp, 520.dp),
            position = WindowPosition(Alignment.Center),
        )

        // Prevent minimize on main screen
        LaunchedEffect(isMainScreen, windowState.isMinimized) {
            if (isMainScreen && windowState.isMinimized) {
                windowState.isMinimized = false
            }
        }

        val windowTitle = if (isMainScreen) {
            "${Strings.APP_TITLE} \u2014 ${mainComponent?.agentInfo?.name.orEmpty()}"
        } else {
            Strings.APP_TITLE
        }

        var showLogoutConfirm by remember { mutableStateOf(false) }

        // Logout confirmation as a real OS dialog window (above alwaysOnTop main window)
        if (showLogoutConfirm) {
            DialogWindow(
                onCloseRequest = { showLogoutConfirm = false },
                title = Strings.SETTINGS_LOGOUT_CONFIRM_TITLE,
                state = rememberDialogState(size = DpSize(340.dp, 180.dp)),
                resizable = false,
                alwaysOnTop = true,
            ) {
                YallaSipPhoneTheme(isDark = isDarkTheme) {
                    val dlgColors = LocalYallaColors.current
                    val dlgTokens = LocalAppTokens.current
                    Surface(color = dlgColors.backgroundBase, modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.padding(dlgTokens.spacingLg),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = Strings.SETTINGS_LOGOUT_CONFIRM,
                                style = MaterialTheme.typography.bodyLarge,
                                color = dlgColors.textBase,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(dlgTokens.spacingSm, Alignment.End),
                            ) {
                                OutlinedButton(onClick = { showLogoutConfirm = false }) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        showLogoutConfirm = false
                                        runBlocking {
                                            withTimeoutOrNull(SipConstants.Timeout.DESTROY_MS) { lifecycle.shutdown() }
                                        }
                                        jcefManager.shutdown()
                                        exitApplication()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = dlgColors.errorIndicator),
                                ) {
                                    Text("Logout")
                                }
                            }
                        }
                    }
                }
            }
        }

        Window(
            onCloseRequest = {
                if (isMainScreen) {
                    showLogoutConfirm = true
                } else {
                    exitApplication()
                }
            },
            title = windowTitle,
            state = windowState,
            alwaysOnTop = isMainScreen,
            resizable = isMainScreen,
        ) {
            // ALL window property changes via AWT — Compose windowState alone can't resize from maximized
            LaunchedEffect(isMainScreen) {
                javax.swing.SwingUtilities.invokeLater {
                    if (isMainScreen) {
                        window.minimumSize = java.awt.Dimension(1280, 720)
                        (window as? java.awt.Frame)?.extendedState = java.awt.Frame.NORMAL
                        window.setSize(1280, 720)
                        window.setLocationRelativeTo(null)
                    } else {
                        // 1. Un-maximize (NORMAL state)
                        (window as? java.awt.Frame)?.extendedState = java.awt.Frame.NORMAL
                        // 2. Reset minimum size
                        window.minimumSize = java.awt.Dimension(380, 180)
                        // 3. Resize + center
                        window.setSize(420, 520)
                        window.setLocationRelativeTo(null)
                        window.isResizable = false
                    }
                }
            }

            // AWT-level keyboard shortcuts — work regardless of Compose/JCEF focus
            LaunchedEffect(Unit) {
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
                    if (event is KeyEvent && event.id == KeyEvent.KEY_PRESSED) {
                        val ctrl = event.isControlDown || event.isMetaDown
                        val shift = event.isShiftDown

                        val currentChild = rootComponent.childStack.value.active.instance
                        if (currentChild !is RootComponent.Child.Main) return@addAWTEventListener
                        val toolbar = (currentChild as RootComponent.Child.Main).component.toolbar
                        val callState = toolbar.callState.value

                        when {
                            // Ctrl+Enter = Answer incoming call
                            ctrl && event.keyCode == KeyEvent.VK_ENTER
                                && callState is CallState.Ringing && !callState.isOutbound -> {
                                toolbar.answerCall()
                                event.consume()
                            }
                            // Ctrl+Shift+E = Reject incoming or End active call
                            ctrl && shift && event.keyCode == KeyEvent.VK_E -> {
                                when (callState) {
                                    is CallState.Ringing -> toolbar.rejectCall()
                                    is CallState.Active -> toolbar.hangupCall()
                                    else -> {}
                                }
                                event.consume()
                            }
                            // Ctrl+M = Toggle mute (only during active call)
                            ctrl && !shift && event.keyCode == KeyEvent.VK_M
                                && callState is CallState.Active -> {
                                toolbar.toggleMute()
                                event.consume()
                            }
                            // Ctrl+H = Toggle hold (only during active call)
                            ctrl && !shift && event.keyCode == KeyEvent.VK_H
                                && callState is CallState.Active -> {
                                toolbar.toggleHold()
                                event.consume()
                            }
                            // Ctrl+L = Focus phone input (idle state only)
                            ctrl && !shift && event.keyCode == KeyEvent.VK_L
                                && callState is CallState.Idle -> {
                                // Focus on phone input — full focus management comes with JCEF
                                event.consume()
                            }
                        }
                    }
                }, AWTEvent.KEY_EVENT_MASK)
            }

            LifecycleController(decomposeLifecycle, windowState)

            YallaSipPhoneTheme(isDark = isDarkTheme) {
                RootContent(
                    root = rootComponent,
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = { isDarkTheme = !isDarkTheme },
                )
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
