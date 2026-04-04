package uz.yalla.sipphone.feature.main.placeholder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import uz.yalla.sipphone.ui.strings.Strings
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun WebviewPlaceholder(dispatcherUrl: String, modifier: Modifier = Modifier) {
    val colors = LocalYallaColors.current
    Box(
        modifier = modifier.background(colors.backgroundBase),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = Strings.PLACEHOLDER_DISPATCHER,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textSubtle,
            )
            if (dispatcherUrl.isNotEmpty()) {
                Text(
                    text = Strings.PLACEHOLDER_URL.format(dispatcherUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSubtle,
                )
            }
        }
    }
}
