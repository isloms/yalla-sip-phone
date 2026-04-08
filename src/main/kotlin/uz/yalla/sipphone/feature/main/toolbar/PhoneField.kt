package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.yalla.sipphone.domain.CallState
import uz.yalla.sipphone.ui.strings.LocalStrings
import uz.yalla.sipphone.ui.theme.LocalYallaColors

/**
 * Phone number input field with monospace/tabular-nums font.
 *
 * - Fixed width range: 120-160dp
 * - 36dp height, rounded 8dp
 * - During ringing: brand border, brand text color
 * - Placeholder: "+998 __ ___ __ __"
 */
@Composable
fun PhoneField(
    phoneNumber: String,
    onValueChange: (String) -> Unit,
    callState: CallState,
    focusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val strings = LocalStrings.current

    val isRinging = callState is CallState.Ringing
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = when {
        isRinging -> colors.buttonActive
        isFocused -> colors.buttonActive.copy(alpha = 0.5f)
        else -> colors.borderDisabled
    }

    val textColor = when {
        isRinging -> colors.buttonActive
        else -> colors.textBase
    }

    val shape = RoundedCornerShape(8.dp)

    val fieldTextStyle = TextStyle(
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 36.sp, // match field height for vertical centering
    )

    BasicTextField(
        value = phoneNumber,
        onValueChange = onValueChange,
        textStyle = fieldTextStyle,
        singleLine = true,
        cursorBrush = if (isFocused) SolidColor(colors.buttonActive) else SolidColor(Color.Transparent),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .widthIn(min = 120.dp, max = 160.dp)
                    .height(36.dp)
                    .background(colors.backgroundSecondary, shape)
                    .border(1.dp, borderColor, shape)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (phoneNumber.isEmpty()) {
                    Text(
                        text = strings.placeholderPhone,
                        style = fieldTextStyle.copy(color = colors.textSubtle),
                    )
                }
                innerTextField()
            }
        },
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
    )
}
