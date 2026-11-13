package io.github.glacier_jellyfin.androidtv.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import io.github.glacier_jellyfin.androidtv.ui.OptionRow
import io.github.glacier_jellyfin.androidtv.ui.OptionsPanel
import io.github.glacier_jellyfin.androidtv.ui.OptionsPanelScope

/** The panel of [MusicActions], over whatever page opened it. */
@Composable
fun MusicActionsSheet(actions: MusicActions) {
    val sheet by actions.sheet.collectAsStateWithLifecycle()
    val current = sheet ?: return
    val title = when (current) {
        is MusicSheet.Menu -> current.target.title
        is MusicSheet.Playlists -> stringResource(R.string.music_choose_playlist)
        is MusicSheet.NewPlaylist -> stringResource(R.string.music_new_playlist)
    }
    val subtitle = if (current is MusicSheet.Menu) current.target.subtitle else current.target.title
    // One panel for every step, so the steps do not move it.
    OptionsPanel(
        title = title,
        subtitle = subtitle,
        onDismiss = actions::back,
        note = if (current is MusicSheet.NewPlaylist) stringResource(R.string.music_new_playlist_body) else null,
    ) {
        when (current) {
            is MusicSheet.Menu -> Menu(current, actions)
            is MusicSheet.Playlists -> Playlists(current, actions)
            is MusicSheet.NewPlaylist -> NewPlaylist(current, actions)
        }
    }
}

@Composable
private fun OptionsPanelScope.Menu(sheet: MusicSheet.Menu, actions: MusicActions) {
    val first = remember { FocusRequester() }
    val target = sheet.target
    // Play all and shuffle all first, when the target has them; the focus starts on the first entry.
    val playFirst = target.play != null
    if (target.play != null) {
        OptionRow(
            stringResource(R.string.music_play_all),
            stringResource(R.string.music_play_all_desc),
            actions::playAll,
            Modifier.focusRequester(first),
            GlacierIcons.Play,
        )
    }
    if (target.shuffle != null) {
        OptionRow(
            stringResource(R.string.music_shuffle_all),
            stringResource(R.string.music_shuffle_all_desc),
            actions::shuffleAll,
            icon = GlacierIcons.Shuffle,
        )
    }
    when {
        target.playing -> Unit

        target.queueIndex != null -> {
            OptionRow(
                stringResource(R.string.music_options_play_next),
                stringResource(R.string.music_options_move_next_desc),
                actions::moveNext,
                Modifier.focusRequester(first),
                GlacierIcons.Play,
            )
            OptionRow(
                stringResource(R.string.music_options_remove_queue),
                stringResource(R.string.music_options_remove_queue_desc),
                actions::removeFromQueue,
                icon = GlacierIcons.Close,
            )
        }

        else -> {
            OptionRow(
                stringResource(R.string.music_options_play_next),
                stringResource(R.string.music_options_play_next_desc),
                actions::playNext,
                if (playFirst) Modifier else Modifier.focusRequester(first),
                GlacierIcons.Play,
            )
            OptionRow(
                stringResource(R.string.music_options_add_queue),
                stringResource(R.string.music_options_add_queue_desc),
                actions::addToQueue,
                icon = GlacierIcons.Chapters,
            )
            if (target.videos != null) {
                OptionRow(
                    stringResource(R.string.music_options_add_videos_queue),
                    stringResource(R.string.music_options_add_videos_queue_desc),
                    actions::addVideosToQueue,
                    icon = GlacierIcons.Film,
                )
            }
        }
    }
    OptionRow(
        stringResource(R.string.music_options_add_playlist),
        stringResource(R.string.music_options_add_playlist_desc),
        actions::choosePlaylist,
        if (target.playing) Modifier.focusRequester(first) else Modifier,
        GlacierIcons.PlaylistAdd,
    )
    if (target.mixFrom != null) {
        OptionRow(
            stringResource(R.string.music_options_mix),
            stringResource(R.string.music_options_mix_desc),
            actions::playMix,
            icon = GlacierIcons.Compass,
        )
    }
    if (target.playlistId != null) {
        OptionRow(
            stringResource(R.string.music_options_remove_playlist),
            stringResource(R.string.music_options_remove_playlist_desc),
            actions::removeFromPlaylist,
            icon = GlacierIcons.Close,
        )
    }
    FocusOnStart(first)
}

@Composable
private fun OptionsPanelScope.Playlists(sheet: MusicSheet.Playlists, actions: MusicActions) {
    val first = remember { FocusRequester() }
    val playlists = sheet.playlists
    if (playlists == null) {
        // In the middle of where the playlists will be listed.
        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { SpinningDiamond(70) }
        return
    }
    OptionRow(
        stringResource(R.string.music_new_playlist),
        stringResource(R.string.music_new_playlist_desc),
        actions::newPlaylist,
        Modifier.focusRequester(first),
        GlacierIcons.Plus,
    )
    val addDescription = stringResource(R.string.music_playlist_add_desc)
    playlists.forEach { playlist ->
        val count = playlist.childCount
        key(playlist.id) {
            OptionRow(
                playlist.title,
                addDescription,
                { actions.addTo(playlist) },
                badge = count?.let { pluralStringResource(R.plurals.count_titles, it, it) },
            )
        }
    }
    if (playlists.isEmpty()) {
        Text(
            stringResource(R.string.music_no_playlists),
            style = GlacierText.body(18),
            color = GlacierColors.Mist,
        )
    }
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
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
