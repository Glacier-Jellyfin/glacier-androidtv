package io.github.glacier_jellyfin.androidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.ignoreHeldOk
import io.github.glacier_jellyfin.androidtv.core.designsystem.startGradient

/** Width of the panel at the end edge (design px of the 1920 grid). */
private const val PANEL_WIDTH = 620

/** Slide and fade of the panel's entrance. */
private const val ENTER_MS = 200

private val RowShape = RoundedCornerShape(16.dp)

/** What a panel's rows share: the description of the focused row, shown in the fixed area below the list. */
@Stable
class OptionsPanelScope internal constructor() {
    internal var description by mutableStateOf("")
}

/**
 * The option menu as a panel at the end edge (the start edge in a right-to-left layout), over a page
 * that is only dimmed towards the panel. Title and subtitle on top, the rows [content] in the middle
 * (it scrolls when there are many), and at the bottom a fixed area that explains the focused row.
 * With [note] that area shows this line instead (for a step with an input rather than rows).
 * [first] takes the focus when the panel opens; holding OK may have opened it, so the held key is ignored.
 * Confirmations stay in [ModalSheet] so they force a decision.
 */
@Composable
fun OptionsPanel(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    first: FocusRequester? = null,
    note: String? = null,
    content: @Composable OptionsPanelScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val scope = remember { OptionsPanelScope() }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.fillMaxSize()) {
        // The page stays recognisable: the dimming only grows towards the panel.
        AnimatedVisibility(shown, enter = fadeIn(tween(ENTER_MS))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .startGradient(0f to Color(0x2605090F), 0.55f to Color(0x7005090F), 1f to Color(0xB005090F)),
            )
        }
        AnimatedVisibility(
            shown,
            modifier = Modifier.align(Alignment.CenterEnd),
            // Offsets are absolute: from the right edge, or from the left in a right-to-left layout.
            enter = slideInHorizontally(tween(ENTER_MS)) { if (rtl) -it else it } + fadeIn(tween(ENTER_MS)),
        ) {
            Row(Modifier.fillMaxHeight().width(PANEL_WIDTH.dp).background(GlacierColors.Deep)) {
                // The inner edge.
                Box(Modifier.fillMaxHeight().width(1.dp).background(GlacierColors.GlassBorder2))
                Column(
                    // Focus stays in the panel: the page behind it is still composed, and a card there
                    // can be the next one over from a row here. Holding OK opened it: the held key must not pick the first row.
                    modifier = Modifier
                        .ignoreHeldOk()
                        .focusProperties { onExit = { cancelFocusChange() } }
                        .focusGroup()
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 40.dp, end = 40.dp, top = 56.dp, bottom = 44.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = GlacierText.display(34), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        subtitle?.let { Text(it, style = GlacierText.body(19), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    // The rows scroll when there are many; the header and the description stay.
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { scope.content() }
                    // Room for three lines, so nothing jumps as the focus moves.
                    Text(
                        note ?: scope.description,
                        style = GlacierText.body(19),
                        color = GlacierColors.Mist,
                        minLines = 3,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    if (first != null) {
        LaunchedEffect(first) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

/**
 * One slim row of a panel: icon and label over the full width, the accent when focused. While it has
 * the focus, the panel's description area shows [description]. [badge] is a small count at the end.
 */
@Composable
fun OptionsPanelScope.OptionRow(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badge: String? = null,
) {
    val accent = LocalAccent.current.main
    var focused by remember { mutableStateOf(false) }
    // Also when the text changes under the focus (a toggled label).
    if (focused) SideEffect { this.description = description }
    GlacierClickable(
        onClick = onClick,
        shape = RowShape,
        scaleOnFocus = false,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus },
    ) { isFocused ->
        val foreground = if (isFocused) GlacierColors.Void else GlacierColors.Ice
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RowShape)
                .background(if (isFocused) accent else GlacierColors.GlassFill)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
            Text(
                label,
                style = GlacierText.body(21, FontWeight.SemiBold),
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                Box(
                    Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (isFocused) GlacierColors.Void.copy(alpha = 0.12f) else GlacierColors.GlassFill2)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(badge, style = GlacierText.body(16, FontWeight.SemiBold), color = foreground, maxLines = 1)
                }
            }
        }
    }
}
