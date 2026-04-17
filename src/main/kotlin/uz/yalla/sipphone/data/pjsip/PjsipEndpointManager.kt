package uz.yalla.sipphone.data.pjsip

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pjsip_transport_type_e
import uz.yalla.sipphone.domain.SipConstants
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

class PjsipEndpointManager(private val pjDispatcher: CoroutineContext) {

    lateinit var endpoint: Endpoint
        private set

    private val scope = CoroutineScope(SupervisorJob() + pjDispatcher)
    private var pollJob: Job? = null
    private var logWriter: PjsipLogWriter? = null

    fun initEndpoint() {
        endpoint = Endpoint()
        endpoint.libCreate()

        val epConfig = EpConfig()
        try {
            epConfig.uaConfig.threadCnt = 0
            epConfig.uaConfig.mainThreadOnly = false
            epConfig.uaConfig.userAgent = SipConstants.USER_AGENT

            logWriter = PjsipLogWriter()
            epConfig.logConfig.writer = logWriter
            epConfig.logConfig.level = 3
            epConfig.logConfig.consoleLevel = 3

            endpoint.libInit(epConfig)
        } finally {
            epConfig.delete()
        }
    }

    fun createTransports() {
        val transportConfig = TransportConfig()
        try {
            transportConfig.port = 0
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig)
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, transportConfig)
        } finally {
            transportConfig.delete()
        }
    }

    fun startLibrary() {
        endpoint.libStart()

        val version = endpoint.libVersion()
        logger.info { "pjsip initialized, version: ${version.full}" }
        version.delete()

        logAudioDevices()
    }

    fun startPolling() {
        pollJob = scope.launch(pjDispatcher) {
            if (!endpoint.libIsThreadRegistered()) {
                endpoint.libRegisterThread("pjsip-poll")
            }
            while (isActive) {
                endpoint.libHandleEvents(SipConstants.POLL_INTERVAL_MS.toLong())
                yield()
            }
        }
    }

    suspend fun stopPolling() {
        pollJob?.cancel()
        pollJob?.join()
        pollJob = null
    }

    fun getPlaybackDevMedia(): AudioMedia = endpoint.audDevManager().playbackDevMedia

    fun getCaptureDevMedia(): AudioMedia = endpoint.audDevManager().captureDevMedia

    fun destroy() {
        scope.cancel()
        try {
            // Force GC to release any SWIG pointers before destroying
            System.gc()
            // libDestroy still uses the logWriter for shutdown logging — do NOT delete it before this call
            endpoint.libDestroy()
        } catch (e: Exception) {
            logger.warn(e) { "libDestroy failed (may be partially destroyed)" }
        }
        // Now safe to clean up logWriter — pjsip no longer references it
        try { logWriter?.delete() } catch (_: Exception) {}
        logWriter = null
        try {
            endpoint.delete()
        } catch (e: Exception) {
            logger.warn(e) { "endpoint.delete failed (libDestroy may have cleaned it)" }
        }
    }

    private fun logAudioDevices() {
        val adm = endpoint.audDevManager()
        logger.info { "Audio capture device: ${adm.captureDev}, playback device: ${adm.playbackDev}" }
        val devices = adm.enumDev2()
        try {
            for (j in 0 until devices.size) {
                val dev = devices[j]
                logger.info { "Audio device[$j]: '${dev.name}' in=${dev.inputCount} out=${dev.outputCount}" }
            }
        } finally {
            devices.delete()
        }
    }
}
