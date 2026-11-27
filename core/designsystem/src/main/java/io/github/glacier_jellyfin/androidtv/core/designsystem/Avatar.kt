package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/** Someone shown as a circle: a picture, or the [initials] on a colour made from the [name]. */
data class AvatarPerson(
    val name: String,
    val initials: String,
    val imageUrl: String?,
)

/** How a stack of [count] people is drawn: [avatars] pictures, then a "+[more]" circle when [more] is above 0. */
data class AvatarSlots(val avatars: Int, val more: Int)

/**
 * The circles of a stack of [count] people with room for [max]: all of them when they fit,
 * else the last circle takes the place of an avatar and stands for everyone that is left.
 */
fun avatarSlots(count: Int, max: Int): AvatarSlots {
    val room = max.coerceAtLeast(1)
    if (count <= room) return AvatarSlots(count.coerceAtLeast(0), 0)
    return AvatarSlots(room - 1, count - (room - 1))
}

/** A colour of the design's accents, the same one for the same [name] every time. */
fun avatarColor(name: String): Color {
    val accents = Accent.entries
    return accents[Math.floorMod(name.trim().lowercase().hashCode(), accents.size)].deep
}

/**
 * Round avatars that overlap a little, the newest first, at most [max] circles. With more people the last circle
 * reads "+X". The overlap follows the layout direction, so the stack mirrors in right-to-left.
 */
@Composable
fun AvatarStack(
    people: List<AvatarPerson>,
    modifier: Modifier = Modifier,
    max: Int = 3,
    size: Dp = 30.dp,
) {
    if (people.isEmpty()) return
    val slots = avatarSlots(people.size, max)
    val names = people.joinToString(", ") { it.name }
    Row(
        modifier.semantics { contentDescription = names },
        horizontalArrangement = Arrangement.spacedBy(-(size * OVERLAP)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        people.take(slots.avatars).forEach { Avatar(it, size) }
        if (slots.more > 0) {
            Box(
                Modifier
                    .size(size)
                    .ring()
                    .background(GlacierColors.Deep),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+${slots.more}",
                    style = GlacierText.body(1, FontWeight.SemiBold).copy(fontSize = (size.value * LABEL).sp),
                    color = GlacierColors.Ice,
                )
            }
        }
    }
}

/** One round avatar with a thin dark ring, so circles that overlap stay apart. */
@Composable
fun Avatar(
    person: AvatarPerson,
    size: Dp = 30.dp,
    modifier: Modifier = Modifier,
) {
    var loaded by remember(person.imageUrl) { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .ring()
            .background(avatarColor(person.name)),
        contentAlignment = Alignment.Center,
    ) {
        // The initials show while the picture loads, and when there is none.
        if (!loaded) {
            Text(
                person.initials,
                style = GlacierText.body(1, FontWeight.SemiBold).copy(fontSize = (size.value * LABEL).sp),
                color = GlacierColors.Void,
            )
        }
        if (person.imageUrl != null) {
            AsyncImage(
                model = person.imageUrl,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun Modifier.ring(): Modifier = this
    .border(RING, GlacierColors.Void, CircleShape)
    .clip(CircleShape)

/** Part of the diameter each circle covers of the one before it. */
private const val OVERLAP = 0.28f

/** Font size as part of the diameter. */
private const val LABEL = 0.38f
private val RING = 2.dp
