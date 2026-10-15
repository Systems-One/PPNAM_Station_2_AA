package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerStageTest {

    private val main = Machine("M_MAIN", "Main Mixer", "Mixing", "MAIN-MIX-01", true, MachineKind.Main, false, null,
        listOf("M_EXT1", "EXT-2", "M_EXT3", "M_OFF"))
    private val mixer6 = Machine("M_6", "Mixer 6", "Mixing", "MIX6", true, MachineKind.Mixer6, false, null, listOf("M_JAN"))
    private val pair = Machine("M_PAIR", "Pair A", "Mixing", "PAIR-A", true, MachineKind.FixedPair, false, "PAIR1", emptyList())
    private val rajoo = Machine("M_RAJ", "Rajoo", "Rajoo", "RAJ-1", true, MachineKind.Rajoo, false, null, emptyList())
    private val uncommissioned = Machine("M_NEW", "New", "Mixing", "", true, MachineKind.Main, false, null, emptyList())
    private val off = Machine("M_OFFM", "Off mixer", "Mixing", "OFF-MIX", false, MachineKind.Main, false, null, emptyList())
    private val ext1 = Machine("M_EXT1", "Extruder 1", "Ext", "EXT-1", true, MachineKind.Unknown, true, null, emptyList())
    private val ext2 = Machine("M_EXT2", "Extruder 2", "Ext", "EXT-2", true, MachineKind.Unknown, true, null, emptyList())
    private val ext3 = Machine("M_EXT3", "Extruder 3", "Ext", "EXT-3", true, MachineKind.Unknown, true, null, emptyList())
    private val extOff = Machine("M_OFF", "Extruder off", "Ext", "EXT-OFF", false, MachineKind.Unknown, true, null, emptyList())
    private val all = listOf(main, mixer6, pair, rajoo, uncommissioned, off, ext1, ext2, ext3, extOff)

    private fun prep(
        stage: PrepStage, mixed: Int = 0, produced: Int = 0, n: Int = 3, mixerId: String? = null,
        productionId: String? = null, cycleId: String? = null, id: String = "PREP_1",
    ) = Preparation(id, "JOB", n, mixed, produced, stage, stage.name, 1, null, null, null, emptyList(),
        mixerId = mixerId, productionId = productionId, cycleId = cycleId)

    private fun snap(p: Preparation?, others: List<PrepSummary> = emptyList(), machines: List<Machine> = all) = PrepSnapshot(
        lookup = JobLookupSnapshot(emptyList(), null), preparation = p, requiredChoices = emptyList(),
        exceptionListRevision = null, collectionExceptions = emptyList(), commandExceptions = emptyList(),
        resultTargetId = null, resultMessage = "", machines = machines, preparations = others,
    )

    private fun other(id: String, stage: PrepStage, mixerId: String? = null, productionId: String? = null) =
        PrepSummary(id, "J2", stage, mixerId, productionId)

    @Test fun `match is case-insensitive and trims`() {
        val s = snap(null)
        assertEquals(main, s.matchMachine("main-mix-01"))
        assertEquals(main, s.matchMachine(" MAIN-MIX-01 "))
        assertNull(s.matchMachine("nope"))
    }

    @Test fun `blank codes never match even an uncommissioned machine`() {
        val s = snap(null)
        assertNull(s.matchMachine(""))
        assertNull(s.matchMachine("  "))
    }

    @Test fun `free by default`() = assertEquals(MachineUse.Free, snap(null).machineUse(main))

    @Test fun `disabled when not enabled or blank code`() {
        assertEquals(MachineUse.Disabled, snap(null).machineUse(off))
        assertEquals(MachineUse.Disabled, snap(null).machineUse(uncommissioned))
    }

    @Test fun `in use by another live prep by mixerId or productionId`() {
        val s = snap(null, listOf(other("PREP_2", PrepStage.Mixing, mixerId = "M_MAIN"),
            other("PREP_3", PrepStage.Producing, mixerId = "M_6", productionId = "M_EXT1")))
        assertEquals(MachineUse.InUse("PREP_2"), s.machineUse(main))
        assertEquals(MachineUse.InUse("PREP_3"), s.machineUse(ext1))
        assertEquals(MachineUse.Free, s.machineUse(ext2))
    }

    @Test fun `completed and cancelled preps and the current prep do not occupy a machine`() {
        val s = snap(prep(PrepStage.ReadyForNextMix, mixed = 1, mixerId = "M_MAIN"), listOf(
            other("PREP_1", PrepStage.ReadyForNextMix, mixerId = "M_MAIN"),
            other("PREP_2", PrepStage.Completed, mixerId = "M_6"),
            other("PREP_3", PrepStage.Cancelled, mixerId = "M_6")))
        assertEquals(MachineUse.Free, s.machineUse(main))
        assertEquals(MachineUse.Free, s.machineUse(mixer6))
    }

    @Test fun `a disabled machine in use is still disabled`() {
        val s = snap(null, listOf(other("PREP_2", PrepStage.Mixing, mixerId = "M_OFFM")))
        assertEquals(MachineUse.Disabled, s.machineUse(off))
    }

    @Test fun `mixers are non-production non-rajoo machines`() {
        assertEquals(listOf(main, mixer6, pair, uncommissioned, off), snap(null).mixers())
    }

    @Test fun `free destinations resolve by id or code and drop disabled and busy`() {
        val s = snap(prep(PrepStage.ReadyForProduction, mixed = 3, mixerId = "M_MAIN"),
            listOf(other("PREP_2", PrepStage.Producing, productionId = "M_EXT3")))
        assertEquals(listOf(ext1, ext2), s.freeDestinations())
    }

    @Test fun `no saved mixer means no destinations`() =
        assertTrue(snap(prep(PrepStage.ReadyForProduction)).freeDestinations().isEmpty())

    @Test fun `fixed pair detection`() {
        assertTrue(snap(prep(PrepStage.Mixing, mixerId = "M_PAIR")).isFixedPair())
        assertTrue(!snap(prep(PrepStage.Mixing, mixerId = "M_MAIN")).isFixedPair())
        assertTrue(!snap(prep(PrepStage.ReadyForMixer)).isFixedPair())
    }

    @Test fun `unknown code`() =
        assertEquals(MachineScan.NotAMachine("X1"), snap(prep(PrepStage.ReadyForMixer)).scanMachine("X1"))

    @Test fun `first start`() = assertEquals(MachineScan.StartMix(main, 1, 3, true, null),
        snap(prep(PrepStage.ReadyForMixer)).scanMachine("main-mix-01"))

    @Test fun `next mix start reports the saved mixer and k`() {
        val s = snap(prep(PrepStage.ReadyForNextMix, mixed = 1, mixerId = "M_MAIN"))
        assertEquals(MachineScan.StartMix(main, 2, 3, false, main), s.scanMachine("MAIN-MIX-01"))
        assertEquals(MachineScan.StartMix(mixer6, 2, 3, false, main), s.scanMachine("MIX6"))
    }

    @Test fun `disabled machine cannot start`() {
        assertEquals(MachineScan.Disabled(off), snap(prep(PrepStage.ReadyForMixer)).scanMachine("OFF-MIX"))
        assertEquals(MachineScan.Disabled(extOff),
            snap(prep(PrepStage.ReadyForProduction, 3, mixerId = "M_MAIN")).scanMachine("EXT-OFF"))
    }

    @Test fun `production start`() {
        val s = snap(prep(PrepStage.ReadyForProduction, mixed = 3, produced = 1, mixerId = "M_MAIN", productionId = "M_EXT1"))
        assertEquals(MachineScan.StartProduction(ext2, 2, 3, ext1), s.scanMachine("ext-2"))
    }

    @Test fun `a mixer is not an extruder and vice versa`() {
        assertTrue(snap(prep(PrepStage.ReadyForProduction, 3, mixerId = "M_MAIN")).scanMachine("MAIN-MIX-01") is MachineScan.NotNow)
        assertTrue(snap(prep(PrepStage.ReadyForMixer)).scanMachine("EXT-1") is MachineScan.NotNow)
    }

    @Test fun `finish requires the active mixer and carries the snapshot cycle`() {
        val s = snap(prep(PrepStage.Mixing, mixed = 1, mixerId = "M_MAIN", cycleId = "CYC_9"))
        assertEquals(MachineScan.Finish(main, 2, 3, false, "CYC_9"), s.scanMachine("main-mix-01"))
        assertEquals(MachineScan.NotActive("Main Mixer"), s.scanMachine("MIX6"))
    }

    @Test fun `finish production uses productionId`() {
        val s = snap(prep(PrepStage.Producing, mixed = 3, produced = 2, mixerId = "M_MAIN", productionId = "M_EXT1", cycleId = "CYC_P"))
        assertEquals(MachineScan.Finish(ext1, 3, 3, true, "CYC_P"), s.scanMachine("EXT-1"))
        assertEquals(MachineScan.NotActive("Extruder 1"), s.scanMachine("EXT-2"))
    }

    @Test fun `finishing without a cycle id is not now`() {
        val s = snap(prep(PrepStage.Mixing, mixerId = "M_MAIN", cycleId = null))
        assertTrue(s.scanMachine("MAIN-MIX-01") is MachineScan.NotNow)
    }

    @Test fun `a disabled active machine can still finish`() {
        val s = snap(prep(PrepStage.Mixing, mixerId = "M_OFFM", cycleId = "C"))
        assertTrue(s.scanMachine("OFF-MIX") is MachineScan.Finish)
    }

    @Test fun `other stages are not now`() {
        for (st in listOf(PrepStage.Collecting, PrepStage.AwaitingConfirmation, PrepStage.Completed, PrepStage.Cancelled, PrepStage.Unknown))
            assertTrue(st.name, snap(prep(st)).scanMachine("MAIN-MIX-01") is MachineScan.NotNow)
        assertTrue(snap(null).scanMachine("MAIN-MIX-01") is MachineScan.NotNow)
    }

    private fun strip(stage: PrepStage, fixed: Boolean = false) =
        prep(stage).stageStrip(fixed).steps.map { it.label to it.state }

    private val D = StripState.Done
    private val C = StripState.Current
    private val F = StripState.Future

    @Test fun `strip per stage`() {
        assertEquals(listOf("Collect" to C, "Mix" to F, "Produce" to F, "Done" to F), strip(PrepStage.Collecting))
        assertEquals(listOf("Collect" to D, "Mix" to C, "Produce" to F, "Done" to F), strip(PrepStage.Mixing))
        assertEquals(listOf("Collect" to D, "Mix" to C, "Produce" to F, "Done" to F), strip(PrepStage.ReadyForMixer))
        assertEquals(listOf("Collect" to D, "Mix" to D, "Produce" to C, "Done" to F), strip(PrepStage.ReadyForProduction))
        assertEquals(listOf("Collect" to D, "Mix" to D, "Produce" to D, "Done" to D), strip(PrepStage.Completed))
    }

    @Test fun `fixed pair strip has no produce step`() {
        assertEquals(listOf("Collect" to D, "Mix" to C, "Done" to F), strip(PrepStage.Mixing, true))
        assertEquals(listOf("Collect" to D, "Mix" to D, "Done" to D), strip(PrepStage.Completed, true))
    }

    @Test fun `machine kind parsing`() {
        assertEquals(MachineKind.FixedPair, MachineKind.fromWire("FixedPair"))
        assertEquals(MachineKind.Unknown, MachineKind.fromWire("Whatever"))
    }
}
