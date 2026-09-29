package io.github.glacier_jellyfin.androidtv.ui

import io.github.glacier_jellyfin.androidtv.core.designsystem.PinKey

/** Why the PIN is asked; decides the steps and the texts of the dialog. */
sealed interface PinReason {
    /** The Account settings are protected. */
    data object Settings : PinReason

    /** A title above the profile's age limit; [rating] null when it has none. */
    data class Title(val itemId: String, val name: String, val rating: String?) : PinReason

    /** Current PIN, then the new one twice. */
    data object Change : PinReason

    /** No PIN yet: the new one twice. */
    data object Create : PinReason
}

enum class PinStep { Current, New, Confirm }

enum class PinError { Wrong, Mismatch }

/** State of an open PIN dialog; the transitions are pure so they can be tested. */
data class PinPrompt(
    val reason: PinReason,
    val step: PinStep = if (reason == PinReason.Create) PinStep.New else PinStep.Current,
    val digits: String = "",
    /** The new PIN from [PinStep.New], waiting for its confirmation. */
    val newPin: String? = null,
    val error: PinError? = null,
) {
    val complete: Boolean get() = digits.length == LENGTH

    /** A key typed; [PinKey.Confirm] changes nothing, four digits finish a step on their own. */
    fun type(key: PinKey): PinPrompt = when (key) {
        is PinKey.Digit -> if (complete) this else copy(digits = digits + key.value, error = null)
        PinKey.Delete -> copy(digits = digits.dropLast(1), error = null)
        PinKey.Confirm -> this
    }

    /**
     * Moves on after four digits. [currentOk] is whether they matched the stored
     * PIN, only asked for in [PinStep.Current].
     */
    fun next(currentOk: Boolean = false): PinOutcome = when (step) {
        PinStep.Current -> when {
            !currentOk -> PinOutcome.Continue(copy(digits = "", error = PinError.Wrong))
            reason == PinReason.Change -> PinOutcome.Continue(copy(step = PinStep.New, digits = "", error = null))
            else -> PinOutcome.Unlocked
        }
        PinStep.New -> PinOutcome.Continue(copy(step = PinStep.Confirm, digits = "", newPin = digits, error = null))
        PinStep.Confirm -> if (digits == newPin) {
            PinOutcome.NewPin(digits)
        } else {
            PinOutcome.Continue(copy(step = PinStep.New, digits = "", newPin = null, error = PinError.Mismatch))
        }
    }

    companion object {
        const val LENGTH = 4
    }
}

sealed interface PinOutcome {
    data class Continue(val prompt: PinPrompt) : PinOutcome

    /** The stored PIN was entered. */
    data object Unlocked : PinOutcome

    /** A new PIN was entered twice. */
    data class NewPin(val pin: String) : PinOutcome
}
