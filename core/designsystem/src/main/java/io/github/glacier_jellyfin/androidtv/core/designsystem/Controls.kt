package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

val PillShape = RoundedCornerShape(percent = 50)

/**
 * The large pill buttons of the design (`bigBtn`): accent-deep when
 * [primary], glass otherwise, solid accent when focused.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    height: Int = 70,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** Stretch to the available width with the label at the start, like the design's list actions. */
    fillWidth: Boolean = false,
) {
    val accent = LocalAccent.current
    GlacierClickable(
        onClick = onClick,
        shape = PillShape,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .alpha(if (enabled) 1f else 0.5f),
        enabled = enabled,
    ) { focused ->
        val background = when {
            focused -> accent.main
            primary -> accent.deep
            else -> GlacierColors.GlassFill
        }
        val foreground = if (focused || primary) GlacierColors.Void else GlacierColors.Ice
        Row(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .height(height.dp)
                .clip(PillShape)
                .background(background)
                .then(if (!focused && !primary) Modifier.border(2.dp, GlacierColors.GlassBorder, PillShape) else Modifier)
                .padding(horizontal = if (fillWidth) 30.dp else if (primary) 40.dp else 34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
            Text(text, style = GlacierText.body(21, FontWeight.SemiBold), color = foreground)
        }
    }
}

/** Translucent panel (glass level 1), e.g. the "Examples" and "Note" cards. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Column(
        modifier = modifier
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Small tinted chip, e.g. "Step 1 of 2". */
@Composable
fun AccentChip(text: String) {
    val accent = LocalAccent.current.main
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(PillShape)
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.4f), PillShape)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GlacierText.body(16, FontWeight.SemiBold), color = GlacierColors.Ice)
    }
}

/** The four dots above a PIN pad. */
@Composable
fun PinDots(filled: Int, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(4) { i ->
            val on = i < filled
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (on) accent else Color.Transparent)
                    .border(2.dp, if (on) accent else GlacierColors.GlassBorder2, CircleShape),
            )
        }
    }
}

sealed interface PinKey {
    data class Digit(val value: Char) : PinKey
    data object Delete : PinKey
    data object Confirm : PinKey
}

/** 3×4 PIN pad: 1–9, delete, 0, OK. */
@Composable
fun PinPad(
    onKey: (PinKey) -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = "OK",
    firstKeyFocus: FocusRequester? = null,
) {
    val keys = "123456789".map { PinKey.Digit(it) } + listOf(PinKey.Delete, PinKey.Digit('0'), PinKey.Confirm)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { key ->
                    KeyCap(
                        onClick = { onKey(key) },
                        width = 92,
                        height = 76,
                        shape = RoundedCornerShape(GlacierShapes.RadiusMd),
                        modifier = if (key == keys.first() && firstKeyFocus != null) Modifier.focusRequester(firstKeyFocus) else Modifier,
                    ) { color ->
                        when (key) {
                            is PinKey.Digit -> Text(key.value.toString(), style = GlacierText.display(26), color = color)
                            PinKey.Delete -> Icon(GlacierIcons.Backspace, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
                            PinKey.Confirm -> Text(confirmLabel, style = GlacierText.display(26), color = color)
                        }
                    }
                }
            }
        }
    }
}

/** Key in the design's filled-pill style (`pill()`): glass at rest, accent fill when focused. */
@Composable
fun KeyCap(
    onClick: () -> Unit,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlacierShapes.RadiusSm),
    active: Boolean = false,
    content: @Composable (color: Color) -> Unit,
) {
    val accent = LocalAccent.current.main
    GlacierClickable(onClick = onClick, shape = shape, modifier = modifier, contentAlignment = Alignment.Center) { focused ->
        Box(
            modifier = Modifier
                .width(width.dp)
                .height(height.dp)
                .clip(shape)
                .background(
                    when {
                        focused -> accent
                        active -> accent.copy(alpha = 0.18f)
                        else -> GlacierColors.GlassFill
                    },
                )
                .border(2.dp, if (focused) accent else if (active) accent.copy(alpha = 0.45f) else GlacierColors.GlassBorder, shape),
            contentAlignment = Alignment.Center,
        ) {
            content(if (focused) GlacierColors.Void else GlacierColors.Ice)
        }
    }
}

/** Full-screen scrim with the rotating diamond, e.g. "Connecting to Home …". */
@Composable
fun BusyOverlay(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xC705090F)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
            SpinningDiamond(size = 110)
            Text(message, style = GlacierText.display(28), color = GlacierColors.Ice)
        }
    }
}
