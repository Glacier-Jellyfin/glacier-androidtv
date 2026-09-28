package io.github.glacier_jellyfin.androidtv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.AppearanceSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.UiLanguage
import io.github.glacier_jellyfin.androidtv.core.designsystem.Accent
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.navigation.GlacierNavHost
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.ui.CardSizes
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.LocalToaster
import io.github.glacier_jellyfin.androidtv.ui.ToastHost
import io.github.glacier_jellyfin.androidtv.ui.Toaster
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Picks the first screen: "Who's watching?" for the last server, otherwise setup. */
@HiltViewModel
class StartViewModel @Inject constructor(
    accounts: AccountRepository,
    settings: SettingsRepository,
) : ViewModel() {
    /** The signed-in profile's look; defaults on the setup and profile screens. */
    val appearance: StateFlow<AppearanceSettings> = settings.settings
        .map { it.appearance }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.appearance)

    /** The signed-in profile's interface language; null while no one is signed in, which keeps the last one. */
    val uiLanguage: Flow<UiLanguage?> = settings.active.map { it?.uiLanguage }

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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                startViewModel.uiLanguage.filterNotNull().collect { language ->
                    if (language.tag != UiLocale.current(this@MainActivity)) {
                        UiLocale.set(this@MainActivity, language.tag)
                        recreate()
                    }
                }
            }
        }
        setContent {
            val appearance by startViewModel.appearance.collectAsStateWithLifecycle()
            GlacierTheme(accent = Accent.valueOf(appearance.accent.name), reduceMotion = appearance.reduceMotion) {
                val toaster = remember { Toaster() }
                CompositionLocalProvider(
                    LocalToaster provides toaster,
                    LocalCardSizes provides if (appearance.compact) CardSizes.Compact else CardSizes.Comfortable,
                ) {
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
