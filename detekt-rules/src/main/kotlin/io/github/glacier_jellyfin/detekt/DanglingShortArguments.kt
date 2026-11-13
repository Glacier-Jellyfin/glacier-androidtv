package io.github.glacier_jellyfin.detekt

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument

private const val MAX_ARGUMENTS = 3
private const val MAX_ARGUMENT_LENGTH = 40
private const val MAX_LINE_LENGTH = 120

private val COLLECTION_BUILDERS = setOf(
    "listOf",
    "setOf",
    "mapOf",
    "arrayOf",
    "mutableListOf",
    "mutableSetOf",
    "mutableMapOf",
    "intArrayOf",
    "longArrayOf",
    "floatArrayOf",
    "byteArrayOf",
    "listOfNotNull",
    "sequenceOf",
)

/** What follows `)` on its line and makes the wrapped argument list look stranded: an operator or an infix call. */
private val OPERATOR_AFTER = Regex(
    """^(\?:|==|!=|<=|>=|&&|\|\||-(?!>)|[+*/%<>]|(in|is|as|to|shl|shr|and|or|xor|togetherWith) )""",
)

/**
 * A call whose argument list is spread over several lines for one to three
 * short arguments, although they could stay on the line of the call:
 *
 *     YearMonth.of(
 *         group.year,
 *         group.month,
 *     ).format(pattern)
 *
 * Reported are calls
 * - followed straight away by `.`, `?.` or `)`: the chain breaks before the dots instead,
 * - ending a call chain (`x.foo(\n a,\n)`): one line, or a break before the last dot,
 * - followed by an operator, `?:` or a trailing lambda, when the call fits on one line,
 * - standing alone with positional arguments only (`val x = foo(\n a,\n)`), when the call fits on one line.
 *
 * A standalone call with named arguments is the usual Compose style, and `listOf` and the like are enumerations:
 * both are left alone.
 */
class DanglingShortArguments(config: Config) :
    Rule(
        config,
        "A call with a few short arguments is not wrapped when they can stay on the line of the call.",
    ) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val list = expression.valueArgumentList ?: return
        val arguments = list.arguments
        if (arguments.size !in 1..MAX_ARGUMENTS || arguments.any { !isShort(it) }) return
        val open = list.leftParenthesis ?: return
        val close = list.rightParenthesis ?: return
        val text = expression.containingFile.text
        if (!text.substring(open.textRange.startOffset, close.textRange.endOffset).contains('\n')) return
        val reason = reasonFor(expression, arguments, text, open.textRange.endOffset, close) ?: return
        report(Finding(Entity.from(expression), "Keep the short arguments on the line of the call: $reason."))
    }

    private fun reasonFor(
        expression: KtCallExpression,
        arguments: List<KtValueArgument>,
        text: String,
        openEnd: Int,
        close: PsiElement,
    ): String? {
        val next = PsiTreeUtil.nextLeaf(close)?.text.orEmpty()
        val rest = text.substring(close.textRange.endOffset).substringBefore('\n').trimStart()
        val oneLine by lazy { fitsOnOneLine(text, arguments, openEnd, close.textRange.endOffset) }
        val terminal = rest.isEmpty() || rest.first() in ",}"
        return when {
            isChained(next) ->
                "`${next.take(2)}` follows"

            terminal && isChainEnd(expression) && (oneLine || hasCallReceiver(expression)) -> "it ends a call chain"

            isStranded(rest) && oneLine ->
                "`${rest.take(2).trim()}` follows"

            terminal && oneLine && isPlainCall(expression, arguments) -> "the call fits on one line"

            else -> null
        }
    }

    private fun isChained(next: String) = next.startsWith(".") || next.startsWith("?.") || next.startsWith(")")

    private fun isStranded(rest: String) = rest.startsWith("{") || OPERATOR_AFTER.containsMatchIn(rest)

    /** A call chain can break in front of its last dot only when the receiver is itself a call or a group. */
    private fun hasCallReceiver(expression: KtCallExpression): Boolean {
        val receiver = (expression.parent as? KtQualifiedExpression)?.receiverExpression ?: return false
        val callInside = PsiTreeUtil.findChildOfType(receiver, KtCallExpression::class.java) != null
        return receiver is KtParenthesizedExpression || receiver is KtCallExpression || callInside
    }

    /** Named arguments are the usual Compose style, and a list of values is an enumeration: both may stay wrapped. */
    private fun isPlainCall(expression: KtCallExpression, arguments: List<KtValueArgument>): Boolean =
        arguments.none { it.isNamed() } && expression.calleeExpression?.text !in COLLECTION_BUILDERS

    private fun isChainEnd(expression: KtCallExpression): Boolean {
        val parent = expression.parent
        return parent is KtQualifiedExpression && parent.selectorExpression == expression
    }

    private fun fitsOnOneLine(
        text: String,
        arguments: List<KtValueArgument>,
        openEnd: Int,
        closeEnd: Int,
    ): Boolean {
        val lineStart = text.lastIndexOf('\n', openEnd - 1) + 1
        val after = text.substring(closeEnd).substringBefore('\n')
        val lambdaHead = after.substringBefore("->", "").let { if ("{" in it) "$it->" else "" }
        val kept = when {
            !after.trimStart().startsWith("{") -> after
            lambdaHead.isNotEmpty() -> lambdaHead
            else -> after.substringBefore('{') + "{"
        }
        val joined = text.substring(lineStart, openEnd) +
            arguments.joinToString(", ") { it.text } +
            ")" +
            kept
        return joined.length <= MAX_LINE_LENGTH
    }

    private fun isShort(argument: KtValueArgument): Boolean = argument.getArgumentExpression() !is KtLambdaExpression &&
        !argument.text.contains('\n') &&
        argument.text.length <= MAX_ARGUMENT_LENGTH
}
