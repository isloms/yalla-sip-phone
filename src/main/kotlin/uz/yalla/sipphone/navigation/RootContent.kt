package uz.yalla.sipphone.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import uz.yalla.sipphone.feature.login.LoginScreen
import uz.yalla.sipphone.feature.main.MainScreen
import uz.yalla.sipphone.ui.theme.LocalAppTokens

@Composable
fun RootContent(
    root: RootComponent,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val childStack by root.childStack.subscribeAsState()

    Children(
        stack = childStack,
        animation = stackAnimation {
            slide(animationSpec = tween(tokens.animSlow, easing = FastOutSlowInEasing)) +
                fade(animationSpec = tween(tokens.animFast))
        },
    ) { child ->
        when (val instance = child.instance) {
            is RootComponent.Child.Login ->
                LoginScreen(instance.component)
            is RootComponent.Child.Main ->
                MainScreen(
                    component = instance.component,
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = onThemeToggle,
                )
        }
    }
}
