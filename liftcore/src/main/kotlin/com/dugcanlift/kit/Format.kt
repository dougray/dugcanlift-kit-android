package com.dugcanlift.kit

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * The one shared numeric-display rule: "4" rather than "4.0", "2.5" stays
 * "2.5", "1/3" reads "0.333333". Prints exactly what iOS's
 * `CookFormat.trimmed` prints (C's `%g`: six significant digits, trailing
 * zeros dropped, scientific past 1e15), so a recipe reads the same on both
 * phones; `FormatTest` pins the shared cases.
 *
 * It used to print the full double ("0.3333333333333333") and clamp anything
 * past Int.MAX_VALUE to 2147483647.
 *
 * Always "." as the decimal separator, matching iOS and the parser, which only
 * reads ".". Non-finite values print "NaN" / "Infinity"; display code should
 * not get that far (see [RecipeIngredient.displayText]).
 */
fun Double.trimZeros(): String {
    if (!isFinite()) return toString()
    if (this == Math.floor(this) && Math.abs(this) < 1e15) return toLong().toString()
    return formatG(this)
}

/** C's `%g` with the default precision of 6, as Swift's `String(format: "%g")` prints it. */
private fun formatG(value: Double): String {
    if (value == 0.0) return "0"
    val rounded = BigDecimal(value).round(MathContext(6, RoundingMode.HALF_EVEN))
    val exponent = rounded.precision() - rounded.scale() - 1
    if (exponent < -4 || exponent >= 6) {
        val mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString()
        val sign = if (exponent < 0) "-" else "+"
        return "${mantissa}e$sign${String.format(Locale.ROOT, "%02d", Math.abs(exponent))}"
    }
    return rounded.stripTrailingZeros().toPlainString()
}
