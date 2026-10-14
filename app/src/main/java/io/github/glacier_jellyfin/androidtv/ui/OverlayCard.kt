package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors

/**
 * Text over a picture (a video, a bright backdrop) is not legible on its own:
 * the whole picture is dimmed, still showing through a little, and the
 * content sits in the middle, the way every player shows loading or failing.
 */
@Composable
fun OverlayCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Dim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            content = content,
        )
    }
}

private val Dim = GlacierColors.Deep.copy(alpha = 0.8f)
