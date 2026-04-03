package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import org.pjsip.pjsua2.LogEntry
import org.pjsip.pjsua2.LogWriter

class PjsipLogWriter : LogWriter() {
    private val logger = KotlinLogging.logger("pjsip.native")

    override fun write(entry: LogEntry) {
        try {
            val msg = entry.msg.trimEnd()
            when (entry.level) {
                0, 1 -> logger.error { msg }
                2 -> logger.warn { msg }
                3 -> logger.info { msg }
                4 -> logger.debug { msg }
                else -> logger.trace { msg }
            }
        } catch (_: Exception) {
            // Native callback during shutdown, ignore
        }
    }
}
