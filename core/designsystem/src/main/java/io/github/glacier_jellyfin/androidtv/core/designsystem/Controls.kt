package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    /** A fixed width with the label centred, like the update dialog's buttons. */
    /** A small count or fact at the end, like a playlist's number of songs. */
    badge: String? = null,
    width: Int? = null,
    /** A marker in the accent before [badge], like "This device". */
    tag: String? = null,
) {
    val accent = LocalAccent.current
    GlacierClickable(
        onClick = onClick,
        shape = PillShape,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else if (width != null) Modifier.width(width.dp) else Modifier)
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
                .then(if (fillWidth || width != null) Modifier.fillMaxWidth() else Modifier)
                .height(height.dp)
                .clip(PillShape)
                .background(background)
                .then(if (!focused && !primary) Modifier.border(2.dp, GlacierColors.GlassBorder, PillShape) else Modifier)
                .padding(horizontal = if (fillWidth) 30.dp else if (primary) 40.dp else 34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, if (width != null) Alignment.CenterHorizontally else Alignment.Start),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
            Text(
                text,
                style = GlacierText.body(21, FontWeight.SemiBold),
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if ((badge != null || tag != null) && fillWidth) Modifier.weight(1f) else Modifier,
            )
            if (tag != null) {
                Box(
                    Modifier
                        .height(30.dp)
                        .clip(PillShape)
                        .background(if (focused) GlacierColors.Void else accent.main)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tag, style = GlacierText.body(15, FontWeight.Bold), color = if (focused) accent.main else GlacierColors.Void, maxLines = 1)
                }
            }
            if (badge != null) {
                Box(
                    Modifier
                        .height(30.dp)
                        .clip(PillShape)
                        .background(if (focused || primary) GlacierColors.Void.copy(alpha = 0.12f) else GlacierColors.GlassFill2)
                        .border(1.dp, if (focused || primary) GlacierColors.Void.copy(alpha = 0.3f) else GlacierColors.GlassBorder, PillShape)
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(badge, style = GlacierText.body(15, FontWeight.SemiBold), color = if (focused || primary) GlacierColors.Void else GlacierColors.Mist, maxLines = 1)
                }
            }
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
}

/** The PIN key for a number or delete key of the remote or a keyboard. */
private fun remoteKey(key: Key): PinKey? = when (key) {
    Key.Zero, Key.NumPad0 -> PinKey.Digit('0')
    Key.One, Key.NumPad1 -> PinKey.Digit('1')
    Key.Two, Key.NumPad2 -> PinKey.Digit('2')
    Key.Three, Key.NumPad3 -> PinKey.Digit('3')
    Key.Four, Key.NumPad4 -> PinKey.Digit('4')
    Key.Five, Key.NumPad5 -> PinKey.Digit('5')
    Key.Six, Key.NumPad6 -> PinKey.Digit('6')
    Key.Seven, Key.NumPad7 -> PinKey.Digit('7')
    Key.Eight, Key.NumPad8 -> PinKey.Digit('8')
    Key.Nine, Key.NumPad9 -> PinKey.Digit('9')
    Key.Backspace -> PinKey.Delete
    else -> null
}

/** The digits of the [PinWheel] per block: OK, then up, right, down and left. */
private val WHEEL_BLOCKS = listOf("01234", "56789")

/**
 * PIN entry with the D-pad, like the Fire TV's: OK and the four directions
 * type the digits of the current block. The menu key, Play/Pause or a held OK
 * switch between 0–4 and 5–9, rewind deletes. The number keys type as well.
 * Arrows never move the focus here. Back is the caller's: remotes without
 * rewind delete with it.
 *
 * [keyIcons] shows the Fire TV keys (rewind, menu) before [deleteHint] and
 * [switchHint]; otherwise [deleteKey] and [switchKey] (such as "Back" and "OK")
 * show as key caps, a short one round like the remote's OK.
 */
@Composable
fun PinWheel(
    onKey: (PinKey) -> Unit,
    deleteHint: String,
    switchHint: (target: String) -> String,
    keyIcons: Boolean,
    deleteKey: String,
    switchKey: String,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
) {
    var block by remember { mutableIntStateOf(0) }
    val digits = WHEEL_BLOCKS[block]
    // OK types on release, so holding it can switch the block instead. Only a press seen here counts.
    var okDown by remember { mutableStateOf(false) }
    var okHeld by remember { mutableStateOf(false) }
    // No digit lights up: only the dots show that a key was taken, so nobody watching sees the PIN.
    fun type(slot: Int) = onKey(PinKey.Digit(digits[slot]))
    fun wheelKey(key: Key): (() -> Unit)? = when (key) {
        Key.DirectionUp -> { { type(1) } }
        Key.DirectionRight -> { { type(2) } }
        Key.DirectionDown -> { { type(3) } }
        Key.DirectionLeft -> { { type(4) } }
        Key.Menu, Key.MediaPlayPause -> { { block = 1 - block } }
        Key.MediaRewind, Key.Backspace -> { { onKey(PinKey.Delete) } }
        else -> remoteKey(key)?.let { pinKey -> { onKey(pinKey) } }
    }
    Column(
        modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                val first = down && event.nativeKeyEvent.repeatCount == 0
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
                        first -> {
                            okDown = true
                            okHeld = false
                        }
                        down -> if (okDown && !okHeld) {
                            okHeld = true
                            block = 1 - block
                        }
                        event.type == KeyEventType.KeyUp -> {
                            if (okDown && !okHeld) type(0)
                            okDown = false
                        }
                    }
                    else -> {
                        val action = wheelKey(event.key) ?: return@onKeyEvent false
                        // A held key types once.
                        if (first) action()
                    }
                }
                true
            }
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        @Composable
        fun Slot(digit: Char, modifier: Modifier) {
            Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Text(digit.toString(), style = GlacierText.display(34), color = GlacierColors.Ice)
            }
        }
        Box(
            Modifier
                .size(300.dp)
                .clip(CircleShape)
                .background(GlacierColors.GlassFill)
                .border(2.dp, GlacierColors.GlassBorder, CircleShape),
        ) {
            Box(
                Modifier
                    .size(132.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(GlacierColors.Void.copy(alpha = 0.55f))
                    .border(2.dp, GlacierColors.GlassBorder, CircleShape),
            )
            Slot(digits[0], Modifier.align(Alignment.Center))
            Slot(digits[1], Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            Slot(digits[2], Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            Slot(digits[3], Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            Slot(digits[4], Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun Hint(icon: ImageVector, key: String, label: String) = Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (keyIcons) {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).border(2.dp, GlacierColors.GlassBorder2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = GlacierColors.Mist, modifier = Modifier.size(18.dp))
                    }
                } else {
                    val round = key.length <= 2
                    val shape = if (round) CircleShape else RoundedCornerShape(GlacierShapes.RadiusSm)
                    Box(
                        Modifier
                            .then(if (round) Modifier.size(40.dp) else Modifier.height(34.dp))
                            .clip(shape)
                            .background(GlacierColors.GlassFill)
                            .border(2.dp, GlacierColors.GlassBorder2, shape)
                            .padding(horizontal = if (round) 0.dp else 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(key, style = GlacierText.body(16, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1)
                    }
                }
                Text(label, style = GlacierText.body(19, FontWeight.SemiBold), color = GlacierColors.Ice)
            }
            Hint(GlacierIcons.Rewind, deleteKey, deleteHint)
            Hint(GlacierIcons.Menu, switchKey, switchHint(if (block == 0) "5–9" else "0–4"))
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
