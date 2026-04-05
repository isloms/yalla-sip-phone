package uz.yalla.sipphone.feature.main.toolbar

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.PhoneNumberValidator
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

private val logger = KotlinLogging.logger {}

class ToolbarComponent(
    val callEngine: CallEngine,
    val registrationEngine: RegistrationEngine,
) {
    val callState: StateFlow<CallState> = callEngine.callState
    val registrationState: StateFlow<RegistrationState> = registrationEngine.registrationState

    private val _agentStatus = MutableStateFlow(AgentStatus.READY)
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _phoneInput = MutableStateFlow("")
    val phoneInput: StateFlow<String> = _phoneInput.asStateFlow()

    // Incremented to trigger focus request from UI
    private val _phoneInputFocusRequest = MutableStateFlow(0)
    val phoneInputFocusRequest: StateFlow<Int> = _phoneInputFocusRequest.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var ringtoneClip: Clip? = null

    init {
        scope.launch {
            callEngine.callState.collect { state ->
                when {
                    state is CallState.Ringing && !state.isOutbound -> {
                        playRingtone()
                        showIncomingNotification()
                    }
                    else -> stopRingtone()
                }
            }
        }
    }

    fun setAgentStatus(status: AgentStatus) {
        _agentStatus.value = status
    }

    fun updatePhoneInput(value: String) {
        _phoneInput.value = value
    }

    fun makeCall(number: String): Boolean {
        val validation = PhoneNumberValidator.validate(number)
        if (validation.isFailure) {
            logger.warn { "Invalid phone number" }
            return false
        }
        scope.launch { callEngine.makeCall(validation.getOrThrow()) }
        return true
    }

    fun answerCall() {
        stopRingtone()
        scope.launch { callEngine.answerCall() }
    }

    fun rejectCall() {
        stopRingtone()
        scope.launch { callEngine.hangupCall() }
    }

    fun hangupCall() {
        scope.launch { callEngine.hangupCall() }
    }

    fun toggleMute() {
        scope.launch { callEngine.toggleMute() }
    }

    fun toggleHold() {
        scope.launch { callEngine.toggleHold() }
    }

    fun requestPhoneInputFocus() {
        _phoneInputFocusRequest.value++
    }

    fun disconnect() {
        scope.launch { registrationEngine.unregister() }
    }

    fun destroy() {
        stopRingtone()
        scope.cancel()
    }

    private fun playRingtone() {
        try {
            stopRingtone()
            val resourceStream = javaClass.getResourceAsStream("/ringtone.wav") ?: return
            val audioStream = AudioSystem.getAudioInputStream(resourceStream)
            ringtoneClip = AudioSystem.getClip().apply {
                open(audioStream)
                loop(Clip.LOOP_CONTINUOUSLY)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to play ringtone" }
        }
    }

    private fun stopRingtone() {
        ringtoneClip?.let { clip ->
            if (clip.isRunning) clip.stop()
            clip.close()
        }
        ringtoneClip = null
    }

    private fun showIncomingNotification() {
        scope.launch(Dispatchers.IO) {
            try {
                if (System.getProperty("os.name").lowercase().contains("mac")) {
                    val process = ProcessBuilder(
                        "osascript", "-e",
                        "display notification \"Incoming Call\" with title \"Yalla SIP Phone\" sound name \"default\""
                    ).start()
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to show notification" }
            }
        }
    }
}
