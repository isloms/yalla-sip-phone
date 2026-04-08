package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import kotlinx.coroutines.launch
import uz.yalla.sipphone.data.auth.AuthEvent
import uz.yalla.sipphone.data.auth.AuthEventBus
import uz.yalla.sipphone.data.auth.LogoutOrchestrator
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.feature.login.LoginComponent
import uz.yalla.sipphone.feature.main.MainComponent

class RootComponent(
    componentContext: ComponentContext,
    private val factory: ComponentFactory,
    private val authEventBus: AuthEventBus,
    private val logoutOrchestrator: LogoutOrchestrator,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Screen>()
    private var currentAuthResult: AuthResult? = null
    private var loginSessionCounter = 0
    private val scope = coroutineScope()

    val childStack: Value<ChildStack<Screen, Child>> = childStack(
        source = navigation,
        serializer = Screen.serializer(),
        initialConfiguration = Screen.Login(),
        handleBackButton = false,
        childFactory = ::createChild,
    )

    init {
        scope.launch {
            authEventBus.events.collect { event ->
                when (event) {
                    AuthEvent.SessionExpired -> {
                        logoutOrchestrator.logout()
                        currentAuthResult = null
                        navigateToLogin()
                        logoutOrchestrator.reset()
                    }
                }
            }
        }
    }

    private fun createChild(screen: Screen, context: ComponentContext): Child =
        when (screen) {
            is Screen.Login -> createLoginChild(context)
            is Screen.Main -> {
                val auth = currentAuthResult ?: run {
                    navigateToLogin()
                    return@createChild createLoginChild(context)
                }
                Child.Main(
                    factory.createMain(context, auth) {
                        currentAuthResult = null
                        navigateToLogin()
                        scope.launch {
                            logoutOrchestrator.logout()
                            logoutOrchestrator.reset()
                        }
                    },
                )
            }
        }

    private fun createLoginChild(context: ComponentContext): Child.Login =
        Child.Login(
            factory.createLogin(context) { authResult ->
                currentAuthResult = authResult
                navigation.pushNew(Screen.Main)
            },
        )

    private fun navigateToLogin() {
        navigation.navigate { listOf(Screen.Login(sessionId = ++loginSessionCounter)) }
    }

    sealed interface Child {
        data class Login(val component: LoginComponent) : Child
        data class Main(val component: MainComponent) : Child
    }
}
