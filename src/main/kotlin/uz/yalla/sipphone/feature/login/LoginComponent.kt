package uz.yalla.sipphone.feature.login

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.RegistrationEngine
import uz.yalla.sipphone.domain.RegistrationState
import uz.yalla.sipphone.domain.SipAccountInfo
import uz.yalla.sipphone.domain.SipCredentials
import uz.yalla.sipphone.domain.sipCredentials

private val logger = KotlinLogging.logger {}

sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data class Error(val message: String) : LoginState
    data class Authenticated(val authResult: AuthResult) : LoginState
}

class LoginComponent(
    componentContext: ComponentContext,
    private val authRepository: AuthRepository,
    private val registrationEngine: RegistrationEngine,
    private val onLoginSuccess: (AuthResult) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ComponentContext by componentContext {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    val registrationState: StateFlow<RegistrationState> = registrationEngine.registrationState

    private val scope = coroutineScope()
    @Volatile
    private var lastAuthResult: AuthResult? = null

    init {
        scope.launch {
            registrationEngine.registrationState.collect { state ->
                if (state is RegistrationState.Registered && lastAuthResult != null) {
                    logger.info { "SIP registered, navigating to main" }
                    onLoginSuccess(lastAuthResult!!)
                }
                if (state is RegistrationState.Failed && _loginState.value is LoginState.Authenticated) {
                    _loginState.value =
                        LoginState.Error("SIP registration failed: ${state.error.displayMessage}")
                }
            }
        }
    }

    fun login(password: String) {
        if (_loginState.value is LoginState.Loading) return
        _loginState.value = LoginState.Loading
        scope.launch(ioDispatcher) {
            val result = authRepository.login(password)
            result.fold(
                onSuccess = { authResult ->
                    lastAuthResult = authResult
                    _loginState.value = LoginState.Authenticated(authResult)
                    logger.info { "Auth success, registering SIP as ${authResult.sipCredentials.username}" }
                    registrationEngine.register(authResult.sipCredentials)
                },
                onFailure = { error ->
                    _loginState.value = LoginState.Error(error.message ?: "Login failed")
                    logger.warn { "Auth failed: ${error.message}" }
                },
            )
        }
    }

    fun manualConnect(server: String, port: Int, username: String, password: String, dispatcherUrl: String = "") {
        val credentials = SipCredentials(server = server, port = port, username = username, password = password)
        lastAuthResult = AuthResult(
            token = "",
            accounts = listOf(
                SipAccountInfo(
                    extensionNumber = username.toIntOrNull() ?: 0,
                    serverUrl = server,
                    sipName = null,
                    credentials = credentials,
                ),
            ),
            dispatcherUrl = dispatcherUrl,
            agent = AgentInfo("manual", username),
        )
        _loginState.value = LoginState.Loading
        scope.launch(ioDispatcher) {
            registrationEngine.register(credentials)
        }
    }
}
