package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape

/** Ids of the titles the PIN unlocked in this session (ParentalControl.unlockedItems). */
val LocalUnlockedTitles = staticCompositionLocalOf<Set<String>> { emptySet() }

/** The card shows a lock: the title is above the age limit and not unlocked yet (nor its show). */
@Composable
fun MediaItem.showsLock(): Boolean {
    if (!ageLocked) return false
    val unlocked = LocalUnlockedTitles.current
    return id.toString() !in unlocked && seriesId?.toString() !in unlocked
}

/** Lock in the top left corner of a card, clear of the badges on the right. */
@Composable
internal fun LockBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(14.dp)
            .size(34.dp)
            .clip(PillShape)
            .background(Color(0xCC05090F)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(GlacierIcons.Lock, contentDescription = null, tint = GlacierColors.Ice, modifier = Modifier.size(17.dp))
    }
}
