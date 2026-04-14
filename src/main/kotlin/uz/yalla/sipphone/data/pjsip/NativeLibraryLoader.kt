package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.domain.SipConstants
import java.io.File

private val logger = KotlinLogging.logger {}

object NativeLibraryLoader {

    fun load() {
        val osName = System.getProperty("os.name").lowercase()
        val libName = when {
            osName.contains("mac") || osName.contains("darwin") -> SipConstants.NativeLib.MAC
            osName.contains("win") -> SipConstants.NativeLib.WINDOWS
            else -> SipConstants.NativeLib.LINUX
        }

        val devDir = System.getProperty("pjsip.library.path")
        if (devDir != null) {
            val devLib = File("$devDir/$libName")
            if (devLib.exists()) {
                System.load(devLib.absolutePath)
                logger.info { "Loaded native library from dev path: ${devLib.absolutePath}" }
                return
            }
        }

        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (resourcesDir != null) {
            val packagedLib = File("$resourcesDir/$libName")
            if (packagedLib.exists()) {
                System.load(packagedLib.absolutePath)
                logger.info { "Loaded native library from resources: ${packagedLib.absolutePath}" }
                return
            }
        }

        System.loadLibrary(SipConstants.NativeLib.FALLBACK)
        logger.info { "Loaded native library from system path: ${SipConstants.NativeLib.FALLBACK}" }
    }
}
