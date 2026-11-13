package io.github.glacier_jellyfin.detekt

import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtValueArgument

private const val MAX_ARGUMENTS = 3
private const val MAX_ARGUMENT_LENGTH = 40

/**
 * A call whose argument list is spread over several lines for one to three
 * short arguments, followed straight away by `.`, `?.` or `)` of the same line:
 *
 *     YearMonth.of(
 *         group.year,
 *         group.month,
 *     ).format(pattern)
 *
 * The arguments stay on the line of the call and the chain breaks before the
 * dots instead.
 */
class DanglingShortArguments(config: Config) :
    Rule(
        config,
        "A call with a few short arguments is not wrapped in front of a trailing `.`, `?.` or `)`.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val list = expression.valueArgumentList ?: return
        val arguments = list.arguments
        if (arguments.size !in 1..MAX_ARGUMENTS) return
        if (arguments.any { !isShort(it) }) return
        val open = list.leftParenthesis ?: return
        val close = list.rightParenthesis ?: return
        val text = expression.containingFile.text
        if (!text.substring(open.textRange.startOffset, close.textRange.endOffset).contains('\n')) return
        val next = PsiTreeUtil.nextLeaf(close)?.text.orEmpty()
        if (next.startsWith(".") || next.startsWith("?.") || next.startsWith(")")) {
            report(
                Finding(
                    Entity.from(expression),
                    "Keep the short arguments on the line of the call and break before `${next.take(2)}` instead.",
                ),
            )
        }
    }

    private fun isShort(argument: KtValueArgument): Boolean = argument.getArgumentExpression() !is KtLambdaExpression &&
        !argument.text.contains('\n') &&
        argument.text.length <= MAX_ARGUMENT_LENGTH
}
