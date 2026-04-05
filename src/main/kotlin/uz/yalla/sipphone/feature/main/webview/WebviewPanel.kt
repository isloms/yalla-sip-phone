package uz.yalla.sipphone.feature.main.webview

import androidx.compose.runtime.Composable
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
    // Browser MUST be created outside SwingPanel's factory.
    // SwingPanel uses a SwingInteropViewGroup with layout=null, and only sets
    // bounds on the direct child returned by factory. When we wrapped
    // browser.uiComponent in an extra JPanel, the browser's internal component
    // never received proper layout propagation on macOS — the native CEF window
    // got attached with zero/incorrect geometry, resulting in a blank browser
    // and continuous "No task runner for threadId 0" warnings.
    //
    // Returning browser.uiComponent directly lets Compose set bounds on the
    // browser's own JPanel, which CefBrowserWr uses as its macOS canvas.
    val browser = remember(dispatcherUrl) {
        jcefManager.createBrowser(dispatcherUrl)
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            browser.uiComponent
        },
    )
}
