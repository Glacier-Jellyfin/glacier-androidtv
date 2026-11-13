package io.github.glacier_jellyfin.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class GlacierRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("glacier")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(::WhenConditionPerLine, ::DanglingShortArguments, ::DanglingShortOperand),
    )
}
