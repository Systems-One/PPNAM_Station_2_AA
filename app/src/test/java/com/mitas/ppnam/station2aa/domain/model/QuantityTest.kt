package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class QuantityTest {

    @Test
    fun `wire form is plain and minimal`() {
        assertEquals("10", WireDecimal.of(BigDecimal("10.000")).toString())
        assertEquals("100", WireDecimal.of(BigDecimal("1E+2")).toString())
        assertEquals("0.25", WireDecimal.of(BigDecimal("0.250")).toString())
        assertEquals("0.001", WireDecimal.of(BigDecimal("0.001")).toString())
    }

    @Test
    fun `operator input accepts a dot or a comma`() {
        assertEquals(0, BigDecimal("2.5").compareTo(QuantityInput.parse("2,5")))
        assertEquals(0, BigDecimal("2.5").compareTo(QuantityInput.parse(" 2.50 ")))
        assertEquals("12", QuantityInput.parse("12")!!.toString())
    }

    @Test
    fun `operator input rejects what Station 2 would reject or we cannot send exactly`() {
        listOf("", "0", "0.0", "-1", "+1", "1e3", "1.2345", "abc", "1.", ".5", "1000000000", "1,2,3")
            .forEach { assertNull(it, QuantityInput.parse(it)) }
    }

    @Test
    fun `comparison ignores scale`() {
        val remaining = BigDecimal("40")
        assertEquals(0, remaining.compareTo(BigDecimal("40.000")))
    }
}
