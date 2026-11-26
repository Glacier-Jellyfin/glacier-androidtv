package io.github.glacier_jellyfin.androidtv.library

import android.text.format.Formatter
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.PhotoDetails
import io.github.glacier_jellyfin.androidtv.core.data.playback.TranscodeStatus
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.player.DecoderInfo
import io.github.glacier_jellyfin.androidtv.player.FactRow
import io.github.glacier_jellyfin.androidtv.player.InfoCard
import io.github.glacier_jellyfin.androidtv.player.InfoPanelFrame
import io.github.glacier_jellyfin.androidtv.player.OsdSecondary
import io.github.glacier_jellyfin.androidtv.player.ServerCards
import io.github.glacier_jellyfin.androidtv.player.StreamStats
import io.github.glacier_jellyfin.androidtv.player.TechnicalCards
import io.github.glacier_jellyfin.androidtv.player.sizeText
import io.github.glacier_jellyfin.androidtv.player.streamStats
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SCROLL_STEP = 220f
private const val STATS_MS = 1_000L
private const val LABEL_WIDTH = 190
private const val TAB_CONTENT = 0
private const val TAB_TECHNICAL = 1

private typealias Facts = List<Pair<String, String>>

/**
 * The details of a photo or video in the viewer, in the player's info panel style: the first tab tells what it is
 * (the picture, when and where it was taken, its folder). For a photo the Technical tab holds cards for the camera and
 * the file. A video has the player's own Technical and Server tabs: what this device plays live from [playing] (the
 * video of the viewer, which goes on behind the panel), what the server says of the file otherwise. Up and down scroll
 * a tab that is longer than the panel; the frame handles Left and Right, OK and Back.
 */
@Composable
internal fun PhotoInfoPanel(
    info: PhotoInfoState,
    playing: InlineVideoState,
    transcodeStatus: suspend () -> TranscodeStatus?,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val video = info.item is FolderMedia.Video
    val live = playing.takeIf { video && it.id == info.item.id }
    Box(
        Modifier.onKeyEvent { event ->
            val step = when (event.key) {
                Key.DirectionDown -> SCROLL_STEP
                Key.DirectionUp -> -SCROLL_STEP
                else -> return@onKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(step) }
            true
        },
    ) {
        InfoPanelFrame(
            tabs = listOf(
                stringResource(if (video) R.string.player_info_video else R.string.photo_tab_photo),
                stringResource(R.string.player_info_technical),
            ) + if (video) listOf(stringResource(R.string.player_info_server)) else emptyList(),
            onDismiss = onDismiss,
        ) { tab ->
            // Each tab starts at its top.
            LaunchedEffect(tab) { scroll.scrollTo(0) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when {
                    tab == TAB_CONTENT -> ContentTab(info)
                    !video -> TechnicalTab(info)
                    tab == TAB_TECHNICAL -> VideoTechnicalTab(info, live)
                    else -> VideoServerTab(info, live, transcodeStatus)
                }
            }
        }
    }
}

/** What it is: the picture next to its name, when it was taken, where and in which folder. */
@Composable
private fun ContentTab(info: PhotoInfoState) {
    val item = info.item
    val details = info.details
    val video = item is FolderMedia.Video
    val folder = details?.folderName
    val taken = longTakenText(details?.taken ?: item.date)
    val length = if (video) runtimeText(details?.runtimeMs ?: (item as FolderMedia.Video).item.runtimeMs) else null
    val location = details?.let { locationText(it.latitude, it.longitude, it.altitude) }
    val facts = listOfNotNull(
        taken?.let { stringResource(R.string.photo_info_date) to it },
        folder?.let { stringResource(R.string.photo_info_folder) to it },
        length?.let { stringResource(R.string.photo_info_length) to it },
        location?.let { stringResource(R.string.photo_info_location) to it },
    )
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(52.dp)) {
        Artwork(
            when (item) {
                is FolderMedia.Photo -> item.photo.thumbUrl
                is FolderMedia.Video -> item.item.thumbUrl ?: item.item.posterUrl
            },
            Modifier
                .width(320.dp)
                .height(if (video) 180.dp else 240.dp)
                .clip(shape)
                .border(1.dp, GlacierColors.GlassBorder, shape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            folder?.let {
                Text(
                    it,
                    style = GlacierText.body(18),
                    color = OsdSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                item.name,
                style = GlacierText.display(44),
                color = GlacierColors.Ice,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (facts.isNotEmpty()) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    facts.forEach { (label, value) -> FactRow(label, value, labelWidth = LABEL_WIDTH) }
                }
            }
            LoadState(info, spinner = 32)
        }
    }
}

/** What this device decodes of the playing video, read once a second; null while nothing plays. */
@Composable
private fun liveStats(live: InlineVideoState?): StreamStats? {
    val context = LocalContext.current
    val player = live?.player
    val playback = live?.playback
    val stats by produceState<StreamStats?>(null, player, playback) {
        if (player == null) return@produceState
        while (isActive) {
            value = player.streamStats(context, playback?.maxBufferMs, playback)
            delay(STATS_MS)
        }
    }
    return stats
}

/**
 * The player's Technical tab for a video: live values while it plays, where nothing plays (not started, or opened
 * without a stream) what the server knows of its picture and sound.
 */
@Composable
private fun VideoTechnicalTab(info: PhotoInfoState, live: InlineVideoState?) {
    val source = live?.source
    TechnicalCards(
        decoders = live?.decoders ?: DecoderInfo(),
        audioLanguage = source?.audioTracks?.firstOrNull { it.index == source.audioIndex }?.language,
        subtitle = null,
        showSubtitles = source?.subtitles?.isNotEmpty() == true,
        file = source?.file ?: info.details?.let(::staticSourceFile),
        staticFallback = true,
        switch = null,
        stats = liveStats(live),
    )
}

/** The player's Server tab for a video, like a film's; just its file while no stream is open. */
@Composable
private fun VideoServerTab(
    info: PhotoInfoState,
    live: InlineVideoState?,
    transcodeStatus: suspend () -> TranscodeStatus?,
) {
    val source = live?.source
    val stats = liveStats(live)
    if (source != null) {
        ServerCards(
            method = source.method,
            file = source.file,
            audioIndex = source.audioIndex,
            audioTracks = source.audioTracks,
            subtitle = null,
            stats = stats,
            transcodeStatus = transcodeStatus,
        )
    } else {
        info.details?.let { details ->
            Card(
                stringResource(R.string.photo_section_file),
                details.fileName,
                listOfNotNull(formatText(details.format), details.sizeBytes?.takeIf { it > 0 }?.let(::sizeText))
                    .joinToString(" · ").ifEmpty { null },
                emptyList(),
            ).Show()
        } ?: LoadState(info, spinner = 56)
    }
}

/** The cards of the technical tab, one under the other; a card with nothing to show is left out. */
@Composable
private fun TechnicalTab(info: PhotoInfoState) {
    val details = info.details
    if (details == null) {
        LoadState(info, spinner = 56)
        return
    }
    photoCards(details).forEach { it.Show() }
}

/** The spinner while the details load, the failure text when they could not be. */
@Composable
private fun LoadState(info: PhotoInfoState, spinner: Int) {
    when {
        info.failed -> Text(
            stringResource(R.string.photo_info_failed),
            style = GlacierText.body(21),
            color = GlacierColors.Mist,
        )

        info.details == null -> SpinningDiamond(spinner)
    }
}

private class Card(
    val label: String,
    val headline: String?,
    val details: String?,
    val rows: Facts,
) {
    @Composable
    fun Show() {
        InfoCard(
            label = label,
            status = null,
            headline = headline,
            details = details,
            modifier = Modifier.fillMaxWidth(),
        ) {
            rows.forEach { (label, value) -> FactRow(label, value, labelWidth = LABEL_WIDTH) }
        }
    }
}

@Composable
private fun photoCards(details: PhotoDetails): List<Card> {
    val context = LocalContext.current
    val camera = cameraText(details.cameraMake, details.cameraModel)
    val cameraRows = listOfNotNull(
        apertureText(details.aperture)?.let { stringResource(R.string.photo_info_aperture) to it },
        exposureText(details.exposureTime)?.let { stringResource(R.string.photo_info_shutter) to it },
        focalLengthText(details.focalLength)?.let { stringResource(R.string.photo_info_focal_length) to it },
        isoText(details.iso)?.let { stringResource(R.string.photo_info_iso) to it },
        details.software?.let { stringResource(R.string.photo_info_software) to it },
        details.rotation?.let { stringResource(R.string.photo_info_rotation) to "$it°" },
    )
    val fileRows = listOfNotNull(
        dimensionsText(details.width, details.height)?.let { stringResource(R.string.photo_info_dimensions) to it },
        details.sizeBytes?.takeIf { it > 0 }?.let {
            stringResource(R.string.photo_info_size) to Formatter.formatShortFileSize(context, it)
        },
    )
    return listOfNotNull(
        Card(stringResource(R.string.photo_section_camera), camera, null, cameraRows)
            .takeIf { camera != null || cameraRows.isNotEmpty() },
        Card(stringResource(R.string.photo_section_file), formatText(details.format), null, fileRows)
            .takeIf { details.format != null || fileRows.isNotEmpty() },
    )
}
