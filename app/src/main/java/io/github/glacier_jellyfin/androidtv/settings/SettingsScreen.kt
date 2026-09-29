package io.github.glacier_jellyfin.androidtv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.UiLocale
import io.github.glacier_jellyfin.androidtv.core.data.AgeLimit
import io.github.glacier_jellyfin.androidtv.core.data.Protection
import io.github.glacier_jellyfin.androidtv.core.data.media.Languages
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentAction
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.UpNextMode
import io.github.glacier_jellyfin.androidtv.core.data.settings.AccentColor
import io.github.glacier_jellyfin.androidtv.core.data.settings.AudioChannels
import io.github.glacier_jellyfin.androidtv.core.data.settings.Language
import io.github.glacier_jellyfin.androidtv.core.data.settings.MaxBitrate
import io.github.glacier_jellyfin.androidtv.core.data.settings.SeekStep
import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightCount
import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightRotation
import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightSource
import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightType
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleColor
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleEdge
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleFont
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleMode
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitlePosition
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleSize
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyleMode
import io.github.glacier_jellyfin.androidtv.core.data.settings.UiLanguage
import io.github.glacier_jellyfin.androidtv.core.data.settings.UpNextChoice
import io.github.glacier_jellyfin.androidtv.core.designsystem.Accent
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.SubtitleBurnIn
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PinDialog
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import java.text.DateFormat
import java.util.Date

/** One card of the settings list; built per category by [rows]. */
private sealed interface SettingRow {
    val label: String
    val sub: String
    val enabled: Boolean

    data class Toggle(
        override val label: String,
        override val sub: String,
        val checked: Boolean,
        val onToggle: () -> Unit,
        override val enabled: Boolean = true,
        val focus: FocusRequester? = null,
    ) : SettingRow

    data class Choice(
        override val label: String,
        override val sub: String,
        val options: List<String>,
        val selected: Int,
        val onSelect: (Int) -> Unit,
        override val enabled: Boolean = true,
    ) : SettingRow

    data class Swatches(
        override val label: String,
        override val sub: String,
        val swatches: List<Swatch>,
        val selected: Int,
        val onSelect: (Int) -> Unit,
        override val enabled: Boolean = true,
    ) : SettingRow

    /** A value at the end of the card: a language list to open, or an action. */
    data class Value(
        override val label: String,
        override val sub: String,
        val value: String,
        val onClick: (() -> Unit)?,
        override val enabled: Boolean = true,
        val chevron: Boolean = true,
        val focus: FocusRequester? = null,
    ) : SettingRow
}

private data class SettingGroup(val title: String, val rows: List<SettingRow>)

@Composable
fun SettingsScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val categoryFocus = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    // Closing the language list returns to the button that opened it.
    val languageFocus = remember { FocusRequester() }
    var pickerWasOpen by remember { mutableStateOf(false) }
    // Closing the PIN dialog returns to the button that opened it.
    val pinPrompt by viewModel.pin.prompt.collectAsStateWithLifecycle()
    val pinOrigins = remember { PinOrigins() }
    var pinWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pinPrompt == null) {
        if (pinPrompt == null && pinWasOpen) {
            withFrameNanos { }
            // The unlock button is gone once the account is open: its first row takes over.
            val restored = pinOrigins.restore() ||
                (state.category == SettingsCategory.Account && runCatching { languageFocus.requestFocus() }.getOrDefault(false))
            if (!restored) runCatching { categoryFocus.getValue(state.category).requestFocus() }
        }
        pinWasOpen = pinPrompt != null
    }
    LaunchedEffect(state.languagePicker) {
        if (state.languagePicker == null && pickerWasOpen) runCatching { languageFocus.requestFocus() }
        pickerWasOpen = state.languagePicker != null
    }
    // A new interface language recreates the activity; once it runs in that language, focus goes back.
    val context = LocalContext.current
    LaunchedEffect(state.refocusLanguage) {
        val language = state.refocusLanguage ?: return@LaunchedEffect
        if (UiLocale.current(context) != language.tag) return@LaunchedEffect
        withFrameNanos { }
        if (runCatching { languageFocus.requestFocus() }.isSuccess) viewModel.languageRefocused()
    }

    LaunchedEffect(Unit) {
        if (!initialFocusDone) {
            withFrameNanos { }
            initialFocusDone = runCatching { categoryFocus.getValue(state.category).requestFocus() }.isSuccess
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 80.dp, end = 80.dp, top = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.nav_settings), style = GlacierText.display(44), color = GlacierColors.Ice)
                Spacer(Modifier.height(12.dp))
                SettingsCategory.entries.forEach { category ->
                    CategoryPill(
                        label = stringResource(category.label),
                        active = category == state.category,
                        onFocused = { viewModel.selectCategory(category) },
                        modifier = Modifier.focusRequester(categoryFocus.getValue(category)),
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    // Left from any card goes back to the open category, not the nearest one.
                    .focusProperties { left = categoryFocus.getValue(state.category) },
            ) {
                if (state.category == SettingsCategory.Subtitles) {
                    SubtitlePreview(state.profile.subtitleStyle, state.previewImage)
                    Spacer(Modifier.height(18.dp))
                }
                val groups = rows(state, viewModel, languageFocus, pinOrigins)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 60.dp),
                ) {
                    groups.forEachIndexed { groupIndex, group ->
                        item(key = "${state.category}/${group.title}") { GroupHeading(group.title, first = groupIndex == 0) }
                        items(group.rows, key = { "${state.category}/${group.title}/${it.label}" }) { row -> SettingCard(row) }
                    }
                }
            }
        }

        TopNav(
            active = NavTarget.Settings,
            kinds = LibraryKind.entries,
            userName = state.userName,
            onSelect = viewModel::onNav,
            down = categoryFocus.getValue(state.category),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        )

        state.languagePicker?.let { target ->
            val server = state.server
            when (target) {
                LanguageTarget.Ui -> LanguagePicker(
                    title = stringResource(R.string.settings_ui_lang),
                    top = LanguageEntry(null, stringResource(R.string.settings_ui_lang_system), stringResource(R.string.settings_ui_lang_system_hint)),
                    languages = UiLanguages,
                    selected = state.profile.uiLanguage.tag,
                    onPick = viewModel::pickLanguage,
                    onDismiss = viewModel::closeLanguages,
                )
                else -> LanguagePicker(
                    title = stringResource(if (target == LanguageTarget.Audio) R.string.settings_audio_lang else R.string.settings_sub_lang_title),
                    top = if (target == LanguageTarget.Audio) {
                        LanguageEntry(null, stringResource(R.string.settings_audio_original), stringResource(R.string.settings_audio_original_hint))
                    } else {
                        LanguageEntry(null, stringResource(R.string.settings_lang_none), stringResource(R.string.settings_lang_none_hint))
                    },
                    languages = state.languages,
                    selected = if (target == LanguageTarget.Audio) server?.audioLanguage else server?.subtitleLanguage,
                    onPick = viewModel::pickLanguage,
                    onDismiss = viewModel::closeLanguages,
                )
            }
        }

        pinPrompt?.let { PinDialog(it, onKey = viewModel.pin::key, onDismiss = viewModel.pin::dismiss) }
    }
}

@Composable
private fun SettingCard(row: SettingRow) {
    when (row) {
        is SettingRow.Toggle -> OptionCard(row.label, row.sub, row.enabled, trailing = {
            ToggleSwitch(row.checked, row.onToggle, row.focus?.let { Modifier.focusRequester(it) } ?: Modifier, row.enabled)
        })
        is SettingRow.Choice -> OptionCard(row.label, row.sub, row.enabled, below = { ChoicePills(row.options, row.selected, row.onSelect, row.enabled) })
        is SettingRow.Swatches -> OptionCard(row.label, row.sub, row.enabled, below = { SwatchPicker(row.swatches, row.selected, row.onSelect) })
        is SettingRow.Value -> OptionCard(
            row.label,
            row.sub,
            row.enabled,
            trailing = { ValueButton(
                    row.value,
                    onClick = { row.onClick?.invoke() },
                    enabled = row.enabled && row.onClick != null,
                    chevron = row.chevron,
                    mono = !row.chevron,
                    modifier = row.focus?.let { Modifier.focusRequester(it) } ?: Modifier,
                ) },
        )
    }
}

@Composable
private fun rows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    languageFocus: FocusRequester,
    pinOrigins: PinOrigins,
): List<SettingGroup> = when (state.category) {
    SettingsCategory.Appearance -> appearanceRows(state, viewModel)
    SettingsCategory.Home -> homeRows(state, viewModel)
    SettingsCategory.Playback -> playbackRows(state, viewModel)
    SettingsCategory.Audio -> audioRows(state, viewModel, languageFocus)
    SettingsCategory.Subtitles -> subtitleRows(state, viewModel, languageFocus)
    SettingsCategory.Account -> accountRows(state, viewModel, languageFocus, pinOrigins)
}

@Composable
private fun appearanceRows(state: SettingsUiState, viewModel: SettingsViewModel): List<SettingGroup> {
    val appearance = state.profile.appearance
    val names = listOf(
        R.string.settings_accent_crevasse,
        R.string.settings_accent_blueice,
        R.string.settings_accent_aurora,
        R.string.settings_accent_polarnight,
        R.string.settings_accent_firn,
    )
    return listOf(
        SettingGroup(
            stringResource(R.string.settings_group_look),
            listOf(
                SettingRow.Swatches(
                    stringResource(R.string.settings_accent),
                    stringResource(R.string.settings_accent_sub),
                    Accent.entries.mapIndexed { i, accent -> Swatch(stringResource(names[i]), accent.main) },
                    appearance.accent.ordinal,
                    onSelect = { i -> viewModel.updateAppearance { it.copy(accent = AccentColor.entries[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_density),
                    stringResource(R.string.settings_density_sub),
                    listOf(stringResource(R.string.settings_density_comfortable), stringResource(R.string.settings_density_compact)),
                    if (appearance.compact) 1 else 0,
                    onSelect = { i -> viewModel.updateAppearance { it.copy(compact = i == 1) } },
                ),
                SettingRow.Toggle(
                    stringResource(R.string.settings_reduce_motion),
                    stringResource(R.string.settings_reduce_motion_sub),
                    appearance.reduceMotion,
                    onToggle = { viewModel.updateAppearance { it.copy(reduceMotion = !it.reduceMotion) } },
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_library),
            listOf(
                SettingRow.Toggle(
                    stringResource(R.string.settings_group_sets),
                    stringResource(R.string.settings_group_sets_sub),
                    appearance.groupCollections,
                    onToggle = { viewModel.updateAppearance { it.copy(groupCollections = !it.groupCollections) } },
                ),
            ),
        ),
    )
}

@Composable
private fun homeRows(state: SettingsUiState, viewModel: SettingsViewModel): List<SettingGroup> {
    val home = state.profile.home
    val sources = listOf(
        Triple(SpotlightSource.ContinueWatching, R.string.settings_spot_continue, R.string.settings_spot_continue_sub),
        Triple(SpotlightSource.RecentlyAdded, R.string.settings_spot_recent, R.string.settings_spot_recent_sub),
        Triple(SpotlightSource.Favorites, R.string.settings_spot_favorites, R.string.settings_spot_favorites_sub),
        Triple(SpotlightSource.Random, R.string.settings_spot_random, R.string.settings_spot_random_sub),
    )
    val source = sources.first { it.first == home.spotlightSource }
    return listOf(
        SettingGroup(
            stringResource(R.string.settings_group_spotlight),
            listOf(
                SettingRow.Choice(
                    stringResource(R.string.settings_spot_source),
                    stringResource(source.third),
                    sources.map { stringResource(it.second) },
                    sources.indexOf(source),
                    onSelect = { i -> viewModel.updateHome { it.copy(spotlightSource = sources[i].first) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_spot_type),
                    stringResource(R.string.settings_spot_type_sub),
                    listOf(R.string.settings_spot_all, R.string.settings_spot_movies, R.string.settings_spot_shows).map { stringResource(it) },
                    home.spotlightType.ordinal,
                    onSelect = { i -> viewModel.updateHome { it.copy(spotlightType = SpotlightType.entries[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_spot_count),
                    stringResource(R.string.settings_spot_count_sub),
                    SpotlightCount.entries.map { it.count.toString() },
                    home.spotlightCount.ordinal,
                    onSelect = { i -> viewModel.updateHome { it.copy(spotlightCount = SpotlightCount.entries[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_spot_rotation),
                    stringResource(R.string.settings_spot_rotation_sub),
                    SpotlightRotation.entries.map { if (it == SpotlightRotation.Off) stringResource(R.string.settings_off) else stringResource(R.string.settings_seconds, it.seconds) },
                    home.spotlightRotation.ordinal,
                    onSelect = { i -> viewModel.updateHome { it.copy(spotlightRotation = SpotlightRotation.entries[i]) } },
                ),
                SettingRow.Toggle(
                    stringResource(R.string.settings_spot_unwatched),
                    stringResource(R.string.settings_spot_unwatched_sub),
                    home.spotlightUnwatched,
                    onToggle = { viewModel.updateHome { it.copy(spotlightUnwatched = !it.spotlightUnwatched) } },
                    enabled = home.spotlightSource != SpotlightSource.ContinueWatching,
                ),
            ),
        ),
    )
}

@Composable
private fun playbackRows(state: SettingsUiState, viewModel: SettingsViewModel): List<SettingGroup> {
    val playback = state.profile.playback
    val upNextLabels = UpNextChoice.entries.map { choice ->
        when (val mode = choice.mode) {
            UpNextMode.Off -> stringResource(R.string.settings_off)
            UpNextMode.WithCredits -> stringResource(R.string.settings_up_next_credits)
            is UpNextMode.Before -> seconds(mode.ms)
        }
    }
    val segmentOptions = listOf(
        stringResource(R.string.settings_segment_none),
        stringResource(R.string.settings_segment_ask),
        stringResource(R.string.settings_segment_skip),
    )
    val segmentActions = listOf(SegmentAction.None, SegmentAction.Ask, SegmentAction.Skip)
    val policy = playback.segmentPolicy
    val segments = listOf(
        Triple(SegmentKind.Intro, R.string.settings_segment_intro, R.string.settings_segment_intro_sub),
        Triple(SegmentKind.Recap, R.string.settings_segment_recap, R.string.settings_segment_recap_sub),
        Triple(SegmentKind.Preview, R.string.settings_segment_preview, R.string.settings_segment_preview_sub),
        Triple(SegmentKind.Commercial, R.string.settings_segment_commercial, R.string.settings_segment_commercial_sub),
        Triple(SegmentKind.Outro, R.string.settings_segment_outro, R.string.settings_segment_outro_sub),
    ).map { (kind, label, sub) ->
        SettingRow.Choice(
            stringResource(label),
            stringResource(sub),
            segmentOptions,
            segmentActions.indexOf(policy.action(kind)),
            onSelect = { i -> viewModel.updatePlayback { it.copy(segments = policy.actions + (kind to segmentActions[i])) } },
        )
    }

    return listOf(
        SettingGroup(
            stringResource(R.string.settings_group_general),
            listOf(
                SettingRow.Toggle(
                    stringResource(R.string.settings_theme_songs),
                    stringResource(R.string.settings_theme_songs_sub),
                    playback.themeSongs,
                    onToggle = { viewModel.updatePlayback { it.copy(themeSongs = !it.themeSongs) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_up_next),
                    stringResource(R.string.settings_up_next_sub),
                    upNextLabels,
                    playback.upNext.ordinal,
                    onSelect = { i -> viewModel.updatePlayback { it.copy(upNext = UpNextChoice.entries[i]) } },
                ),
                SettingRow.Toggle(
                    stringResource(R.string.settings_trailer_auto),
                    stringResource(R.string.settings_trailer_auto_sub),
                    playback.trailerAutoNext,
                    onToggle = { viewModel.updatePlayback { it.copy(trailerAutoNext = !it.trailerAutoNext) } },
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_seeking),
            listOf(
                SettingRow.Choice(
                    stringResource(R.string.settings_seek_back),
                    stringResource(R.string.settings_seek_sub),
                    SeekStep.Back.map { seconds(it.ms) },
                    SeekStep.Back.indexOf(playback.seekBack),
                    onSelect = { i -> viewModel.updatePlayback { it.copy(seekBack = SeekStep.Back[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_seek_forward),
                    stringResource(R.string.settings_seek_sub),
                    SeekStep.Forward.map { seconds(it.ms) },
                    SeekStep.Forward.indexOf(playback.seekForward),
                    onSelect = { i -> viewModel.updatePlayback { it.copy(seekForward = SeekStep.Forward[i]) } },
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_streaming),
            listOf(
                SettingRow.Choice(
                    stringResource(R.string.settings_bitrate),
                    stringResource(R.string.settings_bitrate_sub),
                    MaxBitrate.entries.map { bitrate ->
                        when (bitrate) {
                            MaxBitrate.Auto -> stringResource(R.string.settings_auto)
                            MaxBitrate.M1_5 -> stringResource(R.string.settings_bitrate_low)
                            else -> (bitrate.bitsPerSecond!! / 1_000_000).toString()
                        }
                    },
                    playback.maxBitrate.ordinal,
                    onSelect = { i -> viewModel.updatePlayback { it.copy(maxBitrate = MaxBitrate.entries[i]) } },
                ),
            ),
        ),
        SettingGroup(stringResource(R.string.settings_group_segments), segments),
    )
}

@Composable
private fun audioRows(state: SettingsUiState, viewModel: SettingsViewModel, languageFocus: FocusRequester): List<SettingGroup> {
    val playback = state.profile.playback
    val server = state.server
    val unavailable = stringResource(R.string.settings_server_unavailable).takeIf { state.serverFailed }
    return listOf(
        SettingGroup(
            stringResource(R.string.settings_group_output),
            listOf(
                SettingRow.Choice(
                    stringResource(R.string.settings_audio_channels),
                    stringResource(R.string.settings_audio_channels_sub),
                    listOf(stringResource(R.string.settings_auto), stringResource(R.string.settings_audio_stereo), "5.1", "7.1"),
                    playback.audioChannels.ordinal,
                    onSelect = { i -> viewModel.updatePlayback { it.copy(audioChannels = AudioChannels.entries[i]) } },
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_language),
            listOf(
                SettingRow.Value(
                    stringResource(R.string.settings_audio_lang),
                    unavailable ?: stringResource(R.string.settings_audio_lang_sub),
                    value = server?.audioLanguage?.let { languageName(state, it) } ?: stringResource(R.string.settings_audio_original),
                    onClick = { viewModel.openLanguages(LanguageTarget.Audio) },
                    enabled = server != null,
                    focus = languageFocus,
                ),
                SettingRow.Toggle(
                    stringResource(R.string.settings_default_track),
                    unavailable ?: stringResource(R.string.settings_default_track_sub),
                    server?.playDefaultAudioTrack == true,
                    onToggle = { viewModel.updateServer { it.copy(playDefaultAudioTrack = !it.playDefaultAudioTrack) } },
                    enabled = server != null,
                ),
                SettingRow.Toggle(
                    stringResource(R.string.settings_remember_audio),
                    unavailable ?: stringResource(R.string.settings_remember_audio_sub),
                    server?.rememberAudio == true,
                    onToggle = { viewModel.updateServer { it.copy(rememberAudio = !it.rememberAudio) } },
                    enabled = server != null,
                ),
            ),
        ),
    )
}

@Composable
private fun subtitleRows(state: SettingsUiState, viewModel: SettingsViewModel, languageFocus: FocusRequester): List<SettingGroup> {
    val style = state.profile.subtitleStyle
    val server = state.server
    val unavailable = stringResource(R.string.settings_server_unavailable).takeIf { state.serverFailed }
    val modes = listOf(
        Triple(SubtitleMode.Default, R.string.settings_sub_mode_default, R.string.settings_sub_mode_default_sub),
        Triple(SubtitleMode.Smart, R.string.settings_sub_mode_smart, R.string.settings_sub_mode_smart_sub),
        Triple(SubtitleMode.OnlyForced, R.string.settings_sub_mode_forced, R.string.settings_sub_mode_forced_sub),
        Triple(SubtitleMode.Always, R.string.settings_sub_mode_always, R.string.settings_sub_mode_always_sub),
        Triple(SubtitleMode.None, R.string.settings_sub_mode_none, R.string.settings_sub_mode_none_sub),
    )
    val mode = modes.firstOrNull { it.first == (server?.subtitleMode ?: SubtitleMode.Smart) } ?: modes[1]
    val burnIn = state.profile.playback.subtitleBurnIn
    return listOf(
        SettingGroup(
            stringResource(R.string.settings_group_selection),
            listOf(
                SettingRow.Value(
                    stringResource(R.string.settings_sub_lang),
                    unavailable ?: stringResource(R.string.settings_sub_lang_sub),
                    value = server?.subtitleLanguage?.let { languageName(state, it) } ?: stringResource(R.string.settings_lang_none),
                    onClick = { viewModel.openLanguages(LanguageTarget.Subtitles) },
                    enabled = server != null,
                    focus = languageFocus,
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_mode),
                    unavailable ?: stringResource(mode.third),
                    modes.map { stringResource(it.second) },
                    modes.indexOf(mode),
                    onSelect = { i -> viewModel.updateServer { it.copy(subtitleMode = modes[i].first) } },
                    enabled = server != null,
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_burn),
                    stringResource(R.string.settings_burn_sub),
                    listOf(
                        stringResource(R.string.settings_auto),
                        stringResource(R.string.settings_burn_picture),
                        stringResource(R.string.settings_burn_complex),
                        stringResource(R.string.settings_burn_always),
                    ),
                    burnIn.ordinal,
                    onSelect = { i -> viewModel.updatePlayback { it.copy(subtitleBurnIn = SubtitleBurnIn.entries[i]) } },
                ),
                SettingRow.Toggle(
                    stringResource(R.string.settings_remember_subs),
                    unavailable ?: stringResource(R.string.settings_remember_subs_sub),
                    server?.rememberSubtitles == true,
                    onToggle = { viewModel.updateServer { it.copy(rememberSubtitles = !it.rememberSubtitles) } },
                    enabled = server != null,
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_style),
            listOf(
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_style),
                    stringResource(
                        when (style.mode) {
                            SubtitleStyleMode.Auto -> R.string.settings_sub_style_auto_sub
                            SubtitleStyleMode.Custom -> R.string.settings_sub_style_custom_sub
                            SubtitleStyleMode.Native -> R.string.settings_sub_style_native_sub
                        },
                    ),
                    listOf(stringResource(R.string.settings_auto), stringResource(R.string.settings_sub_style_custom), stringResource(R.string.settings_sub_style_native)),
                    style.mode.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(mode = SubtitleStyleMode.entries[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_size),
                    stringResource(R.string.settings_sub_size_sub),
                    listOf(R.string.settings_sub_size_xs, R.string.settings_sub_size_s, R.string.settings_sub_size_m, R.string.settings_sub_size_l, R.string.settings_sub_size_xl).map { stringResource(it) },
                    style.size.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(size = SubtitleSize.entries[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_weight),
                    stringResource(R.string.settings_sub_weight_sub),
                    listOf(stringResource(R.string.settings_sub_weight_regular), stringResource(R.string.settings_sub_weight_bold)),
                    if (style.bold) 1 else 0,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(bold = i == 1) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_font),
                    stringResource(R.string.settings_sub_font_sub),
                    listOf(R.string.settings_sub_font_default, R.string.settings_sub_font_serif, R.string.settings_sub_font_mono).map { stringResource(it) },
                    style.font.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(font = SubtitleFont.entries[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_color),
                    "#%06X".format(style.color.argb and 0xFFFFFF),
                    listOf(R.string.settings_sub_color_white, R.string.settings_sub_color_yellow, R.string.settings_sub_color_ice, R.string.settings_sub_color_grey).map { stringResource(it) },
                    style.color.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(color = SubtitleColor.entries[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_edge),
                    stringResource(R.string.settings_sub_edge_sub),
                    listOf(
                        R.string.settings_sub_edge_none,
                        R.string.settings_sub_edge_raised,
                        R.string.settings_sub_edge_depressed,
                        R.string.settings_sub_edge_outline,
                        R.string.settings_sub_edge_shadow,
                    ).map { stringResource(it) },
                    style.edge.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(edge = SubtitleEdge.entries[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_position),
                    stringResource(R.string.settings_sub_position_sub),
                    SubtitlePosition.entries.map { stringResource(R.string.settings_sub_line, if (it.line < 0) "−${-it.line}" else it.line.toString()) },
                    style.position.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(position = SubtitlePosition.entries[i]) } },
                ),
            ),
        ),
    )
}

@Composable
private fun accountRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    languageFocus: FocusRequester,
    pinOrigins: PinOrigins,
): List<SettingGroup> {
    val lock = state.lock
    val protection = lock.protection
    val signedInAs = SettingRow.Value(
        stringResource(R.string.settings_signed_in_as),
        stringResource(R.string.settings_signed_in_as_sub),
        value = state.userName,
        onClick = null,
        chevron = false,
    )
    if (protection.pinForSettings && lock.hasPin && !state.accountUnlocked) {
        return listOf(
            SettingGroup(
                stringResource(R.string.settings_group_profile),
                listOf(
                    signedInAs,
                    SettingRow.Value(
                        stringResource(R.string.settings_account_locked),
                        stringResource(R.string.settings_account_locked_sub),
                        value = stringResource(R.string.settings_account_unlock),
                        onClick = pinOrigins.tap("unlock", viewModel::unlockAccount),
                        focus = pinOrigins.of("unlock"),
                    ),
                ),
            ),
        )
    }
    val ages = AgeLimit.entries
    val ageLabel = ageLabel(protection.maxAge)
    @Composable
    fun pinToggle(key: String, label: Int, sub: Int, checked: Boolean, transform: (Protection, Boolean) -> Protection) =
        SettingRow.Toggle(
            stringResource(label),
            stringResource(sub),
            checked = checked,
            onToggle = pinOrigins.tap(key) { viewModel.setPinOption(!checked, transform) },
            focus = pinOrigins.of(key),
        )
    return listOf(
        SettingGroup(
            stringResource(R.string.settings_group_profile),
            listOf(
                signedInAs,
                SettingRow.Value(
                    stringResource(R.string.settings_ui_lang),
                    stringResource(if (state.profile.uiLanguage == UiLanguage.System) R.string.settings_ui_lang_system_sub else R.string.settings_ui_lang_profile_sub),
                    value = UiLanguages.firstOrNull { it.code == state.profile.uiLanguage.tag }?.name ?: stringResource(R.string.settings_ui_lang_system),
                    onClick = { viewModel.openLanguages(LanguageTarget.Ui) },
                    focus = languageFocus,
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_parental),
            listOf(
                SettingRow.Choice(
                    stringResource(R.string.settings_max_age),
                    when {
                        protection.maxAge == AgeLimit.All -> stringResource(R.string.settings_max_age_all_sub)
                        protection.pinForLocked -> stringResource(R.string.settings_max_age_locked_sub, ageLabel)
                        else -> stringResource(R.string.settings_max_age_hidden_sub, ageLabel)
                    },
                    options = ages.map { ageLabel(it) },
                    selected = ages.indexOf(protection.maxAge),
                    onSelect = { viewModel.setMaxAge(ages[it]) },
                ),
                SettingRow.Toggle(
                    stringResource(R.string.settings_block_unrated),
                    stringResource(R.string.settings_block_unrated_sub),
                    checked = protection.blockUnrated,
                    onToggle = { viewModel.setBlockUnrated(!protection.blockUnrated) },
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_pin),
            listOf(
                pinToggle("profile", R.string.settings_pin_profile, R.string.settings_pin_profile_sub, protection.pinOnProfileSwitch) { p, on ->
                    p.copy(pinOnProfileSwitch = on)
                },
                pinToggle("locked", R.string.settings_pin_locked, R.string.settings_pin_locked_sub, protection.pinForLocked) { p, on ->
                    p.copy(pinForLocked = on)
                },
                pinToggle("settings", R.string.settings_pin_settings, R.string.settings_pin_settings_sub, protection.pinForSettings) { p, on ->
                    p.copy(pinForSettings = on)
                },
                if (lock.hasPin) {
                    SettingRow.Value(
                        stringResource(R.string.settings_pin_change),
                        lock.pinChangedAt
                            ?.let { stringResource(R.string.settings_pin_change_sub, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
                            .orEmpty(),
                        value = stringResource(R.string.settings_pin_change_value),
                        onClick = pinOrigins.tap("change", viewModel::changePin),
                        chevron = false,
                        focus = pinOrigins.of("change"),
                    )
                } else {
                    SettingRow.Value(
                        stringResource(R.string.settings_pin_set),
                        stringResource(R.string.settings_pin_set_sub),
                        value = stringResource(R.string.settings_pin_set_value),
                        onClick = pinOrigins.tap("change", viewModel::changePin),
                        chevron = false,
                        focus = pinOrigins.of("change"),
                    )
                },
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_session),
            listOf(
                SettingRow.Value(
                    stringResource(R.string.home_sign_out),
                    stringResource(R.string.settings_sign_out_sub),
                    value = stringResource(R.string.home_sign_out),
                    onClick = viewModel::signOut,
                    chevron = false,
                ),
            ),
        ),
    )
}

@Composable
private fun ageLabel(limit: AgeLimit): String =
    limit.age?.let { "$it+" } ?: stringResource(R.string.settings_age_all)

/** Buttons that open the PIN dialog; focus goes back to the one used once it closes. */
private class PinOrigins {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private var last: String? = null

    fun of(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun tap(key: String, action: () -> Unit): () -> Unit = {
        last = key
        action()
    }

    fun restore(): Boolean = last?.let { runCatching { of(it).requestFocus() }.getOrDefault(false) } == true
}

/** Languages of Glacier itself, each named in its own language. */
private val UiLanguages = listOf(Language("de", "Deutsch"), Language("en", "English"))

/** A language code as the server keeps it, named in the device language. */
private fun languageName(state: SettingsUiState, code: String): String =
    state.languages.firstOrNull { it.code.equals(code, ignoreCase = true) }?.name
        ?: Languages.name(code)
        ?: code

@Composable
private fun seconds(ms: Long): String =
    if (ms >= 60_000) stringResource(R.string.settings_minute) else stringResource(R.string.settings_seconds, (ms / 1000).toInt())
