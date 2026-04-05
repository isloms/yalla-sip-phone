package uz.yalla.sipphone

import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefSettings
import java.awt.BorderLayout
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities

fun main() {
    SwingUtilities.invokeLater {
        val builder = CefAppBuilder()
        builder.setInstallDir(File("jcef-bundle"))

        val cacheDir = File(System.getProperty("user.home"), ".yalla-sip-phone/cef-cache-test")
        cacheDir.mkdirs()

        builder.cefSettings.apply {
            windowless_rendering_enabled = false
            log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING
            root_cache_path = cacheDir.absolutePath
            cache_path = File(cacheDir, "default").absolutePath
        }
        builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})

        val cefApp = builder.build()
        val client = cefApp.createClient()
        val browser = client.createBrowser("https://www.google.com", false, false)

        val frame = JFrame("JCEF Test")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.setSize(1024, 768)
        frame.add(browser.uiComponent, BorderLayout.CENTER)
        frame.isVisible = true

        println("JCEF test window opened. Browser component: ${browser.uiComponent}")
        println("Component size: ${browser.uiComponent.size}")
    }
}
