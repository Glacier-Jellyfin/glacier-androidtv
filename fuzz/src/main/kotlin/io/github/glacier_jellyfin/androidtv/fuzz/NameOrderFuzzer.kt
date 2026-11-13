package io.github.glacier_jellyfin.androidtv.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.glacier_jellyfin.androidtv.core.data.media.NameOrder
import kotlin.math.sign

/**
 * The app's A to Z order (NameOrder.kt) on any names. A comparator that breaks
 * its contract makes sorting throw ("Comparison method violates its general
 * contract") once a library is large enough, so the contract is checked here.
 *
 * The input is three names, separated by newlines.
 */
object NameOrderFuzzer {

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val names = data.consumeRemainingAsString().split('\n', limit = 3)
        val a = names[0]
        val b = names.getOrElse(1) { "" }
        val c = names.getOrElse(2) { "" }

        check(NameOrder.key(a) == NameOrder.key(a)) { "key(\"$a\") changes" }
        NameOrder.title(a, b)
        NameOrder.title(b, a)

        check(NameOrder.compare(a, a) == 0) { "\"$a\" is not equal to itself" }
        val ab = NameOrder.compare(a, b).sign
        val bc = NameOrder.compare(b, c).sign
        val ac = NameOrder.compare(a, c).sign
        check(ab == -NameOrder.compare(b, a).sign) { "\"$a\" and \"$b\" do not compare the same both ways" }
        check(ab != 0 || a == b) { "\"$a\" and \"$b\" are equal" }
        check(!(ab <= 0 && bc <= 0) || ac <= 0) { "\"$a\" <= \"$b\" <= \"$c\" but not \"$a\" <= \"$c\"" }
        check(!(ab >= 0 && bc >= 0) || ac >= 0) { "\"$a\" >= \"$b\" >= \"$c\" but not \"$a\" >= \"$c\"" }
    }
}
