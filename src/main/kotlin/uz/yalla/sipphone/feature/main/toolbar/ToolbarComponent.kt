package uz.yalla.sipphone.feature.main.toolbar

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.AgentStatus
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.PhoneNumberValidator
import uz.yalla.sipphone.domain.SipAccount
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.util.formatDuration
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

private val logger = KotlinLogging.logger {}

class ToolbarComponent(
    val callEngine: CallEngine,
    val sipAccountManager: SipAccountManager,
) {
    val callState: StateFlow<CallState> = callEngine.callState
    val accounts: StateFlow<List<SipAccount>> = sipAccountManager.accounts

    private val _agentStatus = MutableStateFlow(AgentStatus.READY)
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _phoneInput = MutableStateFlow("")
    val phoneInput: StateFlow<String> = _phoneInput.asStateFlow()

    // Incremented to trigger focus request from UI
    private val _phoneInputFocusRequest = MutableStateFlow(0)
    val phoneInputFocusRequest: StateFlow<Int> = _phoneInputFocusRequest.asStateFlow()

    // Call duration timer — null when no active call
    private val _callDuration = MutableStateFlow<String?>(null)
    val callDuration: StateFlow<String?> = _callDuration.asStateFlow()

    // Settings dialog visibility
    private val _settingsVisible = MutableStateFlow(false)
    val settingsVisible: StateFlow<Boolean> = _settingsVisible.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null
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

                // Manage call duration timer
                when (state) {
                    is CallState.Active -> startTimer()
                    else -> stopTimer()
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
        val firstConnected = accounts.value.firstOrNull { it.state is SipAccountState.Connected }
        val accountId = firstConnected?.id ?: ""
        scope.launch { callEngine.makeCall(validation.getOrThrow(), accountId) }
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

    fun onSipChipClick(accountId: String) {
        val account = accounts.value.find { it.id == accountId } ?: return

        // Cannot disconnect account with active call
        val currentCall = callState.value
        val activeCallAccountId = when (currentCall) {
            is CallState.Ringing -> currentCall.accountId
            is CallState.Active -> currentCall.accountId
            is CallState.Ending -> currentCall.accountId
            else -> null
        }
        if (activeCallAccountId == accountId && account.state is SipAccountState.Connected) {
            logger.warn { "Cannot disconnect SIP account with active call: $accountId" }
            return
        }

        scope.launch {
            when (account.state) {
                is SipAccountState.Connected -> sipAccountManager.disconnect(accountId)
                is SipAccountState.Disconnected -> sipAccountManager.connect(accountId)
                is SipAccountState.Reconnecting -> { /* ignore — transition state */ }
            }
        }
    }

    fun openSettings() {
        _settingsVisible.value = true
    }

    fun closeSettings() {
        _settingsVisible.value = false
    }

    fun disconnect() {
        scope.launch { sipAccountManager.unregisterAll() }
    }

    fun destroy() {
        stopRingtone()
        stopTimer()
        scope.cancel()
    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = scope.launch {
            var seconds = 0L
            while (isActive) {
                _callDuration.value = formatDuration(seconds)
                delay(1000)
                seconds++
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _callDuration.value = null
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
