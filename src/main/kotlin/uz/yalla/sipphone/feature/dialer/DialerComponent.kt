package uz.yalla.sipphone.feature.dialer

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.yalla.sipphone.domain.CallEngine
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState

class DialerComponent(
    componentContext: ComponentContext,
    private val registrationEngine: RegistrationEngine,
    private val callEngine: CallEngine,
    private val onDisconnected: () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ComponentContext by componentContext {

    val registrationState: StateFlow<RegistrationState> = registrationEngine.registrationState
    val callState: StateFlow<CallState> = callEngine.callState

    private val scope = coroutineScope()

    init {
        // Navigate back when registration lost AND no active call
        // Uses combine so it re-evaluates when either state changes
        scope.launch(ioDispatcher) {
            combine(
                registrationEngine.registrationState,
                callEngine.callState,
            ) { regState, callState ->
                val isDisconnected = regState is RegistrationState.Idle || regState is RegistrationState.Failed
                val noActiveCall = callState is CallState.Idle
                isDisconnected && noActiveCall
            }
                .drop(1) // skip initial combined emission
                .first { it }
            withContext(Dispatchers.Main) {
                onDisconnected()
            }
        }
    }

    fun makeCall(number: String) {
        scope.launch { callEngine.makeCall(number) }
    }

    fun answerCall() {
        scope.launch { callEngine.answerCall() }
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

    fun disconnect() {
        scope.launch { registrationEngine.unregister() }
    }
}
