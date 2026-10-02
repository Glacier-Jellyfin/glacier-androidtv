package io.github.glacier_jellyfin.androidtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunch
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunches
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.startServerId
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.settings.AppearanceSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.UiLanguage
import io.github.glacier_jellyfin.androidtv.core.designsystem.Accent
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateManager
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateState
import io.github.glacier_jellyfin.androidtv.core.updater.pending
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.music.NowPlaying
import io.github.glacier_jellyfin.androidtv.navigation.GlacierNavHost
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.ui.CardSizes
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.ui.LocalMusicProgress
import io.github.glacier_jellyfin.androidtv.ui.LocalNowPlaying
import io.github.glacier_jellyfin.androidtv.ui.LocalToaster
import io.github.glacier_jellyfin.androidtv.ui.LocalUnlockedTitles
import io.github.glacier_jellyfin.androidtv.ui.LocalUpdatePending
import io.github.glacier_jellyfin.androidtv.ui.NavDirection
import io.github.glacier_jellyfin.androidtv.ui.NowPlayingSaver
import io.github.glacier_jellyfin.androidtv.ui.SAVER_IDLE_MS
import io.github.glacier_jellyfin.androidtv.ui.ToastHost
import io.github.glacier_jellyfin.androidtv.ui.Toaster
import io.github.glacier_jellyfin.androidtv.update.InstallingOverlay
import io.github.glacier_jellyfin.androidtv.update.text
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks the first screen: "Who's watching?" (or the start profile) for the start server, otherwise setup. */
@HiltViewModel
class StartViewModel @Inject constructor(
    accounts: AccountRepository,
    settings: SettingsRepository,
    parental: ParentalControl,
    val updates: UpdateManager,
    val music: MusicController,
    home: HomeRepository,
    private val sessions: SessionManager,
    private val launches: HomeLaunches,
) : ViewModel() {
    /** Library kinds of the server, for every navigation bar. */
    val libraryKinds: StateFlow<List<LibraryKind>> = home.kinds

    /** The signed-in profile's look; defaults on the setup and profile screens. */
    val appearance: StateFlow<AppearanceSettings> = settings.settings
        .map { it.appearance }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.appearance)

    /** The song for the mini player. */
    val nowPlaying: StateFlow<NowPlaying?> = music.nowPlaying

    /** Titles the PIN unlocked in this session; their cards drop the lock. */
    val unlockedTitles: StateFlow<Set<String>> = parental.unlockedItems

    /** The signed-in profile's interface language; null while no one is signed in, which keeps the last one. */
    val uiLanguage: Flow<UiLanguage?> = settings.active.map { it?.uiLanguage }

    private val _start = MutableStateFlow<Any?>(null)
    val start = _start.asStateFlow()

    private val _relaunch = Channel<Any>(Channel.CONFLATED)

    /** Screens to open from scratch while the app runs: a title was picked on the home screen. */
    val relaunch: Flow<Any> = _relaunch.receiveAsFlow()

    init {
        updates.start()
        viewModelScope.launch {
            val server = accounts.current().startServerId()
            _start.value = launches.pending?.let { ProfilesRoute(it.serverId, appStart = true, userId = it.userId) }
                ?: server?.let { ProfilesRoute(it, appStart = true) }
                ?: ServerListRoute
        }
    }

    /** A title picked on the home screen while the app runs: Home opens it, through the profile picker for another profile. */
    fun open(launch: HomeLaunch) {
        launches.set(launch)
        val session = sessions.session.value
        val same = session != null && session.server.id == launch.serverId && session.user.userId == launch.userId
        _relaunch.trySend(if (same) HomeRoute else ProfilesRoute(launch.serverId, appStart = true, userId = launch.userId))
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val startViewModel: StartViewModel by viewModels()

    @Inject
    lateinit var launches: HomeLaunches

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the start screen is chosen; a recreated activity has already handled it.
        if (savedInstanceState == null) HomeLaunch.from(intent)?.let(launches::set)
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
            val unlockedTitles by startViewModel.unlockedTitles.collectAsStateWithLifecycle()
            val nowPlaying by startViewModel.nowPlaying.collectAsStateWithLifecycle()
            val libraryKinds by startViewModel.libraryKinds.collectAsStateWithLifecycle()
            val update by startViewModel.updates.state.collectAsStateWithLifecycle()
            GlacierTheme(accent = Accent.valueOf(appearance.accent.name), reduceMotion = appearance.reduceMotion) {
                val toaster = remember { Toaster() }
                CompositionLocalProvider(
                    LocalToaster provides toaster,
                    LocalCardSizes provides if (appearance.compact) CardSizes.Compact else CardSizes.Comfortable,
                    LocalUnlockedTitles provides unlockedTitles,
                    LocalNowPlaying provides nowPlaying,
                    LocalLibraryKinds provides libraryKinds,
                    LocalUpdatePending provides update.pending,
                    LocalMusicProgress provides startViewModel.music.progress,
                ) {
                    // While music plays the screen stays on; idle, the now-playing saver takes over.
                    val musicPlaying = nowPlaying?.playing == true
                    val view = LocalView.current
                    DisposableEffect(view, musicPlaying) {
                        view.keepScreenOn = musicPlaying
                        onDispose { view.keepScreenOn = false }
                    }
                    var lastInput by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
                    var saverOn by remember { mutableStateOf(false) }
                    var swallowKeyUp by remember { mutableStateOf(false) }
                    LaunchedEffect(musicPlaying, lastInput) {
                        if (!musicPlaying) {
                            saverOn = false
                            return@LaunchedEffect
                        }
                        delay(SAVER_IDLE_MS - (SystemClock.uptimeMillis() - lastInput))
                        saverOn = true
                    }
                    GlacierBackground(
                        Modifier.onPreviewKeyEvent { event ->
                            lastInput = SystemClock.uptimeMillis()
                            // The key that ends the saver does nothing else; media keys still reach the music.
                            if (saverOn && event.type == KeyEventType.KeyDown && !startViewModel.music.ownsKey(event.nativeKeyEvent.keyCode)) {
                                saverOn = false
                                swallowKeyUp = true
                                return@onPreviewKeyEvent true
                            }
                            if (swallowKeyUp && event.type == KeyEventType.KeyUp) {
                                swallowKeyUp = false
                                return@onPreviewKeyEvent true
                            }
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp, Key.DirectionDown -> NavDirection.vertical = true
                                    Key.DirectionLeft, Key.DirectionRight -> NavDirection.vertical = false
                                }
                            }
                            // Nothing reacts to keys while Android installs an update.
                            update is UpdateState.Installing
                        }.onKeyEvent { event ->
                            // Media keys no screen used go to the music, wherever the user is.
                            val code = event.nativeKeyEvent.keyCode
                            when (event.type) {
                                KeyEventType.KeyDown -> startViewModel.music.onMediaKey(code)
                                KeyEventType.KeyUp -> startViewModel.music.ownsKey(code)
                                else -> false
                            }
                        },
                    ) {
                        val start by startViewModel.start.collectAsStateWithLifecycle()
                        start?.let {
                            val navController = rememberNavController()
                            GlacierNavHost(navController, startDestination = it)
                            LaunchedEffect(navController) {
                                startViewModel.relaunch.collect { route ->
                                    navController.navigate(route) { popUpTo(0) { inclusive = true } }
                                }
                            }
                        }
                        UpdateLayer(startViewModel.updates, toaster)
                        CrashNotice(toaster)
                        ToastHost(toaster)
                        NowPlayingSaver(saverOn, nowPlaying, startViewModel.music.progress)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startViewModel.updates.onAppResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        HomeLaunch.from(intent)?.let(startViewModel::open)
    }
}

/** What the updater shows on any screen: its notices as toasts and the overlay while Android installs. */
@Composable
private fun UpdateLayer(updates: UpdateManager, toaster: Toaster) {
    val context = LocalContext.current
    LaunchedEffect(updates) { updates.notices.collect { toaster.show(it.text(context)) } }
    val state by updates.state.collectAsStateWithLifecycle()
    (state as? UpdateState.Installing)?.let { InstallingOverlay(it.candidate.version) }
}

/** After a crash, the next start points to sending the log (Settings › System › Diagnostics). */
@Composable
private fun CrashNotice(toaster: Toaster) {
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        if (withContext(Dispatchers.IO) { Log.takeCrashNotice() }) toaster.show(resources.getString(R.string.diag_crash_notice))
    }
}
