package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.ModalSheet
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.ignoreHeldOk

/**
 * The confirmation before something is deleted, a dialog in the middle that forces a decision.
 * [body] is the one line that says what is lost. Cancel has the focus first, [confirmLabel] is the primary button.
 * Holding OK may have opened it, so the held key is ignored until OK is pressed anew.
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    ModalSheet(onDismiss = onDismiss, width = 680) {
        Column(
            Modifier
                .ignoreHeldOk()
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    style = GlacierText.display(30),
                    color = GlacierColors.Ice,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    body,
                    style = GlacierText.body(17),
                    color = GlacierColors.Mist,
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(
                    stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(first),
                )
                PillButton(confirmLabel, onClick = onConfirm, primary = true, icon = GlacierIcons.Trash)
            }
        }
    }
    LaunchedEffect(first) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
}
