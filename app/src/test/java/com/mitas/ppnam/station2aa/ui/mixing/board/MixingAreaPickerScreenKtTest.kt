package com.mitas.ppnam.station2aa.ui.mixing.board

import com.mitas.ppnam.station2aa.ui.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class MixingAreaPickerScreenKtTest {

    @Test
    fun `a ready mix takes priority and maps to Ready`() {
        assertEquals(StatusTone.Ready, areaTone(mixes = 1, cycles = 3))
    }

    @Test
    fun `active cycles without a ready mix map to Running`() {
        assertEquals(StatusTone.Running, areaTone(mixes = 0, cycles = 2))
    }

    @Test
    fun `nothing active maps to Idle`() {
        assertEquals(StatusTone.Idle, areaTone(mixes = 0, cycles = 0))
    }
}
