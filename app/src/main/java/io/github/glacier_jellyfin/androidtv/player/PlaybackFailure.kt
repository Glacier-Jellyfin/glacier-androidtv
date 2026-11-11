package io.github.glacier_jellyfin.androidtv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.diagnostics.ISSUES_URL
import io.github.glacier_jellyfin.androidtv.diagnostics.LogShare
import io.github.glacier_jellyfin.androidtv.diagnostics.QrImage
import io.github.glacier_jellyfin.androidtv.update.DialogMark

/**
 * Playback failed for good: the player's own recoveries are used up. A raised card over the
 * dimmed picture says what failed and offers trying again; on the right, a QR code fetches
 * the log for an issue while the card is open. Without a network it points to Settings › System.
 */
@Composable
internal fun PlaybackFailureCard(state: PlayerUiState, onRetry: () -> Unit, onBack: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    // The right panel holds the QR code, or says why there is none; while the log server starts, the card stays narrow.
    val hasPanel = state.logShare != null || state.logOffline
    Box(Modifier.fillMaxSize().background(Color(0xB805090F)), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                // The OSD below stays composed; focus must not wander there.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .width(if (hasPanel) 1340.dp else 820.dp)
                .height(IntrinsicSize.Min)
                .clip(shape)
                .background(GlacierColors.Deep)
                .border(1.dp, GlacierColors.GlassBorder2, shape)
                .padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            Column(Modifier.weight(1f).padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                DialogMark(GlacierIcons.Alert)
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemLabel(state)?.let {
                        Text(
                            it.uppercase(),
                            style = GlacierText.label(17, 0.08),
                            color = LocalAccent.current.main,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(stringResource(R.string.player_error), style = GlacierText.display(44), color = GlacierColors.Ice)
                }
                Text(
                    stringResource(if (state.failedOnDevice) R.string.player_error_device_body else R.string.player_error_body),
                    style = GlacierText.body(21),
                    color = GlacierColors.Mist,
                )
                state.failureCode?.let { FailureCode(it) }
                Box(Modifier.weight(1f, fill = false))
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(stringResource(R.string.action_retry), onClick = onRetry, primary = true, modifier = Modifier.focusRequester(retryFocus))
                    PillButton(stringResource(R.string.action_back), onClick = onBack)
                }
            }
            if (hasPanel) LogPanel(state.logShare)
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { retryFocus.requestFocus() }
    }
}

/** The series and episode, or the title, so a photo of the card says what was playing. */
@Composable
private fun itemLabel(state: PlayerUiState): String? {
    val item = state.details?.item ?: return null
    val episode = item.episodeNumber?.takeIf { item.kind == ItemKind.Episode }
        ?.let { stringResource(R.string.player_episode_title, it, item.title) }
    return if (episode != null) listOfNotNull(item.parentTitle, episode).joinToString(" · ") else item.title
}

@Composable
private fun FailureCode(code: String) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Text(
        stringResource(R.string.player_error_code, code),
        style = GlacierText.mono(17).copy(letterSpacing = 0.02.em),
        color = GlacierColors.Ice,
        modifier = Modifier
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** The glass panel on the right: the QR code for the log, or, without a network, where to find the log later. */
@Composable
private fun LogPanel(share: LogShare?) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    Column(
        Modifier
            .width(500.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(horizontal = 36.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.player_error_log_title), style = GlacierText.display(26), color = GlacierColors.Ice, textAlign = TextAlign.Center)
        if (share != null) {
            QrImage(share.url, Modifier.size(240.dp).clip(RoundedCornerShape(GlacierShapes.RadiusMd)))
            Text(share.url, style = GlacierText.mono(16), color = GlacierColors.Ice, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.player_error_log_body, ISSUES_URL),
                style = GlacierText.body(17),
                color = GlacierColors.Mist,
                textAlign = TextAlign.Center,
            )
        } else {
            Icon(GlacierIcons.WifiOff, contentDescription = null, tint = GlacierColors.Mist, modifier = Modifier.size(72.dp))
            Text(stringResource(R.string.player_error_log_offline), style = GlacierText.body(19), color = GlacierColors.Mist, textAlign = TextAlign.Center)
        }
    }
}
