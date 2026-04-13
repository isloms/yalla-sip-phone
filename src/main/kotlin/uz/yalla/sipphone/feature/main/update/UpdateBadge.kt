package uz.yalla.sipphone.feature.main.update

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import uz.yalla.sipphone.domain.update.UpdateState

/**
 * Toolbar badge that becomes visible when an update is available.
 * Click opens the full update dialog.
 *
 * Hidden during Idle/Checking — invariant I14 (UI respects operator focus).
 */
@Composable
fun UpdateBadge(
    state: StateFlow<UpdateState>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by state.collectAsState()
    if (current is UpdateState.Idle || current is UpdateState.Checking) return
    val tint = when (current) {
        is UpdateState.Failed -> MaterialTheme.colorScheme.error
        is UpdateState.ReadyToInstall -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.SystemUpdateAlt,
            contentDescription = "Update",
            tint = tint,
        )
    }
}
