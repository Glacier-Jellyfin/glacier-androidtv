package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PinDots
import io.github.glacier_jellyfin.androidtv.core.designsystem.PinKey
import io.github.glacier_jellyfin.androidtv.core.designsystem.PinPad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Runs a [PinPrompt] for a view model: checks the profile's PIN, saves a new
 * one, and unlocks settings or titles in [ParentalControl].
 */
class PinGate(
    private val scope: CoroutineScope,
    private val parental: ParentalControl,
    private val toast: suspend (UiEvent.Toast) -> Unit,
) {
    private val _prompt = MutableStateFlow<PinPrompt?>(null)
    val prompt: StateFlow<PinPrompt?> = _prompt.asStateFlow()

    private var onDone: (() -> Unit)? = null
    private var pending: Job? = null

    /** Asks for the PIN; [onDone] runs once it is entered (or a new one is saved). */
    fun open(reason: PinReason, onDone: () -> Unit = {}) {
        pending?.cancel()
        this.onDone = onDone
        _prompt.value = PinPrompt(reason)
    }

    /**
     * Opens a title through the profile's age limit: at once, after the PIN, or
     * not at all ([onRefused]) while titles above the limit are hidden.
     */
    fun openTitle(
        ageFilter: AgeFilter,
        id: UUID,
        name: String,
        rating: String?,
        parents: Collection<UUID> = emptyList(),
        onRefused: () -> Unit = {},
        onOpen: () -> Unit,
    ) {
        scope.launch {
            when {
                !ageFilter.isLocked(id, parents) -> onOpen()
                ageFilter.hides -> {
                    toast(UiEvent.Toast(R.string.pin_title_hidden))
                    onRefused()
                }
                else -> open(PinReason.Title(id.toString(), name, rating), onOpen)
            }
        }
    }

    fun dismiss() {
        pending?.cancel()
        onDone = null
        _prompt.value = null
    }

    fun key(key: PinKey) {
        val current = _prompt.value ?: return
        if (key == PinKey.Confirm && !current.complete) {
            scope.launch { toast(UiEvent.Toast(R.string.pin_required)) }
            return
        }
        val typed = current.type(key)
        _prompt.value = typed
        if (!typed.complete || pending?.isActive == true) return
        pending = scope.launch {
            // Short pause so the fourth dot is visible before the result.
            delay(STEP_DELAY_MS)
            val ok = typed.step == PinStep.Current && parental.verifyPin(typed.digits)
            when (val outcome = typed.next(currentOk = ok)) {
                is PinOutcome.Continue -> _prompt.value = outcome.prompt
                PinOutcome.Unlocked -> {
                    when (val reason = typed.reason) {
                        PinReason.Settings -> parental.unlockSettings()
                        is PinReason.Title -> parental.unlock(reason.itemId)
                        PinReason.Change, PinReason.Create -> Unit
                    }
                    finish()
                }
                is PinOutcome.NewPin -> {
                    parental.setPin(outcome.pin)
                    // Setting the PIN proves who is at the remote, like entering it would.
                    parental.unlockSettings()
                    toast(UiEvent.Toast(R.string.pin_saved))
                    finish()
                }
            }
        }
    }

    private fun finish() {
        val done = onDone
        onDone = null
        _prompt.value = null
        done?.invoke()
    }

    private companion object {
        const val STEP_DELAY_MS = 260L
    }
}

/** The design's PIN sheet for a [PinGate]. */
@Composable
fun PinDialog(prompt: PinPrompt, onKey: (PinKey) -> Unit, onDismiss: () -> Unit) {
    val title = when (prompt.step) {
        PinStep.New -> stringResource(R.string.pin_new_title)
        PinStep.Confirm -> stringResource(R.string.pin_confirm_title)
        PinStep.Current -> when (val reason = prompt.reason) {
            PinReason.Settings -> stringResource(R.string.pin_settings_title)
            is PinReason.Title -> reason.name
            PinReason.Change, PinReason.Create -> stringResource(R.string.pin_current_title)
        }
    }
    val sub = when {
        prompt.error == PinError.Mismatch -> stringResource(R.string.pin_mismatch)
        prompt.error == PinError.Wrong -> stringResource(R.string.pin_wrong)
        prompt.step == PinStep.New -> stringResource(R.string.pin_new_hint)
        prompt.step == PinStep.Confirm -> stringResource(R.string.pin_confirm_hint)
        else -> when (val reason = prompt.reason) {
            PinReason.Settings -> stringResource(R.string.pin_settings_hint)
            is PinReason.Title -> reason.rating
                ?.let { stringResource(R.string.pin_title_rated_hint, it) }
                ?: stringResource(R.string.pin_title_unrated_hint)
            PinReason.Change, PinReason.Create -> stringResource(R.string.pin_current_hint)
        }
    }
    ModalSheet(onDismiss = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = GlacierText.display(28), color = GlacierColors.Ice, textAlign = TextAlign.Center)
            Text(
                sub,
                style = GlacierText.body(17),
                color = if (prompt.error != null) LocalAccent.current.main else GlacierColors.Mist,
                textAlign = TextAlign.Center,
            )
        }
        PinDots(filled = prompt.digits.length)
        val padFocus = remember { FocusRequester() }
        PinPad(onKey = onKey, firstKeyFocus = padFocus)
        LaunchedEffect(Unit) { runCatching { padFocus.requestFocus() } }
    }
}
