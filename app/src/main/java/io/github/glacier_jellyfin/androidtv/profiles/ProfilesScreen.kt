package io.github.glacier_jellyfin.androidtv.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.glacier_jellyfin.androidtv.BuildConfig
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierMark
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.PinDots
import io.github.glacier_jellyfin.androidtv.core.designsystem.PinPad
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.ModalSheet
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

@Composable
fun ProfilesScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(state.loading) {
        if (!state.loading) runCatching { firstFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(64.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                GlacierMark(size = 62)
                Text(
                    stringResource(R.string.app_name).uppercase(),
                    style = GlacierText.body(26, FontWeight.Bold).copy(letterSpacing = 0.34.em),
                    color = GlacierColors.Ice,
                )
                Text(stringResource(R.string.profiles_subtitle), style = GlacierText.body(19), color = GlacierColors.Mist)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(36.dp)) {
                Text(stringResource(R.string.profiles_title), style = GlacierText.display(38), color = GlacierColors.Ice)
                Row(horizontalArrangement = Arrangement.spacedBy(52.dp)) {
                    state.profiles.forEachIndexed { index, card ->
                        ProfileTile(
                            card = card,
                            onClick = { viewModel.select(card.profile) },
                            focusRequester = firstFocus.takeIf { index == 0 },
                        )
                    }
                }
                PillButton(
                    text = stringResource(R.string.profiles_other_user),
                    onClick = viewModel::otherUser,
                    icon = GlacierIcons.Plus,
                    height = 56,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .then(if (state.profiles.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier),
                )
            }
        }

        // Provisional: the design will get its own way to switch servers.
        state.server?.let { server ->
            GlacierClickable(
                onClick = viewModel::changeServer,
                shape = PillShape,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp),
                contentAlignment = Alignment.Center,
            ) { focused ->
                val line = if (state.offline) {
                    stringResource(R.string.profiles_offline)
                } else {
                    stringResource(R.string.profiles_server_line, server.address, server.version.orEmpty(), BuildConfig.VERSION_NAME)
                }
                Text(
                    line,
                    style = GlacierText.body(17),
                    color = if (focused) LocalAccent.current.main else GlacierColors.Mist,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }

    state.pinFor?.let { profile ->
        ModalSheet(onDismiss = viewModel::dismissPin) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pin_title, profile.name), style = GlacierText.display(28), color = GlacierColors.Ice)
                Text(
                    stringResource(if (state.pinWrong) R.string.pin_wrong else R.string.pin_hint),
                    style = GlacierText.body(17),
                    color = if (state.pinWrong) LocalAccent.current.main else GlacierColors.Mist,
                )
            }
            PinDots(filled = state.pin.length)
            val padFocus = remember { FocusRequester() }
            PinPad(onKey = viewModel::pinKey, firstKeyFocus = padFocus)
            LaunchedEffect(Unit) { runCatching { padFocus.requestFocus() } }
        }
    }
}

@Composable
private fun ProfileTile(card: ProfileCard, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.width(200.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Only the avatar is the focus target; the name below follows its focus.
        GlacierClickable(
            onClick = onClick,
            shape = shape,
            modifier = Modifier
                .onFocusChanged { focused = it.hasFocus }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        ) {
            Box(
                Modifier
                    .size(168.dp)
                    .clip(shape)
                    // No photo: the surface comes from the accent, not from an image.
                    .background(Brush.linearGradient(listOf(accent.deep, GlacierColors.Void))),
                contentAlignment = Alignment.Center,
            ) {
                // The initial stands in until the profile picture has loaded, or when there is none.
                var imageLoaded by remember(card.imageUrl) { mutableStateOf(false) }
                if (!imageLoaded) {
                    Text(
                        card.profile.name.take(1).uppercase(),
                        style = GlacierText.display(64).copy(shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 18f)),
                        color = GlacierColors.Ice,
                    )
                }
                if (card.imageUrl != null) {
                    AsyncImage(
                        model = card.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = { imageLoaded = true },
                        modifier = Modifier.size(168.dp),
                    )
                }
                if (card.profile.pinLocked) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(36.dp)
                            .clip(PillShape)
                            .background(GlacierColors.GlassFill2)
                            .border(1.dp, GlacierColors.GlassBorder2, PillShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(GlacierIcons.Lock, contentDescription = null, tint = GlacierColors.Ice, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Text(card.profile.name, style = GlacierText.body(21, FontWeight.SemiBold), color = if (focused) accent.main else GlacierColors.Ice, maxLines = 1)
    }
}
