package io.github.glacier_jellyfin.androidtv.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.ui.ModalSheet

/** Glass box behind an input field, as on the address and sign-in screens. */
@Composable
fun FieldBox(
    modifier: Modifier = Modifier,
    borderColor: androidx.compose.ui.graphics.Color = GlacierColors.GlassBorder,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusSm)
    Box(
        modifier
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, borderColor, shape),
    ) { content() }
}

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
