package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BomLineTest {

    private fun bagged(lineNumber: Int = 0, remainingQty: Double = 0.0, remainingBags: Double? = 0.0) =
        BomLine(
            lineNumber = lineNumber,
            itemCode = "1600000301",
            itemName = "HD WHITE",
            requiredQty = 557.049,
            remainingQty = remainingQty,
            bagSize = "25.000 kg",
            remainingBags = remainingBags,
        )

    @Test
    fun `two rows sharing a material code stay distinct by line number`() {
        // The contract keeps duplicate SAP BOM rows separate using lineNumber. Keying on itemCode
        // would silently merge them and corrupt progress on both.
        val first = bagged(lineNumber = 0, remainingQty = 100.0)
        val second = bagged(lineNumber = 1, remainingQty = 50.0)

        assertEquals(first.itemCode, second.itemCode)
        assertFalse("lines with the same material must not be equal", first == second)
        assertEquals(setOf(0, 1), listOf(first, second).map { it.lineNumber }.toSet())
    }

    @Test
    fun `a bulk line has no bag figures at all`() {
        val bulk = BomLine(
            lineNumber = 0,
            itemCode = "BULK-1",
            itemName = "Bulk Resin",
            requiredQty = 500.0,
            remainingQty = 500.0,
            bagSize = null,
            expectedBags = null,
            scannedBags = null,
            remainingBags = null,
        )

        assertNull(bulk.bagSize)
        assertNull(bulk.expectedBags)
        assertNull(bulk.remainingBags)
        assertFalse(bulk.isBagged)
    }

    @Test
    fun `a bulk line completes on quantity alone`() {
        // remainingBags is null, not 0 — bag completion is meaningless here and must not gate.
        val bulk = BomLine(
            lineNumber = 0, itemCode = "BULK-1", itemName = "Bulk Resin",
            requiredQty = 500.0, remainingQty = 0.0,
            bagSize = null, expectedBags = null, scannedBags = null, remainingBags = null,
        )

        assertTrue(bulk.isFullyAllocated)
        assertTrue("a satisfied bulk line must not be blocked by absent bag figures", bulk.isSatisfied)
    }

    @Test
    fun `a bagged line needs both quantity and bags satisfied`() {
        assertFalse(bagged(remainingQty = 0.0, remainingBags = 2.0).isSatisfied)
        assertFalse(bagged(remainingQty = 10.0, remainingBags = 0.0).isSatisfied)
        assertTrue(bagged(remainingQty = 0.0, remainingBags = 0.0).isSatisfied)
    }

    @Test
    fun `a bagged line is identified by having a bag size`() {
        assertTrue(bagged().isBagged)
    }
}
