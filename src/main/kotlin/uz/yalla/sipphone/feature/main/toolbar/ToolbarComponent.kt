package uz.yalla.sipphone.feature.main.toolbar

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

private val logger = KotlinLogging.logger {}

class ToolbarComponent(
    private val callEngine: CallEngine,
    private val sipAccountManager: SipAccountManager,
    private val scope: CoroutineScope,
) {
    val callState: StateFlow<CallState> = callEngine.callState
    val accounts: StateFlow<List<SipAccount>> = sipAccountManager.accounts

    private val _agentStatus = MutableStateFlow(AgentStatus.READY)
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _phoneInput = MutableStateFlow("")
    val phoneInput: StateFlow<String> = _phoneInput.asStateFlow()

    private val _phoneInputFocusRequest = MutableStateFlow(0)
    val phoneInputFocusRequest: StateFlow<Int> = _phoneInputFocusRequest.asStateFlow()

    private val _callDuration = MutableStateFlow<String?>(null)
    val callDuration: StateFlow<String?> = _callDuration.asStateFlow()

    private val _settingsVisible = MutableStateFlow(false)
    val settingsVisible: StateFlow<Boolean> = _settingsVisible.asStateFlow()

    private var timerJob: Job? = null
    private val ringtonePlayer = RingtonePlayer()
    private val notificationService = NotificationService()

    init {
        scope.launch {
            callEngine.callState.collect { state ->
                when {
                    state is CallState.Ringing && !state.isOutbound -> {
                        _phoneInput.value = state.callerNumber
                        ringtonePlayer.play()
                        notificationService.showIncomingCall(scope)
                    }
                    state is CallState.Idle -> {
                        _phoneInput.value = ""
                        ringtonePlayer.stop()
                    }
                    else -> ringtonePlayer.stop()
                }

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
            logger.debug { "Invalid phone number input" }
            return false
        }
        val firstConnected = accounts.value.firstOrNull { it.state is SipAccountState.Connected }
        val accountId = firstConnected?.id ?: ""
        scope.launch { callEngine.makeCall(validation.getOrThrow(), accountId) }
        return true
    }

    fun answerCall() {
        ringtonePlayer.stop()
        scope.launch { callEngine.answerCall() }
    }

    fun rejectCall() {
        ringtonePlayer.stop()
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
        if (callState.value.activeAccountId == accountId && account.state is SipAccountState.Connected) {
            logger.debug { "Cannot disconnect SIP account with active call: $accountId" }
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

    fun releaseAudioResources() {
        ringtonePlayer.release()
        stopTimer()
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
}
