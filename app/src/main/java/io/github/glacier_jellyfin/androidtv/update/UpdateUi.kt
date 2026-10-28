package io.github.glacier_jellyfin.androidtv.update

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.updater.AppVersion
import io.github.glacier_jellyfin.androidtv.core.updater.NotesSection
import io.github.glacier_jellyfin.androidtv.core.updater.ReleaseNotes
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateCandidate
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateError
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateManager
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateNotice
import io.github.glacier_jellyfin.androidtv.core.updater.WhatsNew
import io.github.glacier_jellyfin.androidtv.core.updater.WhatsNewPrompt
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject

/** The updater for screens outside the settings: the home screen dialogs and the app-wide overlay. */
@HiltViewModel
class UpdateViewModel @Inject constructor(val updates: UpdateManager, val whatsNew: WhatsNew) : ViewModel()

/**
 * The design's update dialog (home screen, once per version): version and
 * package on the left, release notes on the right, "Update now" / "Later".
 * Up/Down scroll the notes, Left/Right move between the buttons.
 */
@Composable
fun UpdateDialog(candidate: UpdateCandidate, installed: AppVersion, onNow: () -> Unit, onLater: () -> Unit) {
    BackHandler(onBack = onLater)
    val accent = LocalAccent.current.main
    val notesScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val nowFocus = remember { FocusRequester() }
    val fallback = stringResource(R.string.update_notes_fallback)
    val language = notesLanguage()
    val notes = remember(candidate, language) { releaseNotes(candidate, fallback, language) }
    GlacierBackground(
        Modifier
            .onPreviewKeyEvent { event ->
                val step = when (event.key) {
                    Key.DirectionUp -> -NOTES_STEP
                    Key.DirectionDown -> NOTES_STEP
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) scope.launch { notesScroll.animateScrollBy(step) }
                true
            }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(Modifier.fillMaxSize().padding(start = 130.dp, end = 130.dp, top = 90.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(48.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(100.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    UpdateMark()
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(if (candidate.version.isBeta) R.string.update_eyebrow_beta else R.string.update_eyebrow).uppercase(),
                            style = GlacierText.label(18, 0.08),
                            color = accent,
                        )
                        // "Beta 5" gets a line of its own instead of wrapping wherever the width ends.
                        Text(stringResource(R.string.update_title, candidate.version.displayName.replace(" Beta", "\nBeta")), style = GlacierText.display(76), color = GlacierColors.Ice)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        VersionChip(installed.toString(), highlight = false)
                        Icon(GlacierIcons.ChevronRight, contentDescription = null, tint = GlacierColors.Mist, modifier = Modifier.size(26.dp))
                        VersionChip(candidate.version.toString(), highlight = true)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FactLine(stringResource(R.string.update_released), publishedDate(candidate).orEmpty(), mono = false)
                        FactLine(stringResource(R.string.update_package), candidate.apk.name, mono = true)
                        FactLine(stringResource(R.string.update_size), megabytes(LocalResources.current, candidate.apk.sizeBytes), mono = true)
                    }
                }
                val range = if (notes.size > 1) {
                    stringResource(R.string.update_release_since, installed.toString())
                } else {
                    stringResource(R.string.update_release, candidate.release.tag)
                }
                NotesPanel(notes, range, notesScroll, Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.update_hint), style = GlacierText.body(17), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    PillButton(stringResource(R.string.update_now), onClick = onNow, primary = true, height = 68, width = 320, modifier = Modifier.focusRequester(nowFocus))
                    PillButton(stringResource(R.string.update_later), onClick = onLater, height = 68, width = 200)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { nowFocus.requestFocus() } }
}

/**
 * After the store installed a newer version (store builds, see [WhatsNew]):
 * the update dialog's layout with the changes since the previous version and a
 * single "OK". Up/Down scroll the notes.
 */
@Composable
fun WhatsNewDialog(prompt: WhatsNewPrompt, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val accent = LocalAccent.current.main
    val notesScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val okFocus = remember { FocusRequester() }
    val fallback = stringResource(R.string.update_notes_fallback)
    val language = notesLanguage()
    val notes = remember(prompt, language) { changelogNotes(prompt, fallback, language) }
    GlacierBackground(
        Modifier
            .onPreviewKeyEvent { event ->
                val step = when (event.key) {
                    Key.DirectionUp -> -NOTES_STEP
                    Key.DirectionDown -> NOTES_STEP
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) scope.launch { notesScroll.animateScrollBy(step) }
                true
            }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(Modifier.fillMaxSize().padding(start = 130.dp, end = 130.dp, top = 90.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(48.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(100.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    UpdateMark(GlacierIcons.Sparkle)
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.whats_new_eyebrow).uppercase(), style = GlacierText.label(18, 0.08), color = accent)
                        Text(stringResource(R.string.update_title, prompt.current.displayName.replace(" Beta", "\nBeta")), style = GlacierText.display(76), color = GlacierColors.Ice)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        VersionChip(prompt.previous.toString(), highlight = false)
                        Icon(GlacierIcons.ChevronRight, contentDescription = null, tint = GlacierColors.Mist, modifier = Modifier.size(26.dp))
                        VersionChip(prompt.current.toString(), highlight = true)
                    }
                    notes.firstOrNull()?.date?.let { date ->
                        Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
                        FactLine(stringResource(R.string.update_released), date, mono = false)
                    }
                }
                val range = if (notes.size > 1) {
                    stringResource(R.string.update_release_since, prompt.previous.toString())
                } else {
                    stringResource(R.string.update_release, "v${prompt.current}")
                }
                NotesPanel(notes, range, notesScroll, Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.whats_new_hint), style = GlacierText.body(17), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
                PillButton(stringResource(R.string.whats_new_ok), onClick = onClose, primary = true, height = 68, width = 200, modifier = Modifier.focusRequester(okFocus))
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { okFocus.requestFocus() } }
}

@Composable
private fun NotesPanel(notes: List<VersionNotes>, range: String, scroll: ScrollState, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    Column(
        modifier
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(start = 44.dp, end = 20.dp, top = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 24.dp), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.update_notes), style = GlacierText.display(30), color = GlacierColors.Ice, modifier = Modifier.weight(1f))
            Text(range, style = GlacierText.mono(17), color = GlacierColors.Mist)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            notes.forEach { release ->
                // Only an update across several releases needs a heading per version.
                if (notes.size > 1) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.update_title, release.version), style = GlacierText.display(26), color = GlacierColors.Ice)
                        release.date?.let { Text(it, style = GlacierText.body(17), color = GlacierColors.Mist, modifier = Modifier.padding(bottom = 3.dp)) }
                    }
                }
                release.sections.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(section.heading.uppercase(), style = GlacierText.label(17, 0.06), color = accent)
                            Box(Modifier.weight(1f).height(1.dp).background(GlacierColors.GlassBorder))
                        }
                        section.items.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.padding(top = 12.dp).size(6.dp).clip(CircleShape).background(GlacierColors.Ice))
                                Text(item.text, style = GlacierText.body(20), color = GlacierColors.Ice, modifier = Modifier.weight(1f))
                                item.ref?.let { Text(it, style = GlacierText.mono(16), color = GlacierColors.Mist, modifier = Modifier.padding(top = 3.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The outlined diamond with the accent core and [icon]: a down arrow for an update, a sparkle for "What's new". */
@Composable
private fun UpdateMark(icon: ImageVector = GlacierIcons.ArrowDown) {
    val accent = LocalAccent.current.main
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val unit = size.width / 26f
            val outline = Path().apply {
                moveTo(13 * unit, 1 * unit)
                lineTo(25 * unit, 13 * unit)
                lineTo(13 * unit, 25 * unit)
                lineTo(1 * unit, 13 * unit)
                close()
            }
            drawPath(outline, accent, style = Stroke(width = unit, join = StrokeJoin.Round))
        }
        Box(
            Modifier.size(40.dp).rotate(45f).clip(RoundedCornerShape(4.dp)).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = GlacierColors.Void, modifier = Modifier.size(24.dp).rotate(-45f))
        }
    }
}

@Composable
private fun VersionChip(text: String, highlight: Boolean) {
    val accent = LocalAccent.current.main
    Box(
        Modifier
            .height(40.dp)
            .clip(PillShape)
            .background(if (highlight) accent.copy(alpha = 0.16f) else GlacierColors.GlassFill)
            .border(1.dp, if (highlight) accent.copy(alpha = 0.5f) else GlacierColors.GlassBorder, PillShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (highlight) GlacierText.mono(19).copy(fontWeight = FontWeight.SemiBold) else GlacierText.mono(19),
            color = if (highlight) accent else GlacierColors.Mist,
        )
    }
}

@Composable
private fun FactLine(label: String, value: String, mono: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(label, style = GlacierText.body(19), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
        Text(value, style = if (mono) GlacierText.mono(19) else GlacierText.body(19), color = GlacierColors.Ice)
    }
}

/**
 * "Glacier 1.4.0 is being installed …" while Android takes over. It leaves
 * the focus where it is, so a cancelled installation returns there; the
 * activity holds back the keys meanwhile (see MainActivity).
 */
@Composable
fun InstallingOverlay(version: AppVersion) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD105090F)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
            SpinningDiamond(110)
            Text(stringResource(R.string.update_installing_title, version.displayName), style = GlacierText.display(28), color = GlacierColors.Ice)
            Text(stringResource(R.string.update_installing_hint), style = GlacierText.body(19), color = GlacierColors.Mist)
        }
    }
}

/**
 * "Downloading Glacier 1.4.0 …" with a progress bar, right after "Update now":
 * the installation follows by itself. Like [InstallingOverlay] it leaves the
 * focus where it is; the activity holds back every key but Back (see MainActivity),
 * which cancels the download.
 */
@Composable
fun DownloadingOverlay(version: AppVersion, bytes: Long, total: Long, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val accent = LocalAccent.current.main
    val resources = LocalResources.current
    val share = (bytes.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD105090F)),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(640.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
            UpdateMark()
            Text(stringResource(R.string.update_downloading_title, version.displayName), style = GlacierText.display(28), color = GlacierColors.Ice)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(PillShape).background(accent))
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.update_percent, (share * 100).toInt()),
                        style = GlacierText.mono(17),
                        color = GlacierColors.Mist,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.update_bar_size, megabytes(resources, bytes), megabytes(resources, total)),
                        style = GlacierText.mono(17),
                        color = GlacierColors.Mist,
                    )
                }
            }
            Text(stringResource(R.string.update_downloading_hint), style = GlacierText.body(19), color = GlacierColors.Mist)
        }
    }
}

/** The notes of one release the update brings; [version] like "0.2.2", [date] see [publishedDate]. */
class VersionNotes(val version: String, val date: String?, val sections: List<NotesSection>)

/** The interface language ("de", "en"), which picks the language of the release notes. */
@Composable
fun notesLanguage(): String = LocalConfiguration.current.locales[0].language

/**
 * The notes of every release from the installed version up to [candidate] in [language]
 * (English where a release has no translation), newest first; releases without notes left out.
 */
fun releaseNotes(candidate: UpdateCandidate, fallbackHeading: String, language: String): List<VersionNotes> =
    candidate.changes
        .map {
            val body = ReleaseNotes.localized(it.body, language)
            VersionNotes(it.tag.removePrefix("v"), publishedDate(it.publishedAt), ReleaseNotes.parse(body, fallbackHeading))
        }
        .filter { it.sections.isNotEmpty() }

/** The notes of every version [prompt] brings in [language] (English where there is no translation), newest first. */
fun changelogNotes(prompt: WhatsNewPrompt, fallbackHeading: String, language: String): List<VersionNotes> =
    prompt.changes
        .map { english ->
            val entry = prompt.translations[language]?.firstOrNull { it.version == english.version } ?: english
            VersionNotes(entry.version.toString(), changelogDate(entry.date), ReleaseNotes.parse(entry.body, fallbackHeading))
        }
        .filter { it.sections.isNotEmpty() }

/** A changelog date like "2026-10-27" in the interface language. */
private fun changelogDate(date: String?): String? =
    date
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.let { DateFormat.getDateInstance(DateFormat.LONG).format(Date.from(it.atStartOfDay(ZoneId.systemDefault()).toInstant())) }

/** Glacier's page in [store] ("amazon", "play"), where a store build gets its updates; the web page if the store app is missing. */
fun openStorePage(context: Context, store: String) {
    val pkg = context.packageName
    val pages = when (store) {
        "play" -> listOf("market://details?id=$pkg", "https://play.google.com/store/apps/details?id=$pkg")
        else -> listOf("amzn://apps/android?p=$pkg", "https://www.amazon.com/gp/mas/dl/android?p=$pkg")
    }
    pages.firstOrNull { page ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, page.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}

/** The release date in the interface language, e.g. "24 September 2026"; null if GitHub sent none. */
fun publishedDate(candidate: UpdateCandidate): String? = publishedDate(candidate.release.publishedAt)

private fun publishedDate(publishedAt: String?): String? =
    publishedAt
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?.let { DateFormat.getDateInstance(DateFormat.LONG).format(Date.from(it)) }

fun megabytes(resources: Resources, bytes: Long): String = resources.getString(R.string.update_mb, bytes / 1_000_000.0)

fun UpdateError.message(): Int = when (this) {
    UpdateError.Network -> R.string.update_error_network
    UpdateError.Checksum -> R.string.update_error_checksum
    UpdateError.Package -> R.string.update_error_package
    UpdateError.Install -> R.string.update_error_install
}

/** Toast text for a notice of the updater. */
fun UpdateNotice.text(context: Context): String = when (this) {
    is UpdateNotice.Downloaded -> context.getString(R.string.update_downloaded)
    UpdateNotice.InstallCancelled -> context.getString(R.string.update_install_cancelled)
    is UpdateNotice.Failed -> context.getString(error.message())
}

private const val NOTES_STEP = 160f
