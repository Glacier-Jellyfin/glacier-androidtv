package io.github.glacier_jellyfin.androidtv.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import java.text.DateFormat
import java.util.Date

/**
 * The log offered on the local network: where to get it, what it is called and how large it is.
 * [crashedAt] is set when the page opens by itself after a crash (epoch ms).
 */
data class LogShare(val url: String, val fileName: String, val sizeBytes: Int, val crashedAt: Long? = null)

/**
 * Downloading the log on another device, laid out like the update dialog:
 * the steps on the left, the QR code on a glass panel on the right, "Close"
 * at the bottom. The download works while this page is open. After a crash
 * it says so and asks for a report on GitHub.
 */
@Composable
fun LogShareScreen(share: LogShare, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val closeFocus = remember { FocusRequester() }
    GlacierBackground(
        Modifier
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(Modifier.fillMaxSize().padding(start = 130.dp, end = 130.dp, top = 90.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(48.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(100.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.settings_group_diagnostics).uppercase(),
                            style = GlacierText.label(18, 0.08),
                            color = LocalAccent.current.main,
                        )
                        Text(
                            stringResource(if (share.crashedAt != null) R.string.diag_crash_title else R.string.diag_share_title),
                            style = GlacierText.display(64),
                            color = GlacierColors.Ice,
                        )
                        share.crashedAt?.let {
                            Text(
                                stringResource(R.string.diag_crash_intro, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))),
                                style = GlacierText.body(21),
                                color = GlacierColors.Mist,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Step(1, stringResource(R.string.diag_share_step_network))
                        Step(2, stringResource(R.string.diag_share_step_scan))
                        Step(3, stringResource(R.string.diag_share_step_attach, ISSUES_URL))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Fact(stringResource(R.string.diag_share_file), share.fileName)
                        Fact(stringResource(R.string.diag_share_size), stringResource(R.string.diag_share_kb, share.sizeBytes / 1000.0))
                    }
                }
                QrPanel(share.url, Modifier.width(620.dp).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diag_share_hint), style = GlacierText.body(17), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
                PillButton(
                    stringResource(R.string.diag_share_close),
                    onClick = onClose,
                    primary = true,
                    height = 68,
                    width = 260,
                    modifier = Modifier.focusRequester(closeFocus),
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
}

@Composable
private fun QrPanel(url: String, modifier: Modifier) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    Column(
        modifier
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.diag_share_scan), style = GlacierText.display(30), color = GlacierColors.Ice)
        QrImage(url, Modifier.size(400.dp).clip(RoundedCornerShape(GlacierShapes.RadiusMd)))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diag_share_or_open), style = GlacierText.body(17), color = GlacierColors.Mist)
            Text(url, style = GlacierText.mono(18), color = GlacierColors.Ice, textAlign = TextAlign.Center)
        }
    }
}

/** A numbered step: the number in an accent ring, the text beside it. */
@Composable
private fun Step(number: Int, text: String) {
    val accent = LocalAccent.current.main
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = GlacierText.mono(20).copy(fontWeight = FontWeight.SemiBold), color = accent)
        }
        Text(text, style = GlacierText.body(21), color = GlacierColors.Ice, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(label, style = GlacierText.body(19), color = GlacierColors.Mist, modifier = Modifier.weight(1f))
        Text(value, style = GlacierText.mono(19), color = GlacierColors.Ice)
    }
}

private const val ISSUES_URL = "github.com/Glacier-Jellyfin/glacier-androidtv/issues"
