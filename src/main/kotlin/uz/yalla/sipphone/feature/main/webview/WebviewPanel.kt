package uz.yalla.sipphone.feature.main.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import uz.yalla.sipphone.data.jcef.JcefManager
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JPanel

@Composable
fun WebviewPanel(
    jcefManager: JcefManager,
    dispatcherUrl: String,
    modifier: Modifier = Modifier,
) {
    SwingPanel(
        modifier = modifier,
        factory = {
            val panel = JPanel(BorderLayout())
            val browser = jcefManager.createBrowser("about:blank")
            val component = browser.uiComponent
            panel.add(component, BorderLayout.CENTER)

            // Load URL after component is visible and has size
            panel.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    if (panel.width > 0 && panel.height > 0) {
                        panel.removeComponentListener(this)
                        browser.loadURL(dispatcherUrl)
                    }
                }
            })

            panel
        },
    )
}
