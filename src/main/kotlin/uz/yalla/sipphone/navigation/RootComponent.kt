package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import uz.yalla.sipphone.domain.AuthResult
import uz.yalla.sipphone.feature.login.LoginComponent
import uz.yalla.sipphone.feature.main.MainComponent

class RootComponent(
    componentContext: ComponentContext,
    private val factory: ComponentFactory,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Screen>()
    private var currentAuthResult: AuthResult? = null
    private var loginSessionCounter = 0

    val childStack: Value<ChildStack<Screen, Child>> = childStack(
        source = navigation,
        serializer = Screen.serializer(),
        initialConfiguration = Screen.Login(),
        handleBackButton = false,
        childFactory = ::createChild,
    )

    private fun createChild(screen: Screen, context: ComponentContext): Child =
        when (screen) {
            is Screen.Login -> Child.Login(
                factory.createLogin(context) { authResult ->
                    currentAuthResult = authResult
                    navigation.pushNew(Screen.Main)
                },
            )
            is Screen.Main -> Child.Main(
                factory.createMain(context, checkNotNull(currentAuthResult) { "AuthResult not set" }) {
                    currentAuthResult = null
                    navigation.navigate { listOf(Screen.Login(sessionId = ++loginSessionCounter)) }
                },
            )
        }

    sealed interface Child {
        data class Login(val component: LoginComponent) : Child
        data class Main(val component: MainComponent) : Child
    }
}
