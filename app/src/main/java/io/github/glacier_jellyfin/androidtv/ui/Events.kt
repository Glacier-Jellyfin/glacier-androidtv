package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.flow.Flow

/** Collects a view model's [UiEvent]s for as long as the screen is shown. */
@Composable
fun CollectEvents(events: Flow<UiEvent>, onNavigate: (UiEvent.Navigate) -> Unit) {
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is UiEvent.Navigate -> onNavigate(event)
                is UiEvent.Toast -> toaster.show(resources.getString(event.message, *event.args.toTypedArray()))
            }
        }
    }
}
