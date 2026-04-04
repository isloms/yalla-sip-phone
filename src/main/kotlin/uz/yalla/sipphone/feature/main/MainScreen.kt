package uz.yalla.sipphone.feature.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import uz.yalla.sipphone.feature.main.placeholder.WebviewPlaceholder
import uz.yalla.sipphone.feature.main.toolbar.ToolbarContent

@Composable
fun MainScreen(
    component: MainComponent,
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ToolbarContent(
            component = component.toolbar,
            isDarkTheme = isDarkTheme,
            onThemeToggle = onThemeToggle,
            onLogout = component::logout,
        )
        WebviewPlaceholder(
            dispatcherUrl = component.dispatcherUrl,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}
