package uz.yalla.sipphone.feature.registration

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipCredentials
import io.github.oshai.kotlinlogging.KotlinLogging
import uz.yalla.sipphone.domain.SipEngine

private val logger = KotlinLogging.logger {}

class RegistrationComponent(
    componentContext: ComponentContext,
    private val sipEngine: SipEngine,
    private val appSettings: AppSettings,
    private val onRegistered: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ComponentContext by componentContext {

    private val _formState = MutableStateFlow(FormState())
    val formState: StateFlow<FormState> = _formState.asStateFlow()

    val registrationState: StateFlow<RegistrationState> = sipEngine.registrationState

    // Essenty lifecycle-scoped scope. No args = Main.immediate + lifecycle-managed Job
    private val scope = coroutineScope()

    init {
        // Load last-used credentials
        scope.launch(ioDispatcher) {
            appSettings.loadCredentials()?.let { creds ->
                _formState.value = FormState(
                    server = creds.server,
                    port = creds.port.toString(),
                    username = creds.username,
                    password = creds.password,
                )
            }
        }

        // Navigate on successful registration.
        // Uses collect (not first) because component persists in Decompose back stack.
        // After pop back from Dialer, same component instance is reused — first{} would
        // already be completed. StateFlow.distinctUntilChanged prevents duplicate emissions.
        scope.launch {
            sipEngine.registrationState.collect { state ->
                if (state is RegistrationState.Registered) {
                    logger.info { "Navigating to Dialer" }
                    onRegistered()
                }
            }
        }
    }

    fun connect(credentials: SipCredentials) {
        logger.info { "Connecting to ${credentials.server}:${credentials.port} as ${credentials.username}" }
        scope.launch {
            withContext(ioDispatcher) { appSettings.saveCredentials(credentials) }
            val result = sipEngine.register(credentials)
            logger.info { "Register result: $result" }
        }
    }

    fun cancelRegistration() {
        scope.launch { sipEngine.unregister() }
    }

    fun updateFormState(formState: FormState) {
        _formState.value = formState
    }
}
