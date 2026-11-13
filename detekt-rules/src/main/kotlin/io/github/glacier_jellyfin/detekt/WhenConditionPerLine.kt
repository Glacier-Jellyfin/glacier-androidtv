package io.github.glacier_jellyfin.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtWhenEntry

/**
 * A `when` entry with several conditions that is spread over more than one
 * line puts every condition on a line of its own. Entries that fit on one
 * line stay as they are.
 */
class WhenConditionPerLine(config: Config) :
    Rule(
        config,
        "A multi-line `when` entry with several conditions has one condition per line.",
    ) {
    override fun visitWhenEntry(ktWhenEntry: KtWhenEntry) {
        super.visitWhenEntry(ktWhenEntry)
        val conditions = ktWhenEntry.conditions
        if (conditions.size < 2) return
        val text = ktWhenEntry.containingFile.text
        val lines = conditions.map {
            lineOf(text, it.textRange.startOffset) to lineOf(text, it.textRange.endOffset)
        }
        if (lines.first().first == lines.last().second) return
        if (lines.zipWithNext().any { (a, b) -> a.second == b.first }) {
            report(
                Finding(
                    Entity.from(ktWhenEntry),
                    "Put each condition of this `when` entry on its own line.",
                ),
            )
        }
    }

    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, minOf(offset, text.length)).count { it == '\n' }
}
