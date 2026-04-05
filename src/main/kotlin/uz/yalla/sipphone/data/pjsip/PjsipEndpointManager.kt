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

/**
 * Owns and manages the pjsua2 [Endpoint] SWIG object.
 *
 * Handles library lifecycle (create → init → transport → start → poll → destroy),
 * audio device enumeration, and the event-polling loop.
 *
 * Thread safety: all methods must be called on [pjDispatcher] (the pjsip event-loop thread).
 *
 * SWIG lifecycle: [destroy] must be called before the process exits.
 * The sequence is: [stopPolling] → [destroy]. Do NOT delete pjsua2 SWIG objects from outside
 * this class — ownership belongs here.
 */
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
            epConfig.logConfig.level = 3  // was 5 — level 5 exposes SIP auth headers in logs
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

    /**
     * Destroys the pjsua2 endpoint and frees all associated native resources.
     *
     * WARNING: Must be called only after [stopPolling] returns. Calling [destroy] while the
     * poll loop is running will crash the JVM. After this call the [endpoint] field is invalid
     * and must not be accessed again.
     *
     * SWIG lifecycle note: [logWriter] must NOT be deleted before [endpoint.libDestroy] because
     * pjsip writes shutdown logs through it. It is deleted here after libDestroy completes.
     */
    fun destroy() {
        scope.cancel()
        try {
            // Force GC to release any SWIG pointers before destroying
            System.gc()
            Thread.sleep(100)
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
