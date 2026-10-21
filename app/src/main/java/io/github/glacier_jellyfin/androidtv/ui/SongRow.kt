package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.player.formatTime

/** Height of a [SongRow]; lists space them [SONG_ROW_GAP] apart. */
const val SONG_ROW_HEIGHT = 64
const val SONG_ROW_GAP = 10

/** A focused row gets this much wider in all, about as much as a card. */
private const val FOCUS_GROWTH = 24

/**
 * A song in a one-column list (album, playlist, the songs tab): number or cover, then
 * title, artist and album in columns, and the length. Holding OK or the menu key opens its
 * options; the mark at the end of the focused row says so.
 */
@Composable
fun SongRow(
    title: String,
    durationMs: Long?,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    coverUrl: String? = null,
    showCover: Boolean = false,
    artist: String? = null,
    album: String? = null,
    showArtist: Boolean = artist != null,
    showAlbum: Boolean = album != null,
) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    GlacierClickable(onClick = onClick, onLongClick = onOptions, shape = shape, maxWidthGrowth = FOCUS_GROWTH.dp, modifier = modifier.fillMaxWidth()) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(SONG_ROW_HEIGHT.dp)
                .clip(shape)
                .background(if (focused) GlacierColors.GlassFill2 else GlacierColors.GlassFill)
                .padding(start = if (showCover) 8.dp else 24.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (showCover) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(GlacierShapes.RadiusSm)).background(GlacierColors.GlassFill2)) {
                    if (coverUrl != null) {
                        AsyncImage(model = coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
                    }
                }
            } else {
                Text(
                    number?.let { "%02d".format(it) }.orEmpty(),
                    style = GlacierText.mono(17),
                    color = GlacierColors.Mist,
                    modifier = Modifier.width(30.dp),
                )
            }
            Text(
                title,
                style = GlacierText.body(19, FontWeight.SemiBold),
                color = if (focused) accent else GlacierColors.Ice,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.4f),
            )
            if (showArtist) SideText(artist, Modifier.weight(1f))
            if (showAlbum) SideText(album, Modifier.weight(1f))
            Text(
                durationMs?.let(::formatTime).orEmpty(),
                style = GlacierText.mono(17),
                color = GlacierColors.Mist,
                textAlign = TextAlign.End,
                modifier = Modifier.width(72.dp),
            )
            HoldOkMark(focused, accent, size = 26)
        }
    }
}

@Composable
private fun SideText(text: String?, modifier: Modifier) {
Text(text.orEmpty(), style = GlacierText.body(17), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
