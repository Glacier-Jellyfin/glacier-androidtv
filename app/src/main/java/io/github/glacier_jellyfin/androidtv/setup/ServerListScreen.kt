package io.github.glacier_jellyfin.androidtv.setup

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.AccentChip
import io.github.glacier_jellyfin.androidtv.core.designsystem.BusyOverlay
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierMark
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

@Composable
fun ServerListScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    onManualAddress: () -> Unit,
    viewModel: ServerListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)

    val firstFocus = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }
    val hasEntries = state.saved.isNotEmpty() || state.discovered.isNotEmpty()
    LaunchedEffect(hasEntries, state.searching) {
        // Focus the first server once one exists, or the manual entry once searching gave up.
        if (!initialFocusDone && (hasEntries || !state.searching)) {
            runCatching { firstFocus.requestFocus() }
            initialFocusDone = true
        }
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 130.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(110.dp),
    ) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            GlacierMark(size = 62)
            Text(
                stringResource(R.string.brand_jellyfin).uppercase(),
                style = GlacierText.body(24, FontWeight.Bold).copy(letterSpacing = 0.34.em),
                color = GlacierColors.Ice,
            )
            Text(
                stringResource(R.string.setup_welcome),
                style = GlacierText.display(60).copy(lineHeight = 63.sp),
                color = GlacierColors.Ice,
            )
            Text(
                stringResource(R.string.setup_intro),
                style = GlacierText.body(23).copy(lineHeight = 34.sp),
                color = GlacierColors.Mist,
                modifier = Modifier.widthIn(max = 470.dp),
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentChip(stringResource(R.string.setup_step, 1, 2))
                Text(stringResource(R.string.setup_step_server), style = GlacierText.body(18), color = GlacierColors.Mist)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 60.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            var focusAssigned = false
            fun nextFocus(): Modifier = if (!focusAssigned) {
                focusAssigned = true
                Modifier.focusRequester(firstFocus)
            } else {
                Modifier
            }

            if (state.saved.isNotEmpty()) {
                SectionHeader(stringResource(R.string.setup_saved_servers), pulsing = false)
                state.saved.forEach { entry -> ServerCard(entry, onClick = { viewModel.select(entry) }, modifier = nextFocus()) }
            }
            SectionHeader(
                stringResource(if (state.searching && state.discovered.isEmpty()) R.string.setup_searching else R.string.setup_found_on_network),
                pulsing = state.searching,
            )
            state.discovered.forEach { entry -> ServerCard(entry, onClick = { viewModel.select(entry) }, modifier = nextFocus()) }
            if (!state.searching && state.discovered.isEmpty()) {
                Text(stringResource(R.string.setup_nothing_found), style = GlacierText.body(18), color = GlacierColors.Mist)
            }
            PillButton(
                text = stringResource(R.string.setup_manual_address),
                onClick = onManualAddress,
                icon = GlacierIcons.Plus,
                height = 72,
                fillWidth = true,
                modifier = Modifier.padding(top = 6.dp).then(nextFocus()),
            )
        }
    }

    state.connecting?.let { BusyOverlay(stringResource(R.string.setup_connecting, it)) }

    state.confirmUnsupported?.let { server -> UnsupportedServerDialog(server, viewModel::confirmUnsupported) }
}

@Composable
private fun SectionHeader(text: String, pulsing: Boolean) {
    val accent = LocalAccent.current.main
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(0.5f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulseAlpha")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .alpha(if (pulsing) alpha else 1f)
                .clip(CircleShape)
                .background(accent),
        )
        Text(text, style = GlacierText.body(18), color = GlacierColors.Mist)
    }
}

@Composable
private fun ServerCard(entry: ServerEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val dimmed = !entry.server.isSupported || !entry.reachable
    GlacierClickable(
        onClick = onClick,
        shape = shape,
        modifier = modifier.fillMaxWidth(),
        unfocusedBorder = GlacierColors.GlassBorder,
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(124.dp)
                .clip(shape)
                .background(GlacierColors.GlassFill)
                .alpha(if (dimmed && !focused) 0.55f else 1f)
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Box(
                Modifier
                    .size(66.dp)
                    .clip(RoundedCornerShape(GlacierShapes.RadiusSm))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GlacierIcons.Server, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    entry.server.name,
                    style = GlacierText.display(26),
                    color = if (focused) accent else GlacierColors.Ice,
                    maxLines = 1,
                )
                Text(entry.server.address, style = GlacierText.mono(17), color = GlacierColors.Mist, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val version = entry.server.version.orEmpty()
                Text(
                    if (entry.server.isSupported) "Jellyfin $version" else stringResource(R.string.setup_unsupported, version.ifEmpty { "?" }),
                    style = GlacierText.body(16),
                    color = GlacierColors.Mist,
                )
                val latency = entry.latencyMillis?.toInt()
                val meta = when {
                    !entry.reachable -> stringResource(R.string.setup_offline)
                    latency == null -> null
                    entry.publicUserCount != null ->
                        pluralStringResource(R.plurals.setup_users, entry.publicUserCount, entry.publicUserCount) +
                            " · " + stringResource(R.string.setup_latency, latency)
                    else -> stringResource(R.string.setup_latency, latency)
                }
                if (meta != null) Text(meta, style = GlacierText.body(16), color = GlacierColors.Mist)
            }
            Icon(
                GlacierIcons.ChevronRight,
                contentDescription = null,
                tint = if (focused) accent else GlacierColors.Ice,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
