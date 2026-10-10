package io.github.glacier_jellyfin.androidtv.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind

/** Name of a library kind in the app language, e.g. "Filme". */
@get:StringRes
val LibraryKind.title: Int
    get() = when (this) {
        LibraryKind.Movies -> R.string.library_movies
        LibraryKind.Shows -> R.string.library_shows
        LibraryKind.Music -> R.string.library_music
        LibraryKind.MusicVideos -> R.string.library_music_videos
    }

/** Name of a library kind in the top navigation, e.g. "Film". */
@get:StringRes
val LibraryKind.navTitle: Int
    get() = when (this) {
        LibraryKind.Movies -> R.string.nav_movies
        LibraryKind.Shows -> R.string.nav_shows
        LibraryKind.Music -> R.string.nav_music
        LibraryKind.MusicVideos -> R.string.nav_music_videos
    }

/**
 * What a library is called on the home screen ("New in" rows, "My media"): its kind in the app language
 * when it is the only library of that kind in [all], else its name on the
 * server, so two movie libraries stay apart.
 */
@Composable
fun libraryTitle(library: Library, all: List<Library>): String =
    if (all.count { it.kind == library.kind } == 1) stringResource(library.kind.title) else library.name
