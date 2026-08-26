package com.mitas.ppnam.station2aa.ui.mixing

import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.ui.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class IngredientScanScreenKtTest {

    // remainingQty > 0.0 and no bagSize (bulk line) => isFullyAllocated false => isSatisfied false.
    // remainingQty == 0.0 => isFullyAllocated true, isBagged false => isSatisfied true.
    private fun bomLine(remainingQty: Double) = BomLine(
        lineNumber = 1,
        itemCode = "MAT-1",
        itemName = "Resin",
        requiredQty = 10.0,
        remainingQty = remainingQty,
    )

    @Test
    fun `pending line maps to Running regardless of armed or satisfied`() {
        assertEquals(StatusTone.Running, bomLine(remainingQty = 5.0).checklistTone(armed = false, pending = true))
    }

    @Test
    fun `pending overrides even a satisfied and armed line`() {
        assertEquals(StatusTone.Running, bomLine(remainingQty = 0.0).checklistTone(armed = true, pending = true))
    }

    @Test
    fun `satisfied unarmed line maps to Ready`() {
        assertEquals(StatusTone.Ready, bomLine(remainingQty = 0.0).checklistTone(armed = false, pending = false))
    }

    @Test
    fun `satisfied line stays Ready even if armed`() {
        assertEquals(StatusTone.Ready, bomLine(remainingQty = 0.0).checklistTone(armed = true, pending = false))
    }

    @Test
    fun `armed unsatisfied line maps to Running`() {
        assertEquals(StatusTone.Running, bomLine(remainingQty = 5.0).checklistTone(armed = true, pending = false))
    }

    @Test
    fun `idle unsatisfied unarmed line maps to Idle`() {
        assertEquals(StatusTone.Idle, bomLine(remainingQty = 5.0).checklistTone(armed = false, pending = false))
    }
}
