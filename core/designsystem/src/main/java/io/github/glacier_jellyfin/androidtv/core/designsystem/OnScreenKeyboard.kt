package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

private const val KeyWidth = 94
private const val KeyHeight = 72
private const val Gap = 12

/**
 * The design's on-screen keyboard (8 columns of 94×72, 12 gap). Text changes
 * go through [onTextChange]; [onSystemKeyboard] opens Android's keyboard,
 * see [SystemTextInput].
 */
@Composable
fun OnScreenKeyboard(
    text: String,
    onTextChange: (String) -> Unit,
    onSystemKeyboard: () -> Unit,
    spaceLabel: String,
    modifier: Modifier = Modifier,
    firstKeyFocus: FocusRequester? = null,
) {
    var shift by rememberSaveable { mutableStateOf(false) }
    var symbols by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Gap.dp)) {
        KeyboardLayout.rows(shift, symbols).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.dp)) {
                row.forEachIndexed { index, key ->
                    val width = KeyWidth * key.span + Gap * (key.span - 1)
                    val focusModifier = if (rowIndex == 0 && index == 0 && firstKeyFocus != null) {
                        Modifier.focusRequester(firstKeyFocus)
                    } else {
                        Modifier
                    }
                    KeyCap(
                        onClick = {
                            when (key) {
                                Key.Shift -> shift = !shift
                                Key.Symbols -> symbols = !symbols
                                Key.SystemKeyboard -> onSystemKeyboard()
                                else -> KeyboardLayout.apply(text, key)?.let(onTextChange)
                            }
                        },
                        width = width,
                        height = KeyHeight,
                        modifier = focusModifier,
                        active = (key == Key.Shift && shift) || (key == Key.Symbols && symbols),
                    ) { color ->
                        when (key) {
                            is Key.Char -> Text(key.value.toString(), style = GlacierText.display(23), color = color)
                            Key.Space -> Text(spaceLabel, style = GlacierText.body(23, FontWeight.SemiBold), color = color)
                            Key.Backspace -> Icon(GlacierIcons.Backspace, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
                            Key.Shift -> Icon(GlacierIcons.Shift, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
                            Key.Symbols -> Text(if (symbols) "abc" else "#+=", style = GlacierText.body(21, FontWeight.SemiBold), color = color)
                            Key.SystemKeyboard -> Icon(GlacierIcons.Keyboard, contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Invisible text field that brings up Android's own keyboard (voice input,
 * paste, other layouts). Set [open] to true to show it; [onClose] fires when
 * the user confirms.
 */
@Composable
fun SystemTextInput(
    text: String,
    onTextChange: (String) -> Unit,
    open: Boolean,
    onClose: () -> Unit,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            onClose()
        }),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focus),
    )
    LaunchedEffect(open) {
        if (open) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
}
