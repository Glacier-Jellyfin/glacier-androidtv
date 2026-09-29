package io.github.glacier_jellyfin.androidtv.ui

import io.github.glacier_jellyfin.androidtv.core.designsystem.PinKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinPromptTest {

    private fun PinPrompt.enter(pin: String): PinPrompt = pin.fold(this) { prompt, c -> prompt.type(PinKey.Digit(c)) }

    private fun PinOutcome.prompt(): PinPrompt = (this as PinOutcome.Continue).prompt

    @Test
    fun `digits stop at four and delete clears the error`() {
        val full = PinPrompt(PinReason.Settings).enter("12345")
        assertEquals("1234", full.digits)
        val wrong = full.next(currentOk = false).prompt()
        assertEquals(PinError.Wrong, wrong.error)
        assertEquals("", wrong.digits)
        assertNull(wrong.enter("1").error)
    }

    @Test
    fun `the right pin unlocks settings and titles`() {
        assertEquals(PinOutcome.Unlocked, PinPrompt(PinReason.Settings).enter("1234").next(currentOk = true))
        val title = PinReason.Title("id", "Alien", "16")
        assertEquals(PinOutcome.Unlocked, PinPrompt(title).enter("1234").next(currentOk = true))
    }

    @Test
    fun `changing asks for the current pin, then the new one twice`() {
        val new = PinPrompt(PinReason.Change).enter("1234").next(currentOk = true).prompt()
        assertEquals(PinStep.New, new.step)
        val confirm = new.enter("5678").next().prompt()
        assertEquals(PinStep.Confirm, confirm.step)
        assertEquals(PinOutcome.NewPin("5678"), confirm.enter("5678").next())
    }

    @Test
    fun `creating starts with the new pin and a mismatch starts over`() {
        val create = PinPrompt(PinReason.Create)
        assertEquals(PinStep.New, create.step)
        val confirm = create.enter("1111").next().prompt()
        val retry = confirm.enter("2222").next().prompt()
        assertEquals(PinStep.New, retry.step)
        assertEquals(PinError.Mismatch, retry.error)
        assertNull(retry.newPin)
    }
}
