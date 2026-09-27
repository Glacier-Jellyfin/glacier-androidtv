package io.github.glacier_jellyfin.androidtv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierMark
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomePlaceholderViewModel @Inject constructor(
    private val sessions: SessionManager,
) : ViewModel() {
    val session = sessions.session

    fun switchProfile(then: (serverId: String) -> Unit) {
        val serverId = sessions.session.value?.server?.id ?: return
        sessions.leave()
        then(serverId)
    }

    fun signOut(then: (serverId: String) -> Unit) {
        val serverId = sessions.session.value?.server?.id ?: return
        viewModelScope.launch {
            sessions.signOut()
            then(serverId)
        }
    }
}

/**
 * Stand-in for the home screen, which is the next section to be built. It
 * exists so that sign-in, profile switching and sign-out can be tested.
 */
@Composable
fun HomePlaceholderScreen(
    onLeave: (serverId: String) -> Unit,
    viewModel: HomePlaceholderViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(36.dp)) {
            GlacierMark(size = 62)
            Text(
                stringResource(R.string.home_placeholder, session?.user?.name.orEmpty(), session?.server?.name.orEmpty()),
                style = GlacierText.display(38),
                color = GlacierColors.Ice,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(
                    stringResource(R.string.home_switch_profile),
                    onClick = { viewModel.switchProfile(onLeave) },
                    primary = true,
                    modifier = Modifier.focusRequester(focus),
                )
                PillButton(stringResource(R.string.home_sign_out), onClick = { viewModel.signOut(onLeave) })
            }
        }
    }
}
