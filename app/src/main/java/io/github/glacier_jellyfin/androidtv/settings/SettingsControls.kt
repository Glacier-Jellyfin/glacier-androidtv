package io.github.glacier_jellyfin.androidtv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.settings.Language
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleEdge
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitlePosition
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyle
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyleMode
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.player.subtitleTypeface
import io.github.glacier_jellyfin.androidtv.player.usesNative
import io.github.glacier_jellyfin.androidtv.ui.Artwork

/** A category on the left (design `pill()`): accent when focused, tinted when it is the open one. */
@Composable
internal fun CategoryPill(label: String, active: Boolean, onFocused: () -> Unit, modifier: Modifier = Modifier, dot: Boolean = false) {
    val accent = LocalAccent.current.main
    GlacierClickable(
        onClick = onFocused,
        shape = PillShape,
        unfocusedBorder = if (active) accent.copy(alpha = 0.45f) else Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) { focused ->
        if (focused) LaunchedEffect(Unit) { onFocused() }
        val color = when {
            focused -> GlacierColors.Void
            active -> GlacierColors.Ice
            else -> GlacierColors.Mist
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .clip(PillShape)
                .background(
                    when {
                        focused -> accent
                        active -> accent.copy(alpha = 0.18f)
                        else -> Color.Transparent
                    },
                )
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = GlacierText.body(21, FontWeight.SemiBold), color = color, modifier = Modifier.weight(1f))
            // Something waits there, e.g. an app update.
            if (dot) Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
    }
}

/** A group heading with the line running to the right edge. */
@Composable
internal fun GroupHeading(title: String, first: Boolean) {
    Row(
        Modifier.padding(start = 6.dp, end = 6.dp, top = if (first) 0.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(title, style = GlacierText.display(26), color = GlacierColors.Ice)
        Box(Modifier.weight(1f).height(1.dp).background(GlacierColors.GlassBorder))
    }
}

/**
 * One setting as a glass card: label and explanation, the control at the end
 * ([trailing]) or below them ([below], the choice pills).
 */
@Composable
internal fun OptionCard(
    label: String,
    sub: String,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Column(
        Modifier
            .fillMaxWidth()
            // Up/Down move card by card, also between a switch on the right and choices on the left.
            .focusGroup()
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(horizontal = 30.dp, vertical = 26.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = GlacierText.body(22, FontWeight.SemiBold), color = GlacierColors.Ice)
                Text(sub, style = GlacierText.body(17), color = GlacierColors.Mist)
            }
            trailing?.invoke()
        }
        below?.invoke()
    }
}

/** The on/off switch: accent track when on, the knob slides across. */
@Composable
internal fun ToggleSwitch(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val accent = LocalAccent.current.main
    val knob by animateDpAsState(if (checked) 42.dp else 4.dp, tween(200), label = "knob")
    GlacierClickable(onClick = onToggle, modifier = modifier, shape = PillShape, enabled = enabled, unfocusedBorder = GlacierColors.GlassBorder2) {
        Box(
            Modifier
                .width(86.dp)
                .height(48.dp)
                .clip(PillShape)
                .background(if (checked) accent else GlacierColors.GlassFill2),
        ) {
            Box(
                Modifier
                    .offset { IntOffset((knob + 2.dp).roundToPx(), 6.dp.roundToPx()) }
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (checked) GlacierColors.Void else GlacierColors.Ice),
            )
        }
    }
}

/** The options of a choice, wrapping onto more lines when needed; [check] ticks the chosen ones (several may be). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoicePills(
    options: List<String>,
    selected: (Int) -> Boolean,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
    check: Boolean = false,
    /** A colour dot before each label, for colour choices. */
    dots: List<Color>? = null,
    /** Each label in its own font, for font choices; their pills are narrower so all fonts fit one line. */
    fonts: List<FontFamily>? = null,
) {
    val accent = LocalAccent.current.main
    val side = if (fonts != null) 20.dp else 24.dp
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEachIndexed { index, label ->
            val active = selected(index)
            GlacierClickable(
                onClick = { onSelect(index) },
                shape = PillShape,
                enabled = enabled,
                unfocusedBorder = if (active) accent.copy(alpha = 0.45f) else Color.Transparent,
            ) { focused ->
                val color = when {
                    focused -> GlacierColors.Void
                    active -> GlacierColors.Ice
                    else -> GlacierColors.Mist
                }
                Row(
                    Modifier
                        .height(52.dp)
                        .clip(PillShape)
                        .background(
                            when {
                                focused -> accent
                                active -> accent.copy(alpha = 0.18f)
                                else -> GlacierColors.GlassFill
                            },
                        )
                        .padding(start = if (check && active) 18.dp else side, end = side),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (check && active) Icon(GlacierIcons.Check, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    dots?.getOrNull(index)?.let { dot ->
                        Box(Modifier.size(20.dp).clip(CircleShape).background(dot).border(1.dp, Color(0x66000000), CircleShape))
                    }
                    val font = fonts?.getOrNull(index)
                    Text(
                        label,
                        style = if (font == null) GlacierText.body(18, FontWeight.SemiBold) else GlacierText.body(18).copy(fontFamily = font),
                        color = color,
                    )
                }
            }
        }
    }
}

/** One accent to pick: its name and colour. */
internal data class Swatch(val label: String, val color: Color)

/** The accent swatches (design: 150 wide, gradient chip, name and hex below; the active one in its colour). */
@Composable
internal fun SwatchPicker(swatches: List<Swatch>, selected: Int, onSelect: (Int) -> Unit) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        swatches.forEachIndexed { index, swatch ->
            val active = index == selected
            GlacierCard(onClick = { onSelect(index) }, modifier = Modifier.width(150.dp)) { focused ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .focusFrame(focused, shape, unfocusedBorder = if (active) swatch.color else GlacierColors.GlassBorder2)
                        .clip(shape)
                        .background(Brush.linearGradient(listOf(swatch.color, swatch.color.copy(alpha = 0.53f)))),
                )
                Text(
                    swatch.label,
                    style = GlacierText.body(16, FontWeight.SemiBold),
                    color = if (active) accent else GlacierColors.Ice,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text("#%06x".format(swatch.color.toArgb() and 0xFFFFFF), style = GlacierText.mono(14), color = GlacierColors.Mist, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** The current value at the end of its card, opening a list to pick another (language). */
@Composable
internal fun ValueButton(
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = LocalAccent.current.main
    GlacierClickable(onClick = onClick, shape = PillShape, enabled = enabled, modifier = modifier) { focused ->
        Row(
            Modifier
                .height(52.dp)
                .clip(PillShape)
                .background(if (focused) accent else GlacierColors.GlassFill)
                .padding(start = 24.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val color = if (focused) GlacierColors.Void else GlacierColors.Ice
            Text(value, style = GlacierText.body(19, FontWeight.SemiBold), color = color)
            Icon(GlacierIcons.ChevronRight, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * A read-only fact as a card: the value is plain text, never a pill, so it cannot be mistaken
 * for a button. The whole card takes focus (a lit border only, no ring or scale), so the list
 * can scroll down to it.
 */
@Composable
internal fun InfoCard(label: String, sub: String, value: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Row(
        Modifier
            .fillMaxWidth()
            .focusable(interactionSource = interaction)
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(if (focused) 2.dp else 1.dp, if (focused) LocalAccent.current.main.copy(alpha = 0.6f) else GlacierColors.GlassBorder, shape)
            .padding(horizontal = 30.dp, vertical = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = GlacierText.body(22, FontWeight.SemiBold), color = GlacierColors.Ice)
            Text(sub, style = GlacierText.body(17), color = GlacierColors.Mist)
        }
        Text(value, style = GlacierText.mono(19), color = GlacierColors.Mist, textAlign = TextAlign.End)
    }
}

/** Below the update card: the download progress, or the notes of the version on offer. */
@Composable
internal fun UpdateDetails(progress: Float?, progressStart: String, progressEnd: String, notesTitle: String, notes: List<String>) {
    val accent = LocalAccent.current.main
    if (progress != null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(PillShape).background(accent))
            }
            Row(Modifier.fillMaxWidth()) {
                Text(progressStart, style = GlacierText.mono(16), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
                Text(progressEnd, style = GlacierText.mono(16), color = GlacierColors.Mist)
            }
        }
    }
    if (notes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
            Text(notesTitle.uppercase(), style = GlacierText.label(15, 0.04), color = GlacierColors.Mist, modifier = Modifier.padding(top = 8.dp))
            notes.forEach { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.padding(top = 11.dp).size(6.dp).clip(CircleShape).background(accent))
                    Text(note, style = GlacierText.body(18), color = GlacierColors.Ice)
                }
            }
        }
    }
}

/** One entry of the language list: a language, or the "no preference" entry on top. */
internal data class LanguageEntry(val code: String?, val name: String, val hint: String)

/** The language list (design: 620 × 820 sheet, the chosen language ticked); shorter lists get a shorter sheet. */
@Composable
internal fun LanguagePicker(
    title: String,
    top: LanguageEntry,
    languages: List<Language>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val entries = remember(top, languages) { listOf(top) + languages.map { LanguageEntry(it.code, it.name, it.code) } }
    val selectedIndex = entries.indexOfFirst { it.code == selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 5).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xA805090F)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
        Column(
            Modifier
                .width(620.dp)
                .heightIn(max = 820.dp)
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
                Text(
                    pluralStringResource(R.plurals.settings_lang_count, languages.size, languages.size),
                    style = GlacierText.body(17),
                    color = GlacierColors.Mist,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(entries, key = { _, entry -> entry.code ?: "" }) { index, entry ->
                    LanguageRow(
                        entry = entry,
                        selected = index == selectedIndex,
                        onClick = { onPick(entry.code) },
                        modifier = if (index == selectedIndex) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
    LaunchedEffect(entries.size) {
        listState.scrollToItem((selectedIndex - 5).coerceAtLeast(0))
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun LanguageRow(entry: LanguageEntry, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val foreground = when {
        focused -> GlacierColors.Void
        selected -> accent
        else -> GlacierColors.Ice
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(shape)
            .background(if (focused) accent else Color.Transparent)
            .border(2.dp, if (focused) accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(entry.name, style = GlacierText.body(19, FontWeight.SemiBold), color = foreground, modifier = Modifier.weight(1f))
        Text(entry.hint, style = GlacierText.mono(15), color = if (focused) GlacierColors.Void else GlacierColors.Mist)
        Box(Modifier.size(22.dp)) {
            if (selected) Icon(GlacierIcons.Check, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The subtitle preview above the subtitle settings: two lines over a
 * backdrop, drawn at the size they would have on a picture this wide.
 */
@Composable
internal fun SubtitlePreview(style: SubtitleStyle, image: String?) {
    val context = LocalContext.current
    val native = style.usesNative(context)
    val typeface = remember(style.font, style.weight) { FontFamily(subtitleTypeface(context, style.font, style.weight)) }
    val tag = when {
        style.mode == SubtitleStyleMode.Auto && native -> R.string.settings_preview_auto_system
        style.mode == SubtitleStyleMode.Auto -> R.string.settings_preview_auto_custom
        native -> R.string.settings_preview_system
        else -> R.string.settings_preview_custom
    }
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(shape)
            .border(1.dp, GlacierColors.GlassBorder2, shape),
    ) {
        Artwork(image, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x2605090F), Color(0x5905090F)))))
        Box(
            Modifier
                .padding(16.dp)
                .height(32.dp)
                .clip(PillShape)
                .background(GlacierColors.GlassFill2)
                .border(1.dp, GlacierColors.GlassBorder2, PillShape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.settings_preview, stringResource(tag)), style = GlacierText.body(14, FontWeight.SemiBold), color = GlacierColors.Ice)
        }

        // As tall as on a 16:9 picture of this width: the size setting is a share of the picture height.
        val pictureHeight = maxWidth * 9f / 16f
        val fontSize = with(LocalDensity.current) { (pictureHeight * if (native) NATIVE_PREVIEW_SIZE else style.size.fraction).toSp() }
        val lineHeight = pictureHeight * (if (native) NATIVE_PREVIEW_SIZE else style.size.fraction) * 1.3f
        val margin = 18.dp
        val bottomLines = when (style.position) {
            SubtitlePosition.Top1 -> null
            else -> -style.position.line - 1
        }
        val text = TextStyle(
            fontFamily = if (native) FontFamily.SansSerif else typeface,
            fontSize = fontSize,
            lineHeight = fontSize * 1.3f,
            textAlign = TextAlign.Center,
            color = if (native) Color.White else Color(style.color.argb),
            background = if (native) Color.Unspecified else Color(style.background.argb),
            shadow = if (native) Shadow(Color(0xCC000000), Offset(0f, 1f), 2f) else previewShadow(style.edge),
        )
        Column(
            Modifier
                .align(if (bottomLines == null) Alignment.TopCenter else Alignment.BottomCenter)
                .padding(top = if (bottomLines == null) margin else 0.dp, bottom = if (bottomLines != null) margin + lineHeight * bottomLines else 0.dp)
                .then(if (native) Modifier.background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            listOf(R.string.settings_preview_line1, R.string.settings_preview_line2).forEach { line ->
                OutlinedText(stringResource(line), text, outline = !native && style.edge == SubtitleEdge.Outline)
            }
        }
    }
}

/**
 * Compose text has one shadow; an outline is the text drawn as a black stroke
 * underneath. That lower copy carries the background, so it stays behind the stroke.
 */
@Composable
private fun OutlinedText(text: String, style: TextStyle, outline: Boolean) {
    Box {
        if (outline) Text(text, style = style.copy(color = Color.Black, shadow = null, drawStyle = Stroke(width = 4f)))
        Text(text, style = if (outline) style.copy(background = Color.Unspecified) else style)
    }
}

private fun previewShadow(edge: SubtitleEdge): Shadow? = when (edge) {
    SubtitleEdge.None, SubtitleEdge.Outline -> null
    SubtitleEdge.Raised -> Shadow(Color(0xF2000000), Offset(0f, 2f), 4f)
    SubtitleEdge.Depressed -> Shadow(Color(0xF2000000), Offset(0f, -1f), 1f)
    SubtitleEdge.Shadow -> Shadow(Color(0xF2000000), Offset.Zero, 10f)
}

/** Media3's default text size (5.33 % of the height), what the system style roughly looks like. */
private const val NATIVE_PREVIEW_SIZE = 0.0533f
