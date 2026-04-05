package uz.yalla.sipphone.feature.main.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.data.jcef.JcefManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

private val logger = KotlinLogging.logger {}

@Composable
fun WebviewPanel(
    jcefManager: JcefManager,
    dispatcherUrl: String,
    modifier: Modifier = Modifier,
) {
    val browser = remember(dispatcherUrl) {
        logger.info { "WebviewPanel: creating browser for $dispatcherUrl" }
        jcefManager.createBrowser(dispatcherUrl)
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            val component = browser.uiComponent
            logger.info { "WebviewPanel factory: component=${component.javaClass.name}, size=${component.width}x${component.height}, visible=${component.isVisible}, displayable=${component.isDisplayable}" }

            component.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    logger.info { "WebviewPanel: resized to ${component.width}x${component.height}" }
                }
                override fun componentShown(e: ComponentEvent) {
                    logger.info { "WebviewPanel: component shown" }
                }
            })

            component
        },
    )
}
