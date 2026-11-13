package io.github.glacier_jellyfin.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression

private const val MAX_OPERAND_LENGTH = 40
private const val MAX_LINE_LENGTH = 120

private val COMPARISONS = setOf(
    KtTokens.EQEQ,
    KtTokens.EXCLEQ,
    KtTokens.EQEQEQ,
    KtTokens.EXCLEQEQEQ,
    KtTokens.LT,
    KtTokens.GT,
    KtTokens.LTEQ,
    KtTokens.GTEQ,
)

/**
 * The right operand of a comparison stands alone on the next line:
 *
 *     if (item.kind ==
 *         ItemKind.Series
 *     ) {
 *
 * A short operand stays on the line of the operator when the joined line fits
 * (a closing `)` of the condition on the line below counts to it).
 */
class DanglingShortOperand(config: Config) :
    Rule(
        config,
        "The short right operand of a comparison is not put on a line of its own.",
    ) {
    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationToken !in COMPARISONS) return
        val right = expression.right ?: return
        val operation = expression.operationReference
        val text = expression.containingFile.text
        val between = text.substring(operation.textRange.endOffset, right.textRange.startOffset)
        if (!between.contains('\n') || between.contains("//") || between.contains("/*")) return
        if (right.text.length > MAX_OPERAND_LENGTH || right.text.contains('\n')) return
        if (joinedLength(text, operation.textRange.endOffset, right.textRange.endOffset) <= MAX_LINE_LENGTH) {
            report(
                Finding(
                    Entity.from(expression),
                    "Put `${right.text}` on the line of `${operation.text}`; it fits there.",
                ),
            )
        }
    }

    /** Length of the line the comparison would have with its right operand back on the operator's line. */
    private fun joinedLength(
        text: String,
        operatorEnd: Int,
        operandEnd: Int,
    ): Int {
        val lineStart = text.lastIndexOf('\n', operatorEnd - 1) + 1
        val head = text.substring(lineStart, operatorEnd).trimEnd()
        val tailEnd = text.indexOf('\n', operandEnd).let { if (it < 0) text.length else it }
        val tail = text.substring(operandEnd, tailEnd)
        val below = text.substring(tailEnd).removePrefix("\n").substringBefore('\n').trim()
        val opened = head.count { it == '(' } > head.count { it == ')' }
        val closing = if (opened && tail.isBlank() && below.startsWith(")")) below else ""
        val operand = text.substring(operatorEnd, operandEnd).trim()
        return head.length + 1 + operand.length + tail.length + closing.length
    }
}
