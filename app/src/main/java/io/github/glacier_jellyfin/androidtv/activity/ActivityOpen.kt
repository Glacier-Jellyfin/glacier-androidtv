package io.github.glacier_jellyfin.androidtv.activity

import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityTitle
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.routeFor
import io.github.glacier_jellyfin.androidtv.parental.PinGate

/**
 * Opens a card of the activity pages: a show card opens its show, a home video plays after the PIN gate,
 * anything else opens its page. [navigate] gets the route.
 */
fun PinGate.openActivityTitle(
    title: ActivityTitle,
    ageFilter: AgeFilter,
    navigate: (Any) -> Unit,
) {
    val item = title.item
    when {
        title.seriesId != null -> navigate(DetailRoute(title.seriesId.toString()))

        item.kind == ItemKind.Other -> openTitle(
            ageFilter,
            id = item.id,
            name = item.title,
            rating = item.officialRating,
            parents = listOfNotNull(item.seriesId),
        ) { navigate(PlayerRoute(item.id.toString())) }

        else -> navigate(routeFor(item))
    }
}
