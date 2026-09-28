package io.github.glacier_jellyfin.androidtv.setup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

@Composable
fun QuickConnectScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    onBack: () -> Unit,
    viewModel: QuickConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    LaunchedEffect(state.closed) { if (state.closed) onBack() }

    val newCodeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { newCodeFocus.requestFocus() }

    SetupLayout(
        intro = {
            SetupHeading(stringResource(R.string.quick_connect), stringResource(R.string.quick_connect_intro))
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                StepLine(1, buildAnnotatedString { append(stringResource(R.string.quick_connect_step1)) })
                val step2 = stringResource(R.string.quick_connect_step2)
                StepLine(
                    2,
                    buildAnnotatedString {
                        // "Profile → Quick Connect": the menu entry stands out.
                        append(step2.substringBefore("%1\$s"))
                        withStyle(SpanStyle(color = GlacierColors.Ice, fontWeight = FontWeight.SemiBold)) {
                            append(stringResource(R.string.quick_connect))
                        }
                        append(step2.substringAfter("%1\$s"))
                    },
                )
                StepLine(3, buildAnnotatedString { append(stringResource(R.string.quick_connect_step3)) })
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Text(
                stringResource(R.string.quick_connect_code_for, state.server?.name.orEmpty()).uppercase(),
                style = GlacierText.body(16).copy(letterSpacing = 0.08.em),
                color = GlacierColors.Mist,
                modifier = Modifier.padding(start = 4.dp),
            )
            CodeDigits(state.code)
            Validity(state.secondsLeft)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SetupButton(
                    stringResource(R.string.quick_connect_new_code),
                    onClick = viewModel::newCode,
                    primary = true,
                    modifier = Modifier.focusRequester(newCodeFocus),
                )
                SetupButton(stringResource(R.string.quick_connect_use_password), onClick = onBack)
            }
        }
    }
}

@Composable
private fun StepLine(number: Int, text: androidx.compose.ui.text.AnnotatedString) {
    val accent = LocalAccent.current.main
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f))
                .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = GlacierText.body(18, FontWeight.SemiBold), color = GlacierColors.Ice)
        }
        Text(
            text,
            style = GlacierText.body(21).copy(lineHeight = 31.sp),
            color = GlacierColors.Mist,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Six boxes of 108×136; dashes until the server has sent a code. */
@Composable
private fun CodeDigits(code: String?) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        (code ?: "------").padEnd(CODE_LENGTH).take(CODE_LENGTH).forEach { digit ->
            Box(
                Modifier
                    .size(width = 108.dp, height = 136.dp)
                    .clip(shape)
                    .background(GlacierColors.GlassFill2)
                    .border(1.dp, GlacierColors.GlassBorder2, shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    digit.toString(),
                    style = GlacierText.mono(68).copy(fontWeight = FontWeight.SemiBold),
                    color = if (code == null) GlacierColors.Mist else GlacierColors.Ice,
                )
            }
        }
    }
}

/** Remaining validity: a draining bar, "Waiting for approval …" and m:ss. */
@Composable
private fun Validity(secondsLeft: Int) {
    val accent = LocalAccent.current.main
    val fraction by animateFloatAsState(
        targetValue = secondsLeft / QUICK_CONNECT_TTL_SECONDS.toFloat(),
        animationSpec = tween(1_000, easing = LinearEasing),
        label = "validity",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(PillShape)
                .background(GlacierColors.GlassFill2),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(PillShape).background(accent))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PulseDot()
            Text(stringResource(R.string.quick_connect_waiting), style = GlacierText.body(18), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.quick_connect_valid, "%d:%02d".format(secondsLeft / 60, secondsLeft % 60)),
                style = GlacierText.mono(18),
                color = GlacierColors.Mist,
            )
        }
    }
}

private const val CODE_LENGTH = 6
