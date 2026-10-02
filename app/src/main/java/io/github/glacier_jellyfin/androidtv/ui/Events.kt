package io.github.glacier_jellyfin.androidtv.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Collects a view model's [UiEvent]s for as long as the screen is shown and
 * the activity is started. An event that arrives while the activity is being
 * recreated (a new interface language) waits for the new one.
 */
@Composable
fun CollectEvents(events: Flow<UiEvent>, onNavigate: (UiEvent.Navigate) -> Unit) {
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val lifecycle = (LocalActivity.current as? LifecycleOwner ?: LocalLifecycleOwner.current).lifecycle
    LaunchedEffect(events, lifecycle) {
        // Main.immediate: a view model sends on the main thread, so the event is handled
        // within send() and cannot be dropped by a cancellation before it is dispatched.
        withContext(Dispatchers.Main.immediate) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                events.collect { event ->
                    when (event) {
                        is UiEvent.Navigate -> onNavigate(event)
                        is UiEvent.Toast -> toaster.show(resources.getString(event.message, *event.args.toTypedArray()))
                    }
                }
            }
        }
    }
}
