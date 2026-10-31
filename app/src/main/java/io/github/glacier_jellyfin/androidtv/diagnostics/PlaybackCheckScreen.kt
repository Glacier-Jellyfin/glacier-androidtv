package io.github.glacier_jellyfin.androidtv.diagnostics

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.player.CheckResult
import io.github.glacier_jellyfin.androidtv.core.player.CheckSample
import io.github.glacier_jellyfin.androidtv.core.player.PlaybackCheck
import io.github.glacier_jellyfin.androidtv.player.InfoOk
import io.github.glacier_jellyfin.androidtv.player.InfoWarn
import io.github.glacier_jellyfin.androidtv.ui.ProgressBar

/**
 * Settings › System › Check playback, full screen like the log download: what
 * the check does and how far it is on the left, every format with its result
 * on the right.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlaybackCheckScreen(viewModel: PlaybackCheckViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val close = {
        viewModel.stop()
        onClose()
    }
    BackHandler(onBack = close)
    val buttonFocus = remember { FocusRequester() }
    GlacierBackground(
        Modifier
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(Modifier.fillMaxSize().padding(start = 130.dp, end = 130.dp, top = 90.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(48.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(100.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.settings_group_diagnostics).uppercase(),
                            style = GlacierText.label(18, 0.08),
                            color = LocalAccent.current.main,
                        )
                        Text(
                            stringResource(if (state.done) R.string.check_done_title else R.string.check_title),
                            style = GlacierText.display(64),
                            color = GlacierColors.Ice,
                        )
                        val body = when {
                            state.running -> stringResource(R.string.check_running_body)
                            state.done -> stringResource(R.string.check_done_body)
                            else -> stringResource(R.string.check_intro_body, state.total)
                        }
                        Text(body, style = GlacierText.body(21), color = GlacierColors.Mist)
                    }
                    if (state.running || state.done) Progress(state)
                }
                Results(state, Modifier.width(880.dp).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.check_hint), style = GlacierText.body(17), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
                if (!state.running && !state.done) PillButton(stringResource(R.string.check_close), onClick = close, height = 68, width = 260)
                // One button for Start, Stop and Close: it keeps the focus while its job changes.
                PillButton(
                    stringResource(
                        when {
                            state.running -> R.string.check_stop
                            state.done -> R.string.check_close
                            else -> R.string.check_start
                        },
                    ),
                    onClick = when {
                        state.running -> viewModel::stop
                        state.done -> close
                        else -> viewModel::start
                    },
                    primary = !state.running,
                    height = 68,
                    width = 260,
                    modifier = Modifier.focusRequester(buttonFocus),
                )
            }
        }
        // The decoder needs a surface to render to, as in real playback; nobody needs to watch test pictures.
        state.playback?.let { playback ->
            Box(Modifier.size(2.dp).alpha(0f).focusProperties { canFocus = false }) {
                ContentFrame(player = playback.player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, contentScale = ContentScale.Fit)
            }
        }
    }
    // The button changes with each stage, and each sample's surface would take the focus: keep it on the button.
    LaunchedEffect(state.running, state.done, state.current) {
        withFrameNanos { }
        runCatching { buttonFocus.requestFocus() }
    }
}

@Composable
private fun Progress(state: PlaybackCheckState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.current?.label ?: stringResource(R.string.check_finished),
                style = GlacierText.display(30),
                color = GlacierColors.Ice,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.check_progress, (state.results.size + if (state.running) 1 else 0).coerceAtMost(state.total), state.total),
                style = GlacierText.mono(20),
                color = GlacierColors.Mist,
            )
        }
        ProgressBar(state.results.size.toFloat() / state.total, Modifier.height(10.dp))
    }
}

/** Every sample in order: its result, the one playing now, or still waiting. */
@Composable
private fun Results(state: PlaybackCheckState, modifier: Modifier) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    val byFile = state.results.associateBy { it.sample.file }
    Column(
        modifier
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(horizontal = 40.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.check_formats), style = GlacierText.display(26), color = GlacierColors.Ice)
        PlaybackCheck.SAMPLES.forEach { sample ->
            ResultRow(sample, byFile[sample.file], playing = state.current == sample)
        }
    }
}

@Composable
private fun ResultRow(sample: CheckSample, result: CheckResult?, playing: Boolean) {
    val (status, color) = when {
        playing -> stringResource(R.string.check_checking) to LocalAccent.current.main
        result == null -> stringResource(R.string.check_waiting) to GlacierColors.Mist.copy(alpha = 0.6f)
        result.outcome == CheckResult.Outcome.Plays ->
            stringResource(if (result.beyondDeclared) R.string.check_plays_beyond else R.string.check_plays) to InfoOk
        result.outcome == CheckResult.Outcome.NotSupported -> stringResource(R.string.check_not_supported) to GlacierColors.Mist
        result.outcome == CheckResult.Outcome.Crashed -> stringResource(R.string.check_crashed) to InfoWarn
        else -> stringResource(R.string.check_fails) to InfoWarn
    }
    val detail = when {
        result == null -> null
        result.outcome == CheckResult.Outcome.Fails -> result.error
        sample.audio -> listOfNotNull(result.audioOutput, result.decoder).joinToString(" · ").ifEmpty { null }
        else -> result.decoder
    }
    val labelColor = if (result == null && !playing) GlacierColors.Mist.copy(alpha = 0.6f) else GlacierColors.Ice
    // One line each, so all samples fit without scrolling, which a remote cannot do here.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(sample.label, style = GlacierText.body(20), color = labelColor, maxLines = 1, modifier = Modifier.width(250.dp))
        Text(detail.orEmpty(), style = GlacierText.mono(14), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(status, style = GlacierText.body(19), color = color, maxLines = 1)
    }
}
