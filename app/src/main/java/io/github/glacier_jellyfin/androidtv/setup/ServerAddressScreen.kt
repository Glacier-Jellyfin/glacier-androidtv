package io.github.glacier_jellyfin.androidtv.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.BusyOverlay
import io.github.glacier_jellyfin.androidtv.core.designsystem.FieldDisplay
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlassCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.OnScreenKeyboard
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SystemTextInput
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

@Composable
fun ServerAddressScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: ServerAddressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)

    val keyboardFocus = remember { FocusRequester() }
    var systemKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { keyboardFocus.requestFocus() }

    Row(
        modifier = Modifier.fillMaxSize().padding(start = 130.dp, end = 130.dp, top = 120.dp, bottom = 70.dp),
        horizontalArrangement = Arrangement.spacedBy(70.dp),
    ) {
        Column(Modifier.width(860.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Text(stringResource(R.string.address_title), style = GlacierText.display(44), color = GlacierColors.Ice)
            FieldBox(Modifier.fillMaxWidth()) {
                FieldDisplay(text = state.address, placeholder = "https://", active = true)
            }
            OnScreenKeyboard(
                text = state.address,
                onTextChange = viewModel::setAddress,
                onSystemKeyboard = { systemKeyboard = true },
                spaceLabel = stringResource(R.string.keyboard_space),
                firstKeyFocus = keyboardFocus,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(stringResource(R.string.address_connect), onClick = viewModel::connect, primary = true)
                PillButton(stringResource(R.string.address_clear), onClick = { viewModel.setAddress("") })
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.address_examples), style = GlacierText.body(22, FontWeight.SemiBold), color = GlacierColors.Ice)
                Text(
                    "https://jellyfin.example.com\nhttp://192.168.1.10:8096\njellyfin.home.lan",
                    style = GlacierText.mono(18).copy(lineHeight = 31.sp),
                    color = GlacierColors.Mist,
                )
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.address_note), style = GlacierText.body(22, FontWeight.SemiBold), color = GlacierColors.Ice)
                Text(
                    stringResource(R.string.address_note_body),
                    style = GlacierText.body(19).copy(lineHeight = 29.sp),
                    color = GlacierColors.Mist,
                )
            }
        }
    }

    SystemTextInput(
        text = state.address,
        onTextChange = viewModel::setAddress,
        open = systemKeyboard,
        onClose = {
            systemKeyboard = false
            keyboardFocus.requestFocus()
        },
        keyboardType = KeyboardType.Uri,
    )

    state.connecting?.let { BusyOverlay(stringResource(R.string.setup_connecting, it)) }
    state.confirmUnsupported?.let { server -> UnsupportedServerDialog(server, viewModel::confirmUnsupported) }
}
