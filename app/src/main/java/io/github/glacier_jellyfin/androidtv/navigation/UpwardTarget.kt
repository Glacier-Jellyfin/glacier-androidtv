package io.github.glacier_jellyfin.androidtv.navigation

/**
 * Where a breadcrumb level to [target] leads, given the routes of the back stack [stack] (bottom first,
 * the current page last; a page of another kind is null): the index of the entry to go Back to, or null
 * to replace the current page.
 *
 * That entry is the nearest one that is the same place as [target], when everything above it, the current
 * page included, is a title page (or a folder, for a folder): Back from here would pass through them anyway.
 * A title is the same by its id, a library by its kind and library, a folder by its id (null: the top).
 * A library is also the same by its tab (scope), as a link to the artists is not the page showing the albums.
 * A library page of a whole kind (the navigation bar's) also counts for each library of that kind: it lists its titles.
 */
internal fun upwardPopIndex(stack: List<Any?>, target: Any): Int? {
    if (stack.isEmpty() || !passes(stack.last(), target)) return null
    for (index in stack.lastIndex - 1 downTo 0) {
        val route = stack[index]
        if (samePlace(route, target)) return index
        if (!passes(route, target)) return null
    }
    return null
}

/** The page may be left on the way to [target]: a page about one title or person, or a folder on the way to a folder. */
private fun passes(route: Any?, target: Any): Boolean = when (route) {
    is DetailRoute, is PersonRoute, is SeerrRoute -> true
    is FolderRoute -> target is FolderRoute
    else -> false
}

private fun samePlace(route: Any?, target: Any): Boolean = when {
    route is DetailRoute && target is DetailRoute -> route.itemId == target.itemId

    route is LibraryRoute && target is LibraryRoute ->
        route.genreId == null && target.genreId == null && route.scope == target.scope &&
            route.kind == target.kind && (route.libraryId == null || route.libraryId == target.libraryId)

    route is FolderRoute && target is FolderRoute -> route.folderId == target.folderId

    else -> false
}
