package com.mitas.ppnam.station2aa.ui.joblookup

import com.mitas.ppnam.station2aa.domain.model.ActivePreparation
import com.mitas.ppnam.station2aa.domain.model.JobMaterial
import com.mitas.ppnam.station2aa.domain.model.MixProgress
import com.mitas.ppnam.station2aa.domain.model.JobPreparation
import org.junit.Assert.assertEquals
import org.junit.Test

class JobFormatTest {

    private fun material(unit: String = "kg", excluded: Boolean = false) = JobMaterial(
        code = "1600000301", name = "HD WHITE", unit = unit,
        perMix = 69.631, required = 557.049, collected = 100.0, excluded = excluded,
    )

    @Test
    fun `a whole number has no decimals`() = assertEquals("25", formatQuantity(25.0))

    @Test
    fun `trailing zeros are dropped`() = assertEquals("3.5", formatQuantity(3.50))

    @Test
    fun `at most three decimals are shown`() = assertEquals("557.049", formatQuantity(557.0490000001))

    @Test
    fun `half-way rounds away from zero`() = assertEquals("0.001", formatQuantity(0.0005))

    @Test
    fun `a large quantity never uses scientific notation`() = assertEquals("12500000", formatQuantity(1.25e7))

    @Test
    fun `zero is zero`() = assertEquals("0", formatQuantity(0.0))

    @Test
    fun `negative zero is zero`() = assertEquals("0", formatQuantity(-0.0))

    @Test
    fun `a material line shows per-mix, required, collected and remaining with the unit`() =
        assertEquals("69.631 kg per mix · 100 of 557.049 kg · 457.049 kg to go", material().quantityLine())

    @Test
    fun `a blank unit leaves no dangling spaces`() =
        assertEquals("69.631 per mix · 100 of 557.049 · 457.049 to go", material(unit = "").quantityLine())

    @Test
    fun `an excluded material says so instead of showing a requirement`() =
        assertEquals("69.631 kg per mix · excluded from this job", material(excluded = true).quantityLine())

    @Test
    fun `a preparation line shows mixes and stage`() =
        assertEquals("2 mixes · 1 mixed · 0 produced · Collecting",
            JobPreparation("PREP_1", mixCount = 2, mixed = 1, produced = 0, stage = "Collecting").summaryLine())

    @Test
    fun `a single-mix preparation is singular`() =
        assertEquals("1 mix · 0 mixed · 0 produced · Mixing",
            JobPreparation("PREP_2", mixCount = 1, mixed = 0, produced = 0, stage = "Mixing").summaryLine())

    @Test
    fun `mix progress reads Required, Active, Available to prepare, Finished`() {
        val mp = MixProgress(
            requiredMixes = 60, allocatedMixes = 4, activeMixes = 4, availableToPrepareMixes = 56,
            remainingToFinishMixes = 60, collectedMixes = 0, confirmedMixes = 0, mixedMixes = 0,
            producedMixes = 0, activePreparations = emptyList(),
        )
        assertEquals("Required 60 · Active 4 · Available to prepare 56 · Finished 0", mp.countsLine())
    }

    @Test
    fun `an active preparation line shows its count, stage and progress`() {
        val prep = ActivePreparation("PREP_1", mixCount = 4, stage = "ReadyForMixer", mixed = 1, produced = 0, remainingToFinishMixes = 4)
        assertEquals("4 mixes · ReadyForMixer · 1 mixed · 0 finished", prep.summaryLine())
    }
}
