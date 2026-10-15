package com.mitas.ppnam.station2aa.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * "How much to take", in whole bags and quarters (simplified collect spec, Bag guide). Display
 * only: it never changes what is recorded — the recorded amount is always the exact kg.
 *
 * [restQuarters] is 0..3. [approximate] is true when the rest is not an exact quarter (or was
 * rounded up to a whole bag), and [text] then carries `≈`.
 */
data class BagGuide(val wholeBags: Int, val restQuarters: Int, val approximate: Boolean, val text: String)

private val FOUR = BigDecimal(4)
private val QUARTER_GLYPHS = arrayOf("", "¼", "½", "¾")

/** Null when there is no positive [bagSize] (show plain `N kg`) or no positive [amount]. */
fun bagGuide(amount: BigDecimal, bagSize: BigDecimal?): BagGuide? {
    if (bagSize == null || bagSize.signum() <= 0 || amount.signum() <= 0) return null
    var whole = amount.divideToIntegralValue(bagSize)
    val rest = amount - whole * bagSize
    val restTimesFour = rest * FOUR
    var quarters = restTimesFour.divide(bagSize, 0, RoundingMode.HALF_UP).toInt()
    if (rest.signum() > 0 && quarters == 0) quarters = 1
    // Exact when the rest is precisely `quarters` quarters of a bag.
    var approximate = rest.signum() > 0 && restTimesFour.compareTo(bagSize * BigDecimal(quarters)) != 0
    if (quarters == 4) {
        whole += BigDecimal.ONE
        quarters = 0
        approximate = true
    }
    val wholeBags = whole.toInt()
    val approx = if (approximate) "≈ " else ""
    val text = when {
        wholeBags == 0 -> "$approx${QUARTER_GLYPHS[quarters]} bag"
        quarters == 0 -> "$approx${bags(wholeBags)}"
        else -> "${bags(wholeBags)} + $approx${QUARTER_GLYPHS[quarters]}"
    }
    return BagGuide(wholeBags, quarters, approximate, text)
}

private fun bags(n: Int) = if (n == 1) "1 bag" else "$n bags"

/**
 * The captures Submit sends, in order: `whole` captures of [bagSize], then the exact rest when it
 * is above zero. Without a positive [bagSize]: one capture of [amount]. Exact; sums to [amount].
 * Values are [WireDecimal]-normalised. Empty for a non-positive [amount].
 */
fun splitCaptures(amount: BigDecimal, bagSize: BigDecimal?): List<BigDecimal> {
    if (amount.signum() <= 0) return emptyList()
    if (bagSize == null || bagSize.signum() <= 0) return listOf(WireDecimal.of(amount))
    val whole = amount.divideToIntegralValue(bagSize)
    val rest = amount - whole * bagSize
    val bag = WireDecimal.of(bagSize)
    return List(whole.toInt()) { bag } + listOfNotNull(rest.takeIf { it.signum() > 0 }?.let(WireDecimal::of))
}

/** Why an Amount field cannot be submitted. [message] is shown muted next to the disabled Submit. */
sealed interface AmountProblem {
    val message: String

    data object Blank : AmountProblem { override val message = "Enter an amount" }
    data object NotANumber : AmountProblem { override val message = "Enter a number, e.g. 12.5" }
    data object NotPositive : AmountProblem { override val message = "Enter more than 0" }
    data object TooManyDecimals : AmountProblem {
        override val message = "At most ${QuantityInput.MAX_SCALE} decimals"
    }
    data class MoreThanNeeded(val remaining: BigDecimal, val unit: String) : AmountProblem {
        override val message get() = "More than still needed — ${plain(remaining)} $unit"
    }
    data class MoreThanOnPallet(val available: BigDecimal, val unit: String) : AmountProblem {
        override val message get() = "Only ${plain(available)} $unit on this pallet"
    }
}

sealed interface AmountCheck {
    data class Valid(val amount: BigDecimal) : AmountCheck
    data class Invalid(val problem: AmountProblem) : AmountCheck
}

private val SIGNED_DECIMAL = Regex("^-?[0-9]+([.,][0-9]+)?$")

/**
 * Validates the Amount field. [remaining] (still needed) and [available] (stock on the pallet) are
 * upper bounds when known. A valid amount is [QuantityInput]-parsed: exact, normalised, at most
 * [QuantityInput.MAX_SCALE] decimals.
 */
fun validateAmount(text: String, remaining: BigDecimal?, available: BigDecimal?, unit: String = "kg"): AmountCheck {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return AmountCheck.Invalid(AmountProblem.Blank)
    if (!SIGNED_DECIMAL.matches(trimmed)) return AmountCheck.Invalid(AmountProblem.NotANumber)
    val raw = WireDecimal.of(BigDecimal(trimmed.replace(',', '.')))
    if (raw.signum() <= 0) return AmountCheck.Invalid(AmountProblem.NotPositive)
    if (raw.scale() > QuantityInput.MAX_SCALE) return AmountCheck.Invalid(AmountProblem.TooManyDecimals)
    val value = QuantityInput.parse(trimmed) ?: return AmountCheck.Invalid(AmountProblem.NotANumber)
    if (remaining != null && value > remaining) {
        return AmountCheck.Invalid(AmountProblem.MoreThanNeeded(remaining, unit))
    }
    if (available != null && value > available) {
        return AmountCheck.Invalid(AmountProblem.MoreThanOnPallet(available, unit))
    }
    return AmountCheck.Valid(value)
}

private fun plain(value: BigDecimal): String = WireDecimal.of(value).toPlainString()
