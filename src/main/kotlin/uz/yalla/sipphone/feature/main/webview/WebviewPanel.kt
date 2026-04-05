package uz.yalla.sipphone.feature.main.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import uz.yalla.sipphone.data.jcef.JcefManager

@Composable
fun WebviewPanel(
    jcefManager: JcefManager,
    dispatcherUrl: String,
    modifier: Modifier = Modifier,
) {
    val browser = remember(dispatcherUrl) {
        jcefManager.createBrowser(dispatcherUrl)
    }

    DisposableEffect(dispatcherUrl) {
        onDispose {
            if (!jcefManager.isClosed()) {
                browser.stopLoad()
                browser.close(false)
            }
        }
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            browser.uiComponent
        },
    )
}
