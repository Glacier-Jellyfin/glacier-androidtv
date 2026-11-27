package io.github.glacier_jellyfin.androidtv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText

/** One entry of the place names list: its [level] (null: off), the [name] with a short [hint], and an [explanation]. */
internal data class PlaceNamesOption(
    val level: Int?,
    val name: String,
    val hint: String,
    val explanation: String,
)

/**
 * The "Place names" list: one line per level with its size, the chosen one ticked, and under the list a two line
 * explanation of the focused one. The explanation has a fixed height, so the list does not jump while focus moves.
 */
@Composable
internal fun PlaceNamesPicker(
    title: String,
    body: String,
    options: List<PlaceNamesOption>,
    selected: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val selectedIndex = options.indexOfFirst { it.level == selected }.coerceAtLeast(0)
    var focusedIndex by remember { mutableIntStateOf(selectedIndex) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xA805090F)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
        Column(
            Modifier
                .width(720.dp)
                .clip(shape)
                .background(GlacierColors.Deep)
                .border(1.dp, GlacierColors.GlassBorder2, shape)
                .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = GlacierText.display(27), color = GlacierColors.Ice)
                Text(body, style = GlacierText.body(17), color = GlacierColors.Mist)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEachIndexed { index, option ->
                    LanguageRow(
                        entry = LanguageEntry(option.level?.toString(), option.name, option.hint),
                        selected = index == selectedIndex,
                        onClick = { onPick(option.level) },
                        modifier = if (index == selectedIndex) Modifier.focusRequester(focus) else Modifier,
                        onFocused = { focusedIndex = index },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
            Text(
                options.getOrNull(focusedIndex)?.explanation.orEmpty(),
                style = GlacierText.body(17),
                color = GlacierColors.Mist,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}
