package uz.yalla.sipphone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Yalla SIP Phone",
        state = rememberWindowState(
            size = DpSize(420.dp, 560.dp),
            position = WindowPosition(Alignment.Center)
        )
    ) {
        MaterialTheme {
            Surface {
                Text("Yalla SIP Phone - Setup OK")
            }
        }
    }
}
