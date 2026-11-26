package io.github.glacier_jellyfin.androidtv.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.PhotoDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.PhotoLocation
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.player.FactRow
import io.github.glacier_jellyfin.androidtv.player.InfoPanelFrame
import java.text.NumberFormat
import java.util.UUID
import kotlin.math.roundToLong

@Composable
internal fun PhotoInfoPanel(
    photo: FolderMedia.Photo,
    place: PhotoPlace?,
    loadDetails: suspend (UUID) -> PhotoDetails,
    onDismiss: () -> Unit,
) {
    val result by produceState<Result<PhotoDetails>?>(null, photo.id) {
        value = runSuspendCatching { loadDetails(photo.id) }
    }
    InfoPanelFrame(tabs = listOf(stringResource(R.string.photo_info)), onDismiss = onDismiss, scrollContent = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(52.dp)) {
            AsyncImage(
                model = photo.photo.thumbUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(480.dp).height(360.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    photo.name,
                    style = GlacierText.display(44),
                    color = GlacierColors.Ice,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = result
                when {
                    details == null -> SpinningDiamond(48)

                    details.isFailure -> {
                        longTakenText(photo.date)?.let {
                            FactRow(stringResource(R.string.photo_info_date), it, labelWidth = 180)
                        }
                        Text(
                            stringResource(R.string.photo_info_error),
                            style = GlacierText.body(20),
                            color = GlacierColors.Mist,
                        )
                    }

                    else -> PhotoInfoFacts(details.getOrThrow(), place)
                }
            }
        }
    }
}

@Composable
private fun PhotoInfoFacts(details: PhotoDetails, place: PhotoPlace?) {
    val unavailable = stringResource(R.string.photo_info_unavailable)
    val takenAt = longTakenText(details.takenAt)
    val createdAt = if (takenAt == null) longTakenText(details.createdAt) else null
    FactRow(stringResource(R.string.photo_info_taken_date), takenAt ?: unavailable, labelWidth = 180)
    createdAt?.let { FactRow(stringResource(R.string.photo_info_created_date), it, labelWidth = 180) }
    FactRow(
        stringResource(R.string.photo_info_location),
        photoLocationText(details.location, place) ?: unavailable,
        labelWidth = 180,
        maxValueLines = Int.MAX_VALUE,
    )
    if (details.location.names.isEmpty() && place?.fromOpenStreetMap == true) OpenStreetMapAttribution()
    FactRow(
        stringResource(R.string.photo_info_path),
        details.path ?: unavailable,
        labelWidth = 180,
        maxValueLines = Int.MAX_VALUE,
    )

    val width = details.width ?: 0
    val height = details.height ?: 0
    val dimensions = if (width > 0 && height > 0) stringResource(R.string.photo_info_pixels, width, height) else null
    val rows = listOfNotNull(
        details.fileName?.let { stringResource(R.string.photo_info_file_name) to it },
        dimensions?.let { stringResource(R.string.photo_info_dimensions) to it },
        details.camera?.let { stringResource(R.string.photo_info_camera) to it },
        photoExposure(details)?.let { stringResource(R.string.photo_info_exposure) to it },
    )
    rows.forEach { (label, value) -> FactRow(label, value, labelWidth = 180) }
    details.description?.let {
        Text(
            it,
            style = GlacierText.body(20),
            color = GlacierColors.Mist,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun photoLocationText(location: PhotoLocation, place: PhotoPlace?): String? {
    val coordinates = location.coordinates?.let { point ->
        val numbers = NumberFormat.getNumberInstance(LocalLocale.current.platformLocale).apply {
            maximumFractionDigits = 6
            isGroupingUsed = false
        }
        "${numbers.format(point.latitude)}° · ${numbers.format(point.longitude)}°"
    }
    val names = location.names.ifEmpty { listOfNotNull(place?.name) }
    return (names + listOfNotNull(coordinates)).joinToString(" · ").takeIf(String::isNotBlank)
}

@Composable
internal fun OpenStreetMapAttribution() {
    Text(
        stringResource(R.string.photo_openstreetmap_attribution),
        style = GlacierText.body(15),
        color = GlacierColors.Mist,
    )
}

@Composable
private fun photoExposure(details: PhotoDetails): String? {
    val numbers = NumberFormat.getNumberInstance(LocalLocale.current.platformLocale).apply { maximumFractionDigits = 3 }
    val time = details.exposureTime?.takeIf { it.isFinite() && it > 0 }?.let {
        if (it <= 0.5) "1/${(1 / it).roundToLong()} s" else "${numbers.format(it)} s"
    }
    return listOfNotNull(
        details.focalLength?.takeIf { it.isFinite() && it > 0 }?.let { "${numbers.format(it)} mm" },
        details.aperture?.takeIf { it.isFinite() && it > 0 }?.let { "f/${numbers.format(it)}" },
        time,
        details.iso?.takeIf { it > 0 }?.let { "ISO $it" },
    ).joinToString(" · ").takeIf(String::isNotBlank)
}
