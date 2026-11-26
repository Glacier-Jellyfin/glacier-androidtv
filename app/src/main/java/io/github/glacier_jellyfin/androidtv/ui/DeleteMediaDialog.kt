package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.ModalSheet
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton

@Composable
internal fun DeleteMediaDialog(
    title: String,
    message: String,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalSheet(onDismiss = onDismiss, width = 720) {
        Text(title, style = GlacierText.display(28), color = GlacierColors.Ice, textAlign = TextAlign.Center)
        Text(message, style = GlacierText.body(19), color = GlacierColors.Mist, textAlign = TextAlign.Center)

        val cancel = remember { FocusRequester() }
        PillButton(
            stringResource(R.string.action_cancel),
            onClick = onDismiss,
            fillWidth = true,
            enabled = !deleting,
            modifier = Modifier.focusRequester(cancel),
        )
        PillButton(
            stringResource(if (deleting) R.string.player_deleting else R.string.player_delete),
            onClick = onConfirm,
            fillWidth = true,
            enabled = !deleting,
            icon = GlacierIcons.Trash,
        )
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { cancel.requestFocus() }
        }
    }
}
