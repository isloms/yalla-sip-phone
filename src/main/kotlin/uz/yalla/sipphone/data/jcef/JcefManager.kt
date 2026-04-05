package uz.yalla.sipphone.data.jcef

import io.github.oshai.kotlinlogging.KotlinLogging
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.io.File
import javax.swing.SwingUtilities

private val logger = KotlinLogging.logger {}

/**
 * Manages the CefApp/CefClient/CefBrowser lifecycle.
 *
 * Thread safety: all JCEF operations run on Swing EDT via [SwingUtilities.invokeAndWait].
 * CefApp is a strict JVM singleton — only one instance is ever created.
 */
class JcefManager {
    private var cefApp: CefApp? = null
    private var cefClient: CefClient? = null
    private var browser: CefBrowser? = null

    @Volatile
    private var isBrowserClosed = false

    val isInitialized: Boolean get() = cefApp != null

    /**
     * Initialize JCEF runtime. Must be called before [createBrowser].
     *
     * @param debugPort Chrome DevTools remote debugging port. 0 = disabled.
     * @throws IllegalStateException if CefApp.startup() fails
     */
    fun initialize(debugPort: Int = 0) {
        if (cefApp != null) return
        logger.info { "Initializing JCEF..." }

        SwingUtilities.invokeAndWait {
            val builder = CefAppBuilder()
            // Packaged app on Windows: Program Files is read-only, use user-writable dir
            // Dev mode: fall back to project-relative jcef-bundle/
            val resourcesDir = System.getProperty("compose.application.resources.dir")
            val jcefDir = if (resourcesDir != null) {
                val bundledDir = File(resourcesDir, "jcef-bundle")
                if (bundledDir.canWrite()) {
                    bundledDir
                } else {
                    // Copy to writable location if needed (Windows Program Files is read-only)
                    val userDir = File(System.getProperty("user.home"), ".yalla-sip-phone/jcef-bundle")
                    if (!userDir.exists() && bundledDir.exists()) {
                        logger.info { "Copying JCEF bundle to writable location: ${userDir.absolutePath}" }
                        bundledDir.copyRecursively(userDir, overwrite = true)
                    }
                    userDir
                }
            } else {
                File("jcef-bundle")
            }
            logger.info { "JCEF install dir: ${jcefDir.absolutePath} (exists=${jcefDir.exists()})" }
            builder.setInstallDir(jcefDir)

            builder.cefSettings.apply {
                windowless_rendering_enabled = false
                log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
                remote_debugging_port = if (debugPort > 0) debugPort else 0
            }

            builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})

            logger.info { "Building CefApp via jcefmaven (first run downloads ~100MB Chromium)..." }
            cefApp = builder.build()
            cefClient = cefApp!!.createClient()

            // Block all popup windows — dispatcher UI must stay in our single browser
            cefClient!!.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    targetUrl: String?,
                    targetFrameName: String?,
                ): Boolean {
                    logger.debug { "Blocked popup: $targetUrl" }
                    return true // true = cancel popup
                }
            })

            logger.info { "JCEF initialized successfully" }
        }
    }

    /**
     * Create an embedded browser for the given URL.
     * Windowed rendering (not OSR) for SwingPanel embedding.
     *
     * @return the created [CefBrowser] instance
     * @throws IllegalStateException if [initialize] hasn't been called
     */
    fun createBrowser(url: String): CefBrowser {
        val client = cefClient ?: throw IllegalStateException("JCEF not initialized — call initialize() first")
        isBrowserClosed = false

        val create = { browser = client.createBrowser(url, false, false) }
        if (SwingUtilities.isEventDispatchThread()) create() else SwingUtilities.invokeAndWait(create)

        logger.info { "Browser created for URL: $url" }
        return browser!!
    }

    /**
     * Install JS bridge infrastructure on the CefClient.
     *
     * Accepts lambdas instead of concrete BridgeRouter/BridgeEventEmitter types
     * so this class compiles independently of bridge implementation.
     *
     * @param installMessageRouter called with [CefClient] to install message router
     * @param onPageLoadEnd called with [CefBrowser] when main frame finishes loading (inject bridge script)
     * @param onPageLoadStart called when main frame starts loading (reset handshake)
     */
    fun setupBridge(
        installMessageRouter: (CefClient) -> Unit,
        onPageLoadEnd: (CefBrowser) -> Unit,
        onPageLoadStart: () -> Unit,
    ) {
        val client = cefClient ?: throw IllegalStateException("JCEF not initialized — call initialize() first")

        installMessageRouter(client)

        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    logger.info { "Page loaded (status=$httpStatusCode), injecting bridge script" }
                    onPageLoadEnd(browser)
                }
            }

            override fun onLoadStart(
                browser: CefBrowser,
                frame: CefFrame,
                transitionType: CefRequest.TransitionType,
            ) {
                if (frame.isMain) {
                    logger.info { "Page load started, resetting handshake" }
                    onPageLoadStart()
                }
            }
        })

        logger.info { "Bridge setup complete" }
    }

    fun getBrowser(): CefBrowser? = browser

    fun isClosed(): Boolean = isBrowserClosed

    /**
     * Shut down JCEF in the correct order:
     * 1. Stop browser load & close browser
     * 2. Dispose client
     * 3. Dispose CefApp
     *
     * All operations on EDT. After this, the manager can be re-initialized.
     */
    fun shutdown() {
        val app = cefApp ?: return
        logger.info { "Shutting down JCEF..." }

        val shutdownWork = Runnable {
            try {
                browser?.let { b ->
                    b.stopLoad()
                    b.close(true)
                    isBrowserClosed = true
                }
                browser = null

                cefClient?.dispose()
                cefClient = null
                app.dispose()
                cefApp = null
            } catch (e: Exception) {
                logger.warn(e) { "JCEF shutdown error (non-fatal)" }
                cefApp = null
                cefClient = null
                browser = null
            }
        }

        if (SwingUtilities.isEventDispatchThread()) {
            shutdownWork.run()
        } else {
            try {
                SwingUtilities.invokeAndWait(shutdownWork)
            } catch (e: Exception) {
                logger.warn(e) { "JCEF shutdown via EDT failed (non-fatal)" }
                cefApp = null
                cefClient = null
                browser = null
            }
        }

        logger.info { "JCEF shutdown complete" }
    }
}
