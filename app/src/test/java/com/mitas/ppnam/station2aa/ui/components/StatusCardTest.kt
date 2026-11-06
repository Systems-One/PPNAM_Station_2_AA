package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.mitas.ppnam.station2aa.ui.theme.BrandPrimary
import com.mitas.ppnam.station2aa.ui.theme.BrandTint
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.InfoBlue
import com.mitas.ppnam.station2aa.ui.theme.SuccessGreen
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.WarningOrange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        assertNotEquals(BrandTint, StatusTone.Running.color())
        assertNotEquals(SuccessGreen, StatusTone.Running.color())
    }

    // Visual critique 2026-11-06 P2: the job list's "Required 8 · Active 2 …" line was InfoBlue on
    // the graphite card, 3.9:1. Status text must reach WCAG AA body contrast (4.5:1) on the card.
    @Test
    fun `every tone's text color is readable on the card surface`() {
        StatusTone.entries.forEach { tone ->
            val ratio = contrast(tone.textColor(), GraphiteSurface)
            assertTrue("$tone text is ${"%.2f".format(ratio)}:1", ratio >= 4.5)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
