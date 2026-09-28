package io.github.glacier_jellyfin.androidtv.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.BusyOverlay
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.SystemTextInput
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

@Composable
fun SignInScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)

    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val signInFocus = remember { FocusRequester() }
    var keyboardOpen by remember { mutableStateOf(false) }
    val activeFocus = if (state.field == SignInField.Username) userFocus else passwordFocus
    // Also follows the view model, e.g. back to the password after a wrong one.
    LaunchedEffect(state.field) { if (!keyboardOpen) activeFocus.requestFocus() }

    fun edit(field: SignInField) {
        viewModel.selectField(field)
        keyboardOpen = true
    }

    SetupLayout(
        intro = {
            SetupHeading(stringResource(R.string.signin_title), stringResource(R.string.signin_intro))
            state.server?.let { ServerBadge(it) }
            SetupStep(2, stringResource(R.string.setup_step_account))
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
            InputField(
                label = stringResource(R.string.signin_username),
                icon = GlacierIcons.User,
                value = state.username,
                placeholder = stringResource(R.string.signin_username_hint),
                editing = keyboardOpen && state.field == SignInField.Username,
                onClick = { edit(SignInField.Username) },
                focusRequester = userFocus,
            )
            InputField(
                label = stringResource(R.string.signin_password),
                icon = GlacierIcons.Password,
                value = state.password,
                placeholder = stringResource(R.string.signin_password_hint),
                editing = keyboardOpen && state.field == SignInField.Password,
                onClick = { edit(SignInField.Password) },
                masked = true,
                focusRequester = passwordFocus,
                down = signInFocus,
            )
        }
        Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SetupButton(stringResource(R.string.signin_title), onClick = viewModel::signIn, primary = true, modifier = Modifier.focusRequester(signInFocus))
            if (state.quickConnectAvailable) {
                SetupButton(stringResource(R.string.quick_connect), onClick = viewModel::openQuickConnect, icon = GlacierIcons.Bolt)
            }
            SetupButton(stringResource(R.string.signin_change_server), onClick = viewModel::changeServer)
        }
        Row(Modifier.padding(top = 26.dp)) { KeyboardStatus(keyboardOpen) }
    }

    // A new input per field, so confirming the user name reopens the keyboard for the password.
    key(state.field) {
        SystemTextInput(
            text = if (state.field == SignInField.Username) state.username else state.password,
            onTextChange = viewModel::setText,
            open = keyboardOpen,
            onClose = { done ->
                when {
                    // Design: OK on the user name continues with the password …
                    done && state.field == SignInField.Username -> viewModel.selectField(SignInField.Password)
                    // … and OK on the password signs in.
                    done -> {
                        keyboardOpen = false
                        passwordFocus.requestFocus()
                        viewModel.signIn()
                    }
                    else -> {
                        keyboardOpen = false
                        activeFocus.requestFocus()
                    }
                }
            },
            password = state.field == SignInField.Password,
        )
    }

    if (state.busy) BusyOverlay(stringResource(R.string.setup_connecting, state.server?.name.orEmpty()))
}

/** The server this account belongs to, in the intro column. */
@Composable
private fun ServerBadge(server: ServerInfo) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(GlacierShapes.RadiusSm))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GlacierIcons.Server, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(server.name, style = GlacierText.display(21), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(server.address, style = GlacierText.mono(16), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
