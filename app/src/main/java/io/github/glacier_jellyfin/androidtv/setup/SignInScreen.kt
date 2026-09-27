package io.github.glacier_jellyfin.androidtv.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.BusyOverlay
import io.github.glacier_jellyfin.androidtv.core.designsystem.FieldDisplay
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlassCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.OnScreenKeyboard
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SystemTextInput
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

@Composable
fun SignInScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)

    val keyboardFocus = remember { FocusRequester() }
    var systemKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { keyboardFocus.requestFocus() }
    val accent = LocalAccent.current.main
    val activeText = if (state.field == SignInField.Username) state.username else state.password

    Row(
        modifier = Modifier.fillMaxSize().padding(start = 130.dp, end = 130.dp, top = 120.dp, bottom = 70.dp),
        horizontalArrangement = Arrangement.spacedBy(70.dp),
    ) {
        Column(Modifier.width(860.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(stringResource(R.string.signin_title), style = GlacierText.display(44), color = GlacierColors.Ice)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                SignInField.entries.forEach { field ->
                    val active = state.field == field
                    GlacierClickable(
                        onClick = { viewModel.selectField(field) },
                        shape = RoundedCornerShape(GlacierShapes.RadiusSm),
                        modifier = Modifier.weight(1f),
                        unfocusedBorder = if (active) accent.copy(alpha = 0.5f) else GlacierColors.GlassBorder,
                    ) {
                        FieldBox(Modifier.fillMaxWidth(), borderColor = androidx.compose.ui.graphics.Color.Transparent) {
                            val username = field == SignInField.Username
                            FieldDisplay(
                                text = if (username) state.username else state.password,
                                placeholder = stringResource(if (username) R.string.signin_username_hint else R.string.signin_password_hint),
                                active = active,
                                label = stringResource(if (username) R.string.signin_username else R.string.signin_password),
                                height = 98,
                                fontSize = 25,
                                masked = !username,
                            )
                        }
                    }
                }
            }
            OnScreenKeyboard(
                text = activeText,
                onTextChange = viewModel::setText,
                onSystemKeyboard = { systemKeyboard = true },
                spaceLabel = stringResource(R.string.keyboard_space),
                firstKeyFocus = keyboardFocus,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(stringResource(R.string.signin_title), onClick = viewModel::signIn, primary = true)
                PillButton(stringResource(R.string.address_clear), onClick = viewModel::clearField)
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.signin_connected_to).uppercase(), style = GlacierText.label(16), color = GlacierColors.Mist)
                Text(state.server?.name.orEmpty(), style = GlacierText.display(26), color = GlacierColors.Ice)
                Text(state.server?.address.orEmpty(), style = GlacierText.mono(18), color = GlacierColors.Mist)
            }
            val code = state.quickConnectCode
            GlassCard(Modifier.fillMaxWidth()) {
                if (code != null) {
                    // Provisional: the design will add its own Quick Connect card.
                    Text(stringResource(R.string.quick_connect), style = GlacierText.body(22, FontWeight.SemiBold), color = GlacierColors.Ice)
                    Text(code.chunked(3).joinToString(" "), style = GlacierText.mono(44).copy(letterSpacing = 0.08.em), color = accent)
                    Text(stringResource(R.string.quick_connect_hint), style = GlacierText.body(19).copy(lineHeight = 29.sp), color = GlacierColors.Mist)
                } else {
                    Text(stringResource(R.string.setup_step, 2, 2), style = GlacierText.body(22, FontWeight.SemiBold), color = GlacierColors.Ice)
                    Text(stringResource(R.string.signin_stays_signed_in), style = GlacierText.body(19).copy(lineHeight = 29.sp), color = GlacierColors.Mist)
                }
            }
        }
    }

    SystemTextInput(
        text = activeText,
        onTextChange = viewModel::setText,
        open = systemKeyboard,
        onClose = {
            systemKeyboard = false
            keyboardFocus.requestFocus()
        },
        password = state.field == SignInField.Password,
    )

    if (state.busy) BusyOverlay(stringResource(R.string.setup_connecting, state.server?.name.orEmpty()))
}
