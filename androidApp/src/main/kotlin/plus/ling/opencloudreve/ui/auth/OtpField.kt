package plus.ling.opencloudreve.ui.auth

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 6 格数字验证码输入；输满由 ViewModel 自动提交 */
@Composable
fun OtpField(
    value: String,
    length: Int,
    enabled: Boolean,
    isError: Boolean,
    contentDescription: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        // 提交期间只读而非禁用：禁用会丢失焦点并收起键盘，失败后无法直接重输
        readOnly = !enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier
            .focusRequester(focus)
            .semantics { this.contentDescription = contentDescription },
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(length) { i ->
                    val char = value.getOrNull(i)
                    val focused = i == value.length
                    val color = when {
                        isError -> MaterialTheme.colorScheme.error
                        focused -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 56.dp)
                            .border(if (focused) 2.dp else 1.dp, color, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = char?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        },
    )
}
