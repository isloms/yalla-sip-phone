package uz.yalla.sipphone.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import uz.yalla.sipphone.feature.dialer.DialerComponent
import uz.yalla.sipphone.feature.registration.RegistrationComponent

class RootComponent(
    componentContext: ComponentContext,
    private val registrationFactory: (ComponentContext, onRegistered: () -> Unit) -> RegistrationComponent,
    private val dialerFactory: (ComponentContext, onDisconnected: () -> Unit) -> DialerComponent,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Screen>()

    val childStack: Value<ChildStack<Screen, Child>> = childStack(
        source = navigation,
        serializer = Screen.serializer(),
        initialConfiguration = Screen.Registration,
        handleBackButton = true,
        childFactory = ::createChild,
    )

    // Reserved for Phase 3: incoming call overlay via childSlot()

    private fun createChild(screen: Screen, context: ComponentContext): Child =
        when (screen) {
            is Screen.Registration -> Child.Registration(
                registrationFactory(context) { navigation.pushNew(Screen.Dialer) }
            )
            is Screen.Dialer -> Child.Dialer(
                dialerFactory(context) { navigation.pop() }
            )
        }

    sealed interface Child {
        data class Registration(val component: RegistrationComponent) : Child
        data class Dialer(val component: DialerComponent) : Child
    }
}
