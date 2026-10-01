package io.github.glacier_jellyfin.androidtv.music

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.SystemTextInput
import io.github.glacier_jellyfin.androidtv.setup.InputField
import io.github.glacier_jellyfin.androidtv.ui.ModalSheet

/** The sheet of [MusicActions], over whatever page opened it. */
@Composable
fun MusicActionsSheet(actions: MusicActions) {
    val sheet by actions.sheet.collectAsStateWithLifecycle()
    val current = sheet ?: return
    ModalSheet(onDismiss = actions::back, width = 680) {
        Column(
            // Focus stays in the sheet; the page behind it is out of reach.
            Modifier
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Header(current)
            when (current) {
                is MusicSheet.Menu -> Menu(current, actions)
                is MusicSheet.Playlists -> Playlists(current, actions)
                is MusicSheet.NewPlaylist -> NewPlaylist(current, actions)
            }
        }
    }
}

@Composable
private fun Header(sheet: MusicSheet) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val title = when (sheet) {
            is MusicSheet.Menu -> sheet.target.title
            is MusicSheet.Playlists -> stringResource(R.string.music_choose_playlist)
            is MusicSheet.NewPlaylist -> stringResource(R.string.music_new_playlist)
        }
        Text(title, style = GlacierText.display(30), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        val subtitle = if (sheet is MusicSheet.Menu) sheet.target.subtitle else sheet.target.title
        subtitle?.let { Text(it, style = GlacierText.body(19), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Menu(sheet: MusicSheet.Menu, actions: MusicActions) {
    val first = remember { FocusRequester() }
    val target = sheet.target
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            target.playing -> Unit
            target.queueIndex != null -> {
                PillButton(stringResource(R.string.music_options_play_next), onClick = actions::moveNext, icon = GlacierIcons.Play, fillWidth = true, modifier = Modifier.focusRequester(first))
                PillButton(stringResource(R.string.music_options_remove_queue), onClick = actions::removeFromQueue, icon = GlacierIcons.Close, fillWidth = true)
            }
            else -> {
                PillButton(stringResource(R.string.music_options_play_next), onClick = actions::playNext, icon = GlacierIcons.Play, fillWidth = true, modifier = Modifier.focusRequester(first))
                PillButton(stringResource(R.string.music_options_add_queue), onClick = actions::addToQueue, icon = GlacierIcons.Chapters, fillWidth = true)
            }
        }
        PillButton(
            stringResource(R.string.music_options_add_playlist),
            onClick = actions::choosePlaylist,
            icon = GlacierIcons.Plus,
            fillWidth = true,
            modifier = if (target.playing) Modifier.focusRequester(first) else Modifier,
        )
        if (target.mixFrom != null) {
            PillButton(stringResource(R.string.music_options_mix), onClick = actions::playMix, icon = GlacierIcons.Shuffle, fillWidth = true)
        }
        if (target.playlistId != null) {
            PillButton(stringResource(R.string.music_options_remove_playlist), onClick = actions::removeFromPlaylist, icon = GlacierIcons.Close, fillWidth = true)
        }
    }
    FocusOnStart(first)
}

@Composable
private fun Playlists(sheet: MusicSheet.Playlists, actions: MusicActions) {
    val first = remember { FocusRequester() }
    val playlists = sheet.playlists
    if (playlists == null) {
        SpinningDiamond(70)
        return
    }
    // Room around the buttons so the focused one can grow without being cut off.
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 480.dp),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "new") {
            PillButton(stringResource(R.string.music_new_playlist), onClick = actions::newPlaylist, icon = GlacierIcons.Plus, primary = true, fillWidth = true, modifier = Modifier.focusRequester(first))
        }
        items(playlists, key = { it.id }) { playlist ->
            val count = playlist.childCount
            PillButton(
                if (count != null) playlist.title + " · " + pluralStringResource(R.plurals.count_titles, count, count) else playlist.title,
                onClick = { actions.addTo(playlist) },
                fillWidth = true,
            )
        }
    }
    if (playlists.isEmpty()) Text(stringResource(R.string.music_no_playlists), style = GlacierText.body(18), color = GlacierColors.Mist)
    FocusOnStart(first)
}

@Composable
private fun NewPlaylist(sheet: MusicSheet.NewPlaylist, actions: MusicActions) {
    val fieldFocus = remember { FocusRequester() }
    val createFocus = remember { FocusRequester() }
    // The keyboard comes up at once: naming is all there is to do here.
    var keyboardOpen by remember { mutableStateOf(true) }
    InputField(
        label = stringResource(R.string.music_playlist_name),
        icon = GlacierIcons.Lyrics,
        value = sheet.name,
        placeholder = stringResource(R.string.music_playlist_name_hint),
        editing = keyboardOpen,
        onClick = { keyboardOpen = true },
        focusRequester = fieldFocus,
        down = createFocus,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PillButton(stringResource(R.string.action_cancel), onClick = actions::back)
        PillButton(
            stringResource(R.string.music_playlist_create),
            onClick = actions::createPlaylist,
            primary = true,
            enabled = sheet.name.isNotBlank() && !sheet.busy,
            modifier = Modifier.focusRequester(createFocus),
        )
    }
    SystemTextInput(
        text = sheet.name,
        onTextChange = actions::setName,
        open = keyboardOpen,
        onClose = { done ->
            keyboardOpen = false
            (if (done && sheet.name.isNotBlank()) createFocus else fieldFocus).requestFocus()
        },
    )
}

@Composable
private fun FocusOnStart(requester: FocusRequester) {
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}
