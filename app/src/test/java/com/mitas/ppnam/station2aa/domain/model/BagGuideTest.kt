package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class BagGuideTest {

    private fun d(s: String) = BigDecimal(s)

    @Test
    fun `bag guide table`() {
        // amount, bagSize, text, whole, quarters, approximate
        val cases = listOf(
            listOf("10", "25", "≈ ½ bag", 0, 2, true),
            listOf("60", "25", "2 bags + ≈ ½", 2, 2, true),
            listOf("24", "25", "≈ 1 bag", 1, 0, true),
            listOf("1", "25", "≈ ¼ bag", 0, 1, true),
            listOf("50", "25", "2 bags", 2, 0, false),
            listOf("25", "25", "1 bag", 1, 0, false),
            listOf("6.25", "25", "¼ bag", 0, 1, false),
            listOf("37.5", "25", "1 bag + ½", 1, 2, false),
            listOf("18.75", "25", "¾ bag", 0, 3, false),
            listOf("74", "25", "≈ 3 bags", 3, 0, true),
            listOf("20", "25", "≈ ¾ bag", 0, 3, true),
            listOf("12.5", "25", "½ bag", 0, 2, false),
            listOf("0.001", "25", "≈ ¼ bag", 0, 1, true),
        )
        for (c in cases) {
            val g = bagGuide(d(c[0] as String), d(c[1] as String))!!
            val label = "${c[0]}/${c[1]}"
            assertEquals(label, c[2], g.text)
            assertEquals(label, c[3], g.wholeBags)
            assertEquals(label, c[4], g.restQuarters)
            assertEquals(label, c[5], g.approximate)
        }
    }

    @Test
    fun `half-up rounding of the rest`() {
        // 3.125/25 = 0.5 quarter -> rounds up to 1 quarter
        assertEquals("≈ ¼ bag", bagGuide(d("3.125"), d("25"))!!.text)
        // 15.625/25 = 2.5 quarters -> 3
        assertEquals("≈ ¾ bag", bagGuide(d("15.625"), d("25"))!!.text)
    }

    @Test
    fun `no guide without a positive bag size or amount`() {
        assertNull(bagGuide(d("10"), null))
        assertNull(bagGuide(d("10"), BigDecimal.ZERO))
        assertNull(bagGuide(d("10"), d("-1")))
        assertNull(bagGuide(BigDecimal.ZERO, d("25")))
        assertNull(bagGuide(d("-5"), d("25")))
    }

    @Test
    fun `split into whole bags then the exact rest`() {
        assertEquals(listOf(d("25"), d("25"), d("10")), splitCaptures(d("60"), d("25")))
        assertEquals(listOf(d("25"), d("25")), splitCaptures(d("50"), d("25")))
        assertEquals(listOf(d("10")), splitCaptures(d("10"), d("25")))
        assertEquals(listOf(d("12.5"), d("0.125")), splitCaptures(d("12.625"), d("12.5")))
        assertEquals(listOf(d("24.999")), splitCaptures(d("24.999"), d("25")))
    }

    @Test
    fun `split without a bag size is one capture`() {
        assertEquals(listOf(d("60")), splitCaptures(d("60"), null))
        assertEquals(listOf(d("60")), splitCaptures(d("60"), BigDecimal.ZERO))
        assertEquals(listOf(d("60")), splitCaptures(d("60.000"), null))
    }

    @Test
    fun `split sums exactly to the amount`() {
        val parts = splitCaptures(d("1234.567"), d("33.3"))
        assertEquals(0, d("1234.567").compareTo(parts.fold(BigDecimal.ZERO, BigDecimal::add)))
        assertTrue(parts.all { it.scale() in 0..QuantityInput.MAX_SCALE })
    }

    @Test
    fun `split of a non-positive amount is empty`() {
        assertEquals(emptyList<BigDecimal>(), splitCaptures(BigDecimal.ZERO, d("25")))
    }

    private fun problem(text: String, remaining: String? = null, available: String? = null): String =
        (validateAmount(text, remaining?.let(::d), available?.let(::d)) as AmountCheck.Invalid).problem.message

    @Test
    fun `valid amounts parse exactly`() {
        assertEquals(AmountCheck.Valid(d("10")), validateAmount(" 10 ", d("25"), d("100")))
        assertEquals(AmountCheck.Valid(d("2.5")), validateAmount("2,500", d("25"), null))
        assertEquals(AmountCheck.Valid(d("25")), validateAmount("25", d("25"), d("25")))
        assertEquals(AmountCheck.Valid(d("1000")), validateAmount("1000", null, null))
    }

    @Test
    fun `amount problems are readable`() {
        assertEquals(AmountProblem.Blank, (validateAmount("  ", null, null) as AmountCheck.Invalid).problem)
        assertEquals(AmountProblem.NotPositive, (validateAmount("0", null, null) as AmountCheck.Invalid).problem)
        assertEquals(AmountProblem.NotPositive, (validateAmount("-3", null, null) as AmountCheck.Invalid).problem)
        assertEquals(AmountProblem.TooManyDecimals, (validateAmount("1.2345", null, null) as AmountCheck.Invalid).problem)
        assertEquals(AmountProblem.NotANumber, (validateAmount("abc", null, null) as AmountCheck.Invalid).problem)
        assertEquals("More than still needed — 12.5 kg", problem("13", remaining = "12.500"))
        assertEquals("Only 8 kg on this pallet", problem("9", remaining = "20", available = "8.000"))
        assertEquals("Enter an amount", problem(""))
    }
}
