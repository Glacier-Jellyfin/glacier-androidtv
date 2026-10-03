package io.github.glacier_jellyfin.androidtv.setup

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.AccentChip
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierMark
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.ui.ModalSheet

/**
 * The two-column frame shared by all setup screens: an intro column of 560
 * on the left, the controls (at most 880 wide) on the right.
 */
@Composable
fun SetupLayout(
    intro: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = 130.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(110.dp),
    ) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(26.dp), content = intro)
        Column(Modifier.weight(1f).widthIn(max = 880.dp).padding(horizontal = 24.dp), content = content)
    }
}

/** Mark, title and description at the top of the intro column. */
@Composable
fun SetupHeading(title: String, body: String, brand: String? = null) {
    GlacierMark(size = 62)
    if (brand != null) {
        Text(brand.uppercase(), style = GlacierText.body(24, FontWeight.Bold).copy(letterSpacing = 0.34.em), color = GlacierColors.Ice)
    }
    Text(title, style = GlacierText.display(60).copy(lineHeight = 63.sp), color = GlacierColors.Ice)
    Text(body, style = GlacierText.body(23).copy(lineHeight = 34.sp), color = GlacierColors.Mist, modifier = Modifier.widthIn(max = 490.dp))
}

/** "Step 1 of 2 · Server" under the intro. */
@Composable
fun SetupStep(step: Int, label: String) {
    Row(
        modifier = Modifier.padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccentChip(stringResource(R.string.setup_step, step, 2))
        Text(label, style = GlacierText.body(18), color = GlacierColors.Mist)
    }
}

/** The accent dot in front of status lines; it pulses while something is going on. */
@Composable
fun PulseDot(pulsing: Boolean = true) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(0.5f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulseAlpha")
    Box(
        Modifier
            .size(10.dp)
            .alpha(if (pulsing) alpha else 1f)
            .clip(CircleShape)
            .background(LocalAccent.current.main),
    )
}

/**
 * The design's text field (104 high): optional label above, icon, value in mono
 * and an "OK" tag that turns into "Typing …" while the system keyboard is open.
 * Selecting it opens the system keyboard; the text is never edited in place.
 */
@Composable
fun InputField(
    label: String?,
    icon: ImageVector,
    value: String,
    placeholder: String,
    editing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    focusRequester: FocusRequester? = null,
    /** Where Down goes; the design moves to the first button, not the nearest one. */
    down: FocusRequester? = null,
) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusSm)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (label != null) Text(label.uppercase(), style = GlacierText.body(16).copy(letterSpacing = 0.08.em), color = GlacierColors.Mist, modifier = Modifier.padding(start = 4.dp))
        GlacierClickable(
            onClick = onClick,
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(down?.let { target -> Modifier.focusProperties { this.down = target } } ?: Modifier),
            unfocusedBorder = GlacierColors.GlassBorder,
            scaleOnFocus = false,
        ) { focused ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(shape)
                    .background(if (editing) accent.copy(alpha = 0.1f) else GlacierColors.GlassFill)
                    .padding(start = 30.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val shown = if (masked) "•".repeat(value.length) else value
                    // The caret sits where typing continues: before the placeholder, after the text.
                    if (editing && shown.isEmpty()) Caret()
                    Text(
                        shown.ifEmpty { placeholder },
                        style = GlacierText.mono(28),
                        color = if (shown.isEmpty()) GlacierColors.Mist else GlacierColors.Ice,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (editing && shown.isNotEmpty()) Caret()
                }
                FieldTag(focused = focused, editing = editing)
            }
        }
    }
}

@Composable
private fun Caret() {
    val pulse = rememberInfiniteTransition(label = "caret")
    val alpha by pulse.animateFloat(0.5f, 1f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "caretAlpha")
    Box(Modifier.width(2.dp).height(34.dp).alpha(alpha).background(LocalAccent.current.main))
}

@Composable
private fun FieldTag(focused: Boolean, editing: Boolean) {
    val accent = LocalAccent.current.main
    val solid = focused && !editing
    val foreground = when {
        solid -> GlacierColors.Void
        focused -> GlacierColors.Ice
        else -> GlacierColors.Mist
    }
    Row(
        Modifier
            .height(52.dp)
            .clip(PillShape)
            .background(if (solid) accent else GlacierColors.GlassFill2)
            .border(1.dp, if (solid) accent else GlacierColors.GlassBorder2, PillShape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(GlacierIcons.Keyboard, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
        Text(
            stringResource(if (editing) R.string.field_typing else R.string.field_ok),
            style = GlacierText.body(17, FontWeight.SemiBold),
            color = foreground,
        )
    }
}

/** Hint under the buttons: how the system keyboard is opened, or that it is open. */
@Composable
fun KeyboardStatus(open: Boolean) {
    Row(
        Modifier.height(28.dp).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (open) {
            PulseDot()
            Text(stringResource(R.string.keyboard_open_hint), style = GlacierText.body(18), color = GlacierColors.Ice)
        } else {
            Text(stringResource(R.string.keyboard_closed_hint), style = GlacierText.body(18), color = GlacierColors.Mist)
        }
    }
}

/** The design's large setup buttons (72 high). */
@Composable
fun SetupButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
) = PillButton(text, onClick = onClick, modifier = modifier, primary = primary, height = 72, icon = icon)

/** "This server is older than 12.0 — connect anyway?" */
@Composable
fun UnsupportedServerDialog(server: ServerInfo, onResult: (connect: Boolean) -> Unit) {
    ModalSheet(onDismiss = { onResult(false) }, width = 640) {
        Text(stringResource(R.string.setup_unsupported_title), style = GlacierText.display(28), color = GlacierColors.Ice)
        Text(
            stringResource(
                R.string.setup_unsupported_body,
                server.name,
                server.version ?: stringResource(R.string.setup_unknown_version),
            ),
            style = GlacierText.body(19).copy(lineHeight = 28.sp),
            color = GlacierColors.Mist,
        )
        val cancelFocus = remember { FocusRequester() }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton(stringResource(R.string.action_cancel), onClick = { onResult(false) }, modifier = Modifier.focusRequester(cancelFocus))
            PillButton(stringResource(R.string.setup_connect_anyway), onClick = { onResult(true) }, primary = true)
        }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
    }
}
