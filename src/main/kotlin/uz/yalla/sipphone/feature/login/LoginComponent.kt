package uz.yalla.sipphone.feature.login

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uz.yalla.sipphone.domain.AgentInfo
import uz.yalla.sipphone.domain.AuthRepository
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.domain.SipAccountInfo
import uz.yalla.sipphone.data.settings.AppSettings
import uz.yalla.sipphone.domain.SipAccountManager
import uz.yalla.sipphone.domain.SipAccountState
import uz.yalla.sipphone.domain.SipCredentials

private val logger = KotlinLogging.logger {}

data class ManualAccountEntry(
    val server: String,
    val port: Int,
    val username: String,
    val password: String,
) {
    val displayKey: String get() = "$username@$server:$port"
    override fun toString(): String = "ManualAccountEntry($displayKey, password=***)"
}

enum class LoginErrorType {
    WRONG_PASSWORD,
    NETWORK,
}

sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data class Error(val message: String, val type: LoginErrorType = LoginErrorType.NETWORK) : LoginState
    data class Authenticated(val authResult: AuthResult) : LoginState
}

class LoginComponent(
    componentContext: ComponentContext,
    private val authRepository: AuthRepository,
    private val sipAccountManager: SipAccountManager,
    private val appSettings: AppSettings,
    private val onLoginSuccess: (AuthResult) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ComponentContext by componentContext {

    companion object {
        private const val SIP_REGISTRATION_TIMEOUT_MS = 15_000L
    }

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val scope = coroutineScope()

    fun login(password: String) {
        if (_loginState.value is LoginState.Loading) return
        _loginState.value = LoginState.Loading
        scope.launch(ioDispatcher) {
            val result = authRepository.login(password)
            result.fold(
                onSuccess = { authResult ->
                    _loginState.value = LoginState.Authenticated(authResult)
                    registerAndNavigate(authResult, authResult.accounts)
                },
                onFailure = { error ->
                    val errorType = classifyAuthError(error)
                    _loginState.value = LoginState.Error(error.message ?: "Login failed", errorType)
                    logger.warn { "Auth failed: ${error.message}" }
                },
            )
        }
    }

    fun manualConnect(
        accounts: List<ManualAccountEntry>,
        dispatcherUrl: String = "",
        backendUrl: String = "",
        pin: String = "",
    ) {
        if (accounts.isEmpty()) return
        _loginState.value = LoginState.Loading

        val accountInfos = accounts.map { entry ->
            val credentials = SipCredentials(
                server = entry.server,
                port = entry.port,
                username = entry.username,
                password = entry.password,
            )
            SipAccountInfo(
                extensionNumber = entry.username.toIntOrNull() ?: 0,
                serverUrl = entry.server,
                sipName = null,
                credentials = credentials,
            )
        }

        scope.launch(ioDispatcher) {
            val resolvedDispatcher = dispatcherUrl.ifBlank { appSettings.dispatcherUrl }
            val resolvedBackend = backendUrl.ifBlank { appSettings.backendUrl }

            // Authenticate with backend if PIN provided — gets a token for the dispatcher
            var token = ""
            var agent = AgentInfo("manual", accounts.first().username)
            if (pin.isNotBlank()) {
                val loginResult = authRepository.login(pin)
                loginResult.fold(
                    onSuccess = {
                        token = it.token
                        agent = it.agent
                        logger.info { "Backend auth succeeded for manual connect: ${agent.name}" }
                    },
                    onFailure = {
                        val errorType = classifyAuthError(it)
                        _loginState.value = LoginState.Error(it.message ?: "Login failed", errorType)
                        logger.warn { "Backend auth failed for manual connect: ${it.message}" }
                        return@launch
                    },
                )
            }

            val authResult = AuthResult(
                token = token,
                accounts = accountInfos,
                dispatcherUrl = resolvedDispatcher,
                backendUrl = resolvedBackend,
                agent = agent,
            )
            registerAndNavigate(authResult, accountInfos)
        }
    }

    private suspend fun registerAndNavigate(authResult: AuthResult, accounts: List<SipAccountInfo>) {
        logger.info { "Registering ${accounts.size} SIP account(s)" }
        sipAccountManager.registerAll(accounts).fold(
            onSuccess = {
                val connected = withTimeoutOrNull(SIP_REGISTRATION_TIMEOUT_MS) {
                    sipAccountManager.accounts.first { accs ->
                        accs.any { it.state is SipAccountState.Connected }
                    }
                }
                if (connected == null) {
                    _loginState.value = LoginState.Error("SIP registration timed out. Check server settings.")
                    return@fold
                }
                logger.info { "SIP connected, navigating to main" }
                withContext(mainDispatcher) {
                    onLoginSuccess(authResult)
                }
            },
            onFailure = { error ->
                _loginState.value = LoginState.Error(
                    "SIP registration failed: ${error.message ?: "Unknown error"}",
                )
                logger.warn { "SIP registration failed: ${error.message}" }
            },
        )
    }

    private fun classifyAuthError(error: Throwable): LoginErrorType {
        val msg = error.message?.lowercase() ?: return LoginErrorType.NETWORK
        return if ("401" in msg || "unauthorized" in msg || "password" in msg) {
            LoginErrorType.WRONG_PASSWORD
        } else {
            LoginErrorType.NETWORK
        }
    }
}
