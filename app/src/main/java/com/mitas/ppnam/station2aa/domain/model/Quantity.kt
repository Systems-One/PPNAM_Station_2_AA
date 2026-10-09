package com.mitas.ppnam.station2aa.domain.model

import java.math.BigDecimal

/**
 * Contract §2: quantities are compared exactly and sent as JSON numbers. Gson writes a BigDecimal
 * through toString(), which uses exponent notation for a negative scale (1E+2) or for an adjusted
 * exponent below -6. A scale of 0..[QuantityInput.MAX_SCALE] avoids both.
 */
object WireDecimal {
    fun of(value: BigDecimal): BigDecimal =
        value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
}

/** Parses what an operator typed for a part bag. Null means "not a quantity we may send". */
object QuantityInput {
    /** Spec open question Q3: grams, for kg materials. */
    const val MAX_SCALE = 3
    private val MAX = BigDecimal("999999999")
    private val PATTERN = Regex("^[0-9]+([.,][0-9]+)?$")

    fun parse(text: String): BigDecimal? {
        val trimmed = text.trim()
        if (!PATTERN.matches(trimmed)) return null
        val value = WireDecimal.of(BigDecimal(trimmed.replace(',', '.')))
        if (value.signum() <= 0 || value > MAX || value.scale() > MAX_SCALE) return null
        return value
    }
}
