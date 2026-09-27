package io.github.glacier_jellyfin.androidtv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.ui.ComingSoonScreen
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsPlaceholderViewModel @Inject constructor(
    private val sessions: SessionManager,
) : ViewModel() {

    /** Ends the session on the server as well; see [SessionManager.signOut]. */
    fun signOut(then: (serverId: String) -> Unit) {
        val serverId = sessions.session.value?.server?.id ?: return
        viewModelScope.launch {
            sessions.signOut()
            then(serverId)
        }
    }
}

/**
 * Settings are built in a later step. Sign-out lives here already, where the
 * design puts it (Settings › Account), so it stays reachable for testing.
 */
@Composable
fun SettingsPlaceholderScreen(
    onBack: () -> Unit,
    onSignedOut: (serverId: String) -> Unit,
    viewModel: SettingsPlaceholderViewModel = hiltViewModel(),
) {
    ComingSoonScreen(title = stringResource(R.string.nav_settings), onBack = onBack) {
        PillButton(stringResource(R.string.home_sign_out), onClick = { viewModel.signOut(onSignedOut) })
    }
}
