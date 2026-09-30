package io.github.glacier_jellyfin.androidtv.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleBackground
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleColor
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleEdge
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleFont
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleMode
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitlePosition
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleSize
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyleMode
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleWeight
import io.github.glacier_jellyfin.androidtv.core.data.settings.UiLanguage
import io.github.glacier_jellyfin.androidtv.core.data.settings.UpNextChoice
import io.github.glacier_jellyfin.androidtv.core.designsystem.Accent
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.SubtitleBurnIn
import io.github.glacier_jellyfin.androidtv.player.subtitleTypeface
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PinDialog
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import io.github.glacier_jellyfin.androidtv.BuildConfig
import io.github.glacier_jellyfin.androidtv.core.updater.ReleaseNotes
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateCandidate
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateChannel
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateState
import io.github.glacier_jellyfin.androidtv.update.megabytes
import io.github.glacier_jellyfin.androidtv.update.message
import io.github.glacier_jellyfin.androidtv.update.publishedDate
import java.text.DateFormat
import kotlin.math.roundToInt
import java.util.Date
import kotlinx.coroutines.launch

/** One card of the settings list; built per category by [rows]. */
private sealed interface SettingRow {
    val label: String
    val sub: String
    val enabled: Boolean

    /** Identifies the card in the list; stays the same while its texts change. */
    val key: String get() = label

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
        val dots: List<Color>? = null,
        val fonts: List<FontFamily>? = null,
    ) : SettingRow

    /** Options switched on and off each, any number of them. */
    data class MultiChoice(
        override val label: String,
        override val sub: String,
        val options: List<String>,
        val selected: Set<Int>,
        val onToggle: (Int) -> Unit,
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

    /** The current value at the end of the card, opening a list to pick another, e.g. a language. */
    data class Value(
        override val label: String,
        override val sub: String,
        val value: String,
        val onClick: () -> Unit,
        override val enabled: Boolean = true,
        val focus: FocusRequester? = null,
    ) : SettingRow

    /** A read-only fact, its value as plain text. */
    data class Info(
        override val label: String,
        override val sub: String,
        val value: String,
    ) : SettingRow {
        override val enabled: Boolean get() = true
    }

    /** Something to do, e.g. sign out: a button like the ones on the rest of the app. */
    data class Action(
        override val label: String,
        override val sub: String,
        val button: String,
        val onClick: () -> Unit,
        override val enabled: Boolean = true,
        val focus: FocusRequester? = null,
        val primary: Boolean = false,
    ) : SettingRow

    /** The app update: its state, an action, a progress bar while downloading and the notes of a new version. */
    data class Update(
        override val label: String,
        override val sub: String,
        val value: String,
        val primary: Boolean,
        val onClick: () -> Unit,
        /** 0..1 while downloading. */
        val progress: Float? = null,
        val progressStart: String = "",
        val progressEnd: String = "",
        val notesTitle: String = "",
        val notes: List<String> = emptyList(),
        override val enabled: Boolean = true,
    ) : SettingRow {
        override val key: String get() = "update"
    }
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
    val focusManager = LocalFocusManager.current
    // Read right after a focus move, so a plain holder rather than state.
    val cardsFocus = remember { object { var value = false } }
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

    // Every category opens scrolled to the top, not where the previous one was left.
    val listState = remember(state.category) { LazyListState() }
    // Right out of the categories always lands on the first card, not the nearest one.
    val firstCard = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 80.dp, end = 80.dp, top = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Column(
                Modifier
                    .width(420.dp)
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionRight) return@onKeyEvent false
                        scope.launch {
                            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                                listState.scrollToItem(0)
                                withFrameNanos { }
                            }
                            runCatching { firstCard.requestFocus() }
                        }
                        true
                    },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.nav_settings), style = GlacierText.display(44), color = GlacierColors.Ice)
                Spacer(Modifier.height(12.dp))
                SettingsCategory.entries.forEach { category ->
                    CategoryPill(
                        label = stringResource(category.label),
                        active = category == state.category,
                        dot = category == SettingsCategory.System && state.update.let { it is UpdateState.Available || it is UpdateState.Ready },
                        onFocused = { viewModel.selectCategory(category) },
                        modifier = Modifier.focusRequester(categoryFocus.getValue(category)),
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .onFocusChanged { cardsFocus.value = it.hasFocus }
                    // Left out of the cards always lands on the open category, not the nearest one. Focus
                    // properties cannot redirect it reliably here, so the move is made by hand and corrected.
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionLeft) return@onKeyEvent false
                        if (focusManager.moveFocus(FocusDirection.Left) && !cardsFocus.value) {
                            runCatching { categoryFocus.getValue(state.category).requestFocus() }
                        }
                        true
                    },
            ) {
                if (state.category == SettingsCategory.Subtitles) {
                    SubtitlePreview(state.profile.subtitleStyle, state.previewImage)
                    Spacer(Modifier.height(18.dp))
                }
                val groups = rows(state, viewModel, languageFocus, pinOrigins)
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 60.dp),
                ) {
                    groups.forEachIndexed { groupIndex, group ->
                        item(key = "${state.category}/${group.title}") { GroupHeading(group.title, first = groupIndex == 0) }
                        itemsIndexed(group.rows, key = { _, row -> "${state.category}/${group.title}/${row.key}" }) { rowIndex, row ->
                            if (groupIndex == 0 && rowIndex == 0) {
                                Box(Modifier.focusRequester(firstCard).focusGroup()) { SettingCard(row) }
                            } else {
                                SettingCard(row)
                            }
                        }
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
        is SettingRow.Choice -> OptionCard(row.label, row.sub, row.enabled, below = { ChoicePills(row.options, { it == row.selected }, row.onSelect, row.enabled, dots = row.dots, fonts = row.fonts) })
        is SettingRow.MultiChoice -> OptionCard(row.label, row.sub, row.enabled, below = { ChoicePills(row.options, { it in row.selected }, row.onToggle, row.enabled, check = true) })
        is SettingRow.Swatches -> OptionCard(row.label, row.sub, row.enabled, below = { SwatchPicker(row.swatches, row.selected, row.onSelect) })
        is SettingRow.Update -> UpdateCard(row)
        is SettingRow.Value -> OptionCard(
            row.label,
            row.sub,
            row.enabled,
            trailing = { ValueButton(
                    row.value,
                    onClick = row.onClick,
                    enabled = row.enabled,
                    modifier = row.focus?.let { Modifier.focusRequester(it) } ?: Modifier,
                ) },
        )
        is SettingRow.Info -> InfoCard(row.label, row.sub, row.value)
        is SettingRow.Action -> OptionCard(
            row.label,
            row.sub,
            row.enabled,
            trailing = { ActionButton(
                    onClick = row.onClick,
                    label = row.button,
                    primary = row.primary,
                    enabled = row.enabled,
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
    SettingsCategory.System -> systemRows(state, viewModel)
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
        SpotlightSource.ContinueWatching to R.string.settings_spot_continue,
        SpotlightSource.RecentlyAdded to R.string.settings_spot_recent,
        SpotlightSource.Favorites to R.string.settings_spot_favorites,
        SpotlightSource.Random to R.string.settings_spot_random,
    )
    val on = home.spotlightSources.isNotEmpty()
    return listOf(
        SettingGroup(
            stringResource(R.string.settings_group_spotlight),
            listOf(
                SettingRow.MultiChoice(
                    stringResource(R.string.settings_spot_source),
                    stringResource(if (on) R.string.settings_spot_source_sub else R.string.settings_spot_source_off),
                    sources.map { stringResource(it.second) },
                    sources.indices.filterTo(HashSet()) { sources[it].first in home.spotlightSources },
                    onToggle = { i ->
                        val source = sources[i].first
                        viewModel.updateHome { it.copy(spotlightSources = if (source in it.spotlightSources) it.spotlightSources - source else it.spotlightSources + source) }
                    },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_spot_type),
                    stringResource(R.string.settings_spot_type_sub),
                    listOf(R.string.settings_spot_all, R.string.settings_spot_movies, R.string.settings_spot_shows).map { stringResource(it) },
                    home.spotlightType.ordinal,
                    onSelect = { i -> viewModel.updateHome { it.copy(spotlightType = SpotlightType.entries[i]) } },
                    enabled = on,
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_spot_count),
                    stringResource(R.string.settings_spot_count_sub),
                    SpotlightCount.entries.map { it.count.toString() },
                    home.spotlightCount.ordinal,
                    onSelect = { i -> viewModel.updateHome { it.copy(spotlightCount = SpotlightCount.entries[i]) } },
                    enabled = on,
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_spot_rotation),
                    stringResource(R.string.settings_spot_rotation_sub),
                    SpotlightRotation.entries.map { if (it == SpotlightRotation.Off) stringResource(R.string.settings_off) else stringResource(R.string.settings_seconds, it.seconds) },
                    home.spotlightRotation.ordinal,
                    onSelect = { i -> viewModel.updateHome { it.copy(spotlightRotation = SpotlightRotation.entries[i]) } },
                    enabled = on,
                ),
                SettingRow.Toggle(
                    stringResource(R.string.settings_spot_unwatched),
                    stringResource(R.string.settings_spot_unwatched_sub),
                    home.spotlightUnwatched,
                    onToggle = { viewModel.updateHome { it.copy(spotlightUnwatched = !it.spotlightUnwatched) } },
                    enabled = on,
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
    val context = LocalContext.current
    val fonts = remember { SubtitleFont.entries.map { FontFamily(subtitleTypeface(context, it, SubtitleWeight.Regular)) } }
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
                    listOf(
                        R.string.settings_sub_weight_regular,
                        R.string.settings_sub_weight_medium,
                        R.string.settings_sub_weight_semibold,
                        R.string.settings_sub_weight_bold,
                    ).map { stringResource(it) },
                    style.weight.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(weight = SubtitleWeight.entries[i]) } },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_font),
                    stringResource(R.string.settings_sub_font_sub),
                    listOf(
                        R.string.settings_sub_font_default,
                        R.string.settings_sub_font_sans,
                        R.string.settings_sub_font_condensed,
                        R.string.settings_sub_font_serif,
                        R.string.settings_sub_font_mono,
                        R.string.settings_sub_font_serif_mono,
                        R.string.settings_sub_font_casual,
                        R.string.settings_sub_font_cursive,
                        R.string.settings_sub_font_smallcaps,
                    ).map { stringResource(it) },
                    style.font.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(font = SubtitleFont.entries[i]) } },
                    fonts = fonts,
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_color),
                    "#%06X".format(style.color.argb and 0xFFFFFF),
                    listOf(
                        R.string.settings_sub_color_white,
                        R.string.settings_sub_color_grey,
                        R.string.settings_sub_color_yellow,
                        R.string.settings_sub_color_amber,
                        R.string.settings_sub_color_green,
                        R.string.settings_sub_color_cyan,
                        R.string.settings_sub_color_ice,
                        R.string.settings_sub_color_pink,
                    ).map { stringResource(it) },
                    style.color.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(color = SubtitleColor.entries[i]) } },
                    dots = SubtitleColor.entries.map { Color(it.argb) },
                ),
                SettingRow.Choice(
                    stringResource(R.string.settings_sub_background),
                    stringResource(R.string.settings_sub_background_sub),
                    listOf(
                        R.string.settings_sub_background_none,
                        R.string.settings_sub_background_translucent,
                        R.string.settings_sub_background_solid,
                    ).map { stringResource(it) },
                    style.background.ordinal,
                    onSelect = { i -> viewModel.updateSubtitleStyle { it.copy(background = SubtitleBackground.entries[i]) } },
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
    val signedInAs = SettingRow.Info(
        stringResource(R.string.settings_signed_in_as),
        stringResource(R.string.settings_signed_in_as_sub),
        value = state.userName,
    )
    if (protection.pinForSettings && lock.hasPin && !state.accountUnlocked) {
        return listOf(
            SettingGroup(
                stringResource(R.string.settings_group_profile),
                listOf(
                    signedInAs,
                    SettingRow.Action(
                        stringResource(R.string.settings_account_locked),
                        stringResource(R.string.settings_account_locked_sub),
                        button = stringResource(R.string.settings_account_unlock),
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
                    SettingRow.Action(
                        stringResource(R.string.settings_pin_change),
                        lock.pinChangedAt
                            ?.let { stringResource(R.string.settings_pin_change_sub, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))) }
                            .orEmpty(),
                        button = stringResource(R.string.settings_pin_change_value),
                        onClick = pinOrigins.tap("change", viewModel::changePin),
                        focus = pinOrigins.of("change"),
                    )
                } else {
                    SettingRow.Action(
                        stringResource(R.string.settings_pin_set),
                        stringResource(R.string.settings_pin_set_sub),
                        button = stringResource(R.string.settings_pin_set_value),
                        onClick = pinOrigins.tap("change", viewModel::changePin),
                        focus = pinOrigins.of("change"),
                    )
                },
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_session),
            listOf(
                SettingRow.Action(
                    stringResource(R.string.home_sign_out),
                    stringResource(R.string.settings_sign_out_sub),
                    button = stringResource(R.string.home_sign_out),
                    onClick = viewModel::signOut,
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

@Composable
private fun systemRows(state: SettingsUiState, viewModel: SettingsViewModel): List<SettingGroup> {
    val context = LocalContext.current
    val device = remember { deviceSummary(context) }
    val server = state.serverSummary
    fun info(label: String, sub: String, value: String) = SettingRow.Info(label, sub, value)
    return listOf(
        SettingGroup(
            stringResource(R.string.settings_group_update),
            listOf(
                updateRow(state, viewModel),
                SettingRow.Choice(
                    stringResource(R.string.update_channel),
                    stringResource(if (state.updateChannel == UpdateChannel.Beta) R.string.update_channel_beta_sub else R.string.update_channel_stable_sub),
                    UpdateChannel.entries.map { it.name },
                    state.updateChannel.ordinal,
                    onSelect = { viewModel.updates.setChannel(UpdateChannel.entries[it]) },
                ),
                SettingRow.Toggle(
                    stringResource(R.string.update_auto),
                    stringResource(R.string.update_auto_sub),
                    state.autoUpdate,
                    onToggle = { viewModel.updates.setAutoCheck(!state.autoUpdate) },
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_device),
            listOf(
                info(
                    stringResource(R.string.settings_device_name),
                    stringResource(R.string.settings_device_sub, Build.VERSION.RELEASE, device.width, device.height, device.refreshRate),
                    device.name,
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_server),
            listOf(
                info(stringResource(R.string.settings_server_address), server?.name.orEmpty(), server?.address?.let(::hostOf).orEmpty()),
                info(
                    stringResource(R.string.settings_server_version),
                    stringResource(R.string.settings_server_version_sub),
                    server?.version ?: stringResource(R.string.settings_unknown),
                ),
            ),
        ),
        SettingGroup(
            stringResource(R.string.settings_group_about),
            listOf(
                info(stringResource(R.string.app_name), stringResource(R.string.settings_about_sub), BuildConfig.VERSION_NAME),
                info(stringResource(R.string.settings_source), stringResource(R.string.settings_source_sub), SOURCE_URL),
                info(
                    stringResource(R.string.settings_ffmpeg),
                    stringResource(R.string.settings_ffmpeg_sub),
                    state.ffmpegVersion ?: stringResource(R.string.settings_ffmpeg_missing),
                ),
            ),
        ),
    )
}

/** The update card for each state of the updater (design `updRow()`). */
@Composable
private fun updateRow(state: SettingsUiState, viewModel: SettingsViewModel): SettingRow.Update {
    val updates = viewModel.updates
    val installed = updates.installed
    val resources = LocalResources.current
    val channel = state.updateChannel.name
    // Busy states keep a button that does nothing: disabling it would drop the focus.
    val none = {}
    if (installed == null) {
        return SettingRow.Update(
            stringResource(R.string.update_unsupported),
            stringResource(R.string.update_unsupported_sub, BuildConfig.VERSION_NAME),
            value = stringResource(R.string.update_check),
            primary = false,
            onClick = none,
            enabled = false,
        )
    }
    return when (val update = state.update) {
        UpdateState.Unchecked -> SettingRow.Update(
            stringResource(R.string.update_unchecked),
            stringResource(R.string.update_unchecked_sub, installed.toString(), channel),
            stringResource(R.string.update_check),
            primary = false,
            onClick = updates::check,
        )
        UpdateState.Checking -> SettingRow.Update(
            stringResource(R.string.update_checking),
            stringResource(R.string.update_checking_sub),
            stringResource(R.string.update_checking_value),
            primary = false,
            onClick = none,
        )
        is UpdateState.Current -> SettingRow.Update(
            stringResource(R.string.update_current),
            stringResource(
                R.string.update_current_sub,
                installed.toString(),
                channel,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(update.checkedAt)),
            ),
            stringResource(R.string.update_check),
            primary = false,
            onClick = updates::check,
        )
        is UpdateState.Available -> {
            val candidate = update.candidate
            SettingRow.Update(
                stringResource(if (candidate.version.isBeta) R.string.update_available_beta else R.string.update_available, candidate.version.displayName),
                stringResource(
                    R.string.update_available_sub,
                    installed.toString(),
                    megabytes(resources, candidate.apk.sizeBytes),
                    publishedDate(candidate).orEmpty(),
                ),
                stringResource(R.string.update_download),
                primary = true,
                onClick = { updates.download() },
                notesTitle = stringResource(R.string.update_new_in, candidate.version.toString()),
                notes = notes(candidate),
            )
        }
        is UpdateState.Downloading -> {
            val total = update.candidate.apk.sizeBytes.coerceAtLeast(1)
            val share = (update.bytes.toFloat() / total).coerceIn(0f, 1f)
            SettingRow.Update(
                stringResource(R.string.update_downloading),
                stringResource(R.string.update_downloading_sub),
                stringResource(R.string.update_percent, (share * 100).toInt()),
                primary = false,
                onClick = none,
                progress = share,
                progressStart = stringResource(R.string.update_bar_version, update.candidate.version.toString()),
                progressEnd = stringResource(R.string.update_bar_size, megabytes(resources, update.bytes), megabytes(resources, total)),
            )
        }
        is UpdateState.Ready -> SettingRow.Update(
            stringResource(R.string.update_ready, update.candidate.version.displayName),
            stringResource(R.string.update_ready_sub),
            stringResource(R.string.update_install_now),
            primary = true,
            onClick = updates::install,
            notesTitle = stringResource(R.string.update_new_in, update.candidate.version.toString()),
            notes = notes(update.candidate),
        )
        is UpdateState.Installing -> SettingRow.Update(
            stringResource(R.string.update_installing),
            stringResource(R.string.update_installing_sub),
            stringResource(R.string.update_installing),
            primary = false,
            onClick = none,
        )
        is UpdateState.Failed -> SettingRow.Update(
            update.candidate?.let { stringResource(R.string.update_failed, it.version.displayName) } ?: stringResource(R.string.update_check_failed),
            stringResource(update.error.message()),
            stringResource(R.string.update_retry),
            primary = false,
            onClick = updates::retry,
        )
    }
}

/** The release notes as single lines, "New · Voice search". */
@Composable
private fun notes(candidate: UpdateCandidate): List<String> {
    val fallback = stringResource(R.string.update_notes_fallback)
    val resources = LocalResources.current
    return remember(candidate, resources) {
        ReleaseNotes.parse(candidate.release.body, fallback).flatMap { section ->
            section.items.map { resources.getString(R.string.update_note, section.heading, it.text) }
        }
    }
}

@Composable
private fun UpdateCard(row: SettingRow.Update) {
    OptionCard(
        row.label,
        row.sub,
        row.enabled,
        trailing = { ActionButton(onClick = row.onClick, label = row.value, primary = row.primary, enabled = row.enabled) },
        below = if (row.progress == null && row.notes.isEmpty()) null else {
            { UpdateDetails(row.progress, row.progressStart, row.progressEnd, row.notesTitle, row.notes) }
        },
    )
}

private data class DeviceSummary(val name: String, val width: Int, val height: Int, val refreshRate: Int)

/** The name the device has in the Android settings, and its current display mode. */
private fun deviceSummary(context: Context): DeviceSummary {
    val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    val mode = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
    return DeviceSummary(name, mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0, mode?.refreshRate?.roundToInt() ?: 0)
}

/** "https://jellyfin.example.org:8920" -> "jellyfin.example.org:8920". */
private fun hostOf(address: String): String = address.substringAfter("://").trimEnd('/')

private const val SOURCE_URL = "github.com/Glacier-Jellyfin/glacier-androidtv"
