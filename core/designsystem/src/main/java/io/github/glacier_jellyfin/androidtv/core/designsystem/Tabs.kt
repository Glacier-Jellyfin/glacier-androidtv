package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/**
 * Tabs as text with an accent bar under the open one, over a hairline. They
 * are not buttons: the surrounding sheet keeps the focus and switches them
 * with Left/Right, so they must not look pressable.
 */
@Composable
fun GlacierTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    Box(modifier) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(GlacierColors.GlassBorder),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            labels.forEachIndexed { index, label ->
                val open = index == selected
                val text by animateColorAsState(if (open) GlacierColors.Ice else GlacierColors.Mist, label = "tabText")
                val bar by animateColorAsState(if (open) accent else Color.Transparent, label = "tabBar")
                Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        label,
                        style = GlacierText.body(21, if (open) FontWeight.SemiBold else FontWeight.Medium),
                        color = text,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(PillShape)
                            .background(bar),
                    )
                }
            }
        }
    }
}
