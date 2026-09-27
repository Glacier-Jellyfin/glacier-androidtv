package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent

/**
 * The facts line of spotlight and detail pages: rating, age rating, the
 * dot-separated [facts] and a technical badge ("4K HDR", or [tech] if given).
 */
@Composable
fun FactsRow(item: MediaItem, facts: List<String>, tech: String? = qualityText(item.quality), size: Int = 20) {
    val accent = LocalAccent.current.main
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        item.communityRating?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(GlacierIcons.Star, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(ratingText(it), style = GlacierText.body(size, FontWeight.Bold), color = accent)
            }
        }
        ageRatingText(item.officialRating)?.let { FactBadge(it, GlacierColors.GlassBorder2, 17, GlacierColors.Ice) }
        if (facts.isNotEmpty()) {
            Text(facts.joinToString("  ·  "), style = GlacierText.body(size), color = GlacierColors.Mist)
        }
        tech?.let { FactBadge(it, GlacierColors.GlassBorder, 15, GlacierColors.Mist) }
    }
}

@Composable
fun FactBadge(text: String, border: Color, size: Int, color: Color) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusSm)
    Box(Modifier.border(1.dp, border, shape).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = GlacierText.body(size).copy(letterSpacing = 0.06.em), color = color)
    }
}
