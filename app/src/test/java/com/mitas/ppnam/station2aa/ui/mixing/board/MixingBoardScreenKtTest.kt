package com.mitas.ppnam.station2aa.ui.mixing.board

import com.mitas.ppnam.station2aa.ui.components.StatusTone
import org.junit.Assert.assertEquals
import org.junit.Test

class MixingBoardScreenKtTest {

    @Test
    fun `Available maps to Ready`() {
        assertEquals(StatusTone.Ready, machineStatusTone("Available"))
    }

    @Test
    fun `InUse maps to Warning`() {
        assertEquals(StatusTone.Warning, machineStatusTone("InUse"))
    }

    @Test
    fun `any other status maps to Danger`() {
        assertEquals(StatusTone.Danger, machineStatusTone("Disabled"))
    }
}
