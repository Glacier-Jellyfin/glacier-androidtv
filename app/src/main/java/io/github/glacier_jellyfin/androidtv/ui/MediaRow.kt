package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText

/** Left and right page margin of rows. */
const val PageEdge = 80

/** A titled row of cards that scrolls sideways, as on the home and detail pages. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** Space below the row; the density's row spacing when null. */
    bottomPadding: Int? = null,
    gap: Int = LocalCardSizes.current.rowGap,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    // The row's 26px vertical content padding leaves room for focus scale and ring;
    // pulling it up by 8px keeps the design's 18px between title and cards.
    Column(modifier.fillMaxWidth().padding(top = 12.dp, bottom = ((bottomPadding ?: LocalCardSizes.current.rowBottom) - 26).coerceAtLeast(0).dp)) {
        if (title != null) Row(
            Modifier.padding(start = PageEdge.dp, end = PageEdge.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Both texts share one baseline, so the count sits on the title's line.
            Text(title, style = GlacierText.display(28), color = GlacierColors.Ice, modifier = Modifier.alignByBaseline())
            if (subtitle != null) Text(subtitle, style = GlacierText.body(17), color = GlacierColors.Mist, modifier = Modifier.alignByBaseline())
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberCardPivotSpec()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = PageEdge.dp, vertical = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(gap.dp),
                state = state,
                modifier = Modifier.offset(y = (-8).dp),
                content = content,
            )
        }
    }
}

