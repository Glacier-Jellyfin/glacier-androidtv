package io.github.glacier_jellyfin.androidtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.navigation.GlacierNavHost
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.ui.LocalToaster
import io.github.glacier_jellyfin.androidtv.ui.ToastHost
import io.github.glacier_jellyfin.androidtv.ui.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Picks the first screen: "Who's watching?" for the last server, otherwise setup. */
@HiltViewModel
class StartViewModel @Inject constructor(accounts: AccountRepository) : ViewModel() {
    private val _start = MutableStateFlow<Any?>(null)
    val start = _start.asStateFlow()

    init {
        viewModelScope.launch {
            val state = accounts.current()
            val server = state.lastServerId?.takeIf { id -> state.servers.any { it.id == id } }
            _start.value = server?.let(::ProfilesRoute) ?: ServerListRoute
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val startViewModel: StartViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlacierTheme {
                val toaster = remember { Toaster() }
                CompositionLocalProvider(LocalToaster provides toaster) {
                    GlacierBackground {
                        val start by startViewModel.start.collectAsStateWithLifecycle()
                        start?.let { GlacierNavHost(rememberNavController(), startDestination = it) }
                        ToastHost(toaster)
                    }
                }
            }
        }
    }
}
