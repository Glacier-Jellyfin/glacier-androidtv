package io.github.glacier_jellyfin.androidtv.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.BusyOverlay
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.SystemTextInput
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

@Composable
fun ServerAddressScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    onBack: () -> Unit,
    viewModel: ServerAddressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)

    val fieldFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    var keyboardOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }

    SetupLayout(
        intro = {
            SetupHeading(stringResource(R.string.address_heading), stringResource(R.string.address_intro))
            SetupStep(1, stringResource(R.string.setup_step_server))
        },
    ) {
        InputField(
            label = stringResource(R.string.address_title),
            icon = GlacierIcons.Globe,
            value = state.address,
            placeholder = "https://",
            editing = keyboardOpen,
            onClick = { keyboardOpen = true },
            focusRequester = fieldFocus,
            down = connectFocus,
        )
        AddressHint(Modifier.padding(start = 4.dp, top = 22.dp))
        Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SetupButton(stringResource(R.string.address_connect), onClick = viewModel::connect, primary = true, modifier = Modifier.focusRequester(connectFocus))
            SetupButton(stringResource(R.string.action_back), onClick = onBack)
        }
        Row(Modifier.padding(top = 22.dp)) { KeyboardStatus(keyboardOpen) }
    }

    SystemTextInput(
        text = state.address,
        onTextChange = viewModel::setAddress,
        open = keyboardOpen,
        onClose = { done ->
            keyboardOpen = false
            // Design: confirming moves on to "Connect"; dismissing returns to the field.
            (if (done) connectFocus else fieldFocus).requestFocus()
        },
        keyboardType = KeyboardType.Uri,
    )

    state.connecting?.let { BusyOverlay(stringResource(R.string.setup_connecting, it)) }
    state.confirmUnsupported?.let { server -> UnsupportedServerDialog(server, viewModel::confirmUnsupported) }
}

/** Port rules, with the literal parts in mono as in the design. */
@Composable
private fun AddressHint(modifier: Modifier) {
    val mono = SpanStyle(fontFamily = GlacierText.mono(18).fontFamily, color = GlacierColors.Ice)
    val template = stringResource(R.string.address_hint)
    // Placeholders: %1$s = 8096, %2$s = :Port
    val parts = template.split("%1\$s", "%2\$s")
    val literals = listOf("8096", ":" + stringResource(R.string.address_port))
    val text = buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            append(part)
            if (i < literals.size && i < parts.lastIndex) withStyle(mono) { append(literals[i]) }
        }
    }
    Text(text, style = GlacierText.body(18).copy(lineHeight = 27.sp), color = GlacierColors.Mist, modifier = modifier)
}
