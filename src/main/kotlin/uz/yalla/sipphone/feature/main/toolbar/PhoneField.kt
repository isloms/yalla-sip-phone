package uz.yalla.sipphone.feature.main.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import uz.yalla.sipphone.ui.theme.LocalAppTokens
import uz.yalla.sipphone.ui.theme.LocalYallaColors

@Composable
fun PhoneField(
    phoneNumber: String,
    onValueChange: (String) -> Unit,
    callState: CallState,
    focusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier,
) {
    val colors = LocalYallaColors.current
    val tokens = LocalAppTokens.current
    val strings = LocalStrings.current

    val isRinging = callState is CallState.Ringing
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = when {
        isRinging -> colors.brandPrimary
        isFocused -> colors.brandPrimary.copy(alpha = tokens.alphaFocus)
        else -> colors.borderDefault
    }

    val textColor = when {
        isRinging -> colors.brandPrimary
        else -> colors.textBase
    }

    val fieldTextStyle = TextStyle(
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = tokens.textMd,
        lineHeight = tokens.fieldHeight.value.sp,
    )

    BasicTextField(
        value = phoneNumber,
        onValueChange = onValueChange,
        textStyle = fieldTextStyle,
        singleLine = true,
        cursorBrush = if (isFocused) SolidColor(colors.brandPrimary) else SolidColor(Color.Transparent),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 220.dp)
                    .height(tokens.fieldHeight)
                    .background(colors.backgroundSecondary, tokens.shapeSmall)
                    .border(tokens.dividerThickness, borderColor, tokens.shapeSmall)
                    .padding(horizontal = tokens.spacingSm),
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
