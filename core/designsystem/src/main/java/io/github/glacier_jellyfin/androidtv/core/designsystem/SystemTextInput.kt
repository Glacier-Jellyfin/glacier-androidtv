package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Invisible text field that brings up Android's own keyboard (voice input,
 * paste, other layouts). Set [open] to true to show it. [onClose] fires with
 * `done = true` when the user confirms, and with `done = false` when a remote
 * key arrives after the keyboard was dismissed with Back, so the caller can
 * move focus back to its own controls.
 */
@Composable
fun SystemTextInput(
    text: String,
    onTextChange: (String) -> Unit,
    open: Boolean,
    onClose: (done: Boolean) -> Unit,
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
            onClose(true)
        }),
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            // Only reachable while open: D-pad focus landing on it would bring up the keyboard.
            .focusProperties { canFocus = open }
            .focusRequester(focus)
            // While Android's keyboard is up it consumes remote keys itself. A
            // D-pad, OK or Back key arriving here means it was dismissed (on
            // TV its window reports no IME insets), so hand focus back.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in ReturnKeys) {
                    onClose(false)
                    true
                } else {
                    false
                }
            },
    )
    // Leaving the screen with the keyboard up must not leave it attached to a window that is gone.
    val isOpen by rememberUpdatedState(open)
    DisposableEffect(Unit) {
        onDispose { if (isOpen) keyboard?.hide() }
    }
    LaunchedEffect(open) {
        if (open) {
            focus.requestFocus()
            // The keyboard only attaches to a focused field; wait until focus has landed.
            withFrameNanos { }
            keyboard?.show()
        }
    }
}

private val ReturnKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter, Key.Back,
)
