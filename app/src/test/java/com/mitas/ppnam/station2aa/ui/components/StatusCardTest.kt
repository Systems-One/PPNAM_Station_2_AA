package com.mitas.ppnam.station2aa.ui.components

import com.mitas.ppnam.station2aa.ui.theme.BrandPrimary
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.InfoBlue
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StatusCardTest {

    @Test
    fun `each tone maps to its designed color`() {
        assertEquals(SuccessGreen, StatusTone.Ready.color())
        assertEquals(InfoBlue, StatusTone.Running.color())
        assertEquals(WarningOrange, StatusTone.Warning.color())
        assertEquals(DangerRed, StatusTone.Danger.color())
        assertEquals(TextMuted, StatusTone.Idle.color())
    }

    @Test
    fun `running tone is not a second green next to the brand primary`() {
        assertNotEquals(BrandPrimary, StatusTone.Running.color())
        assertNotEquals(SuccessGreen, StatusTone.Running.color())
    }
}
