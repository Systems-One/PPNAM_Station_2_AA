package com.mitas.ppnam.station2aa.domain.feedback

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.domain.model.Machine
import com.mitas.ppnam.station2aa.domain.model.MachineKind
import com.mitas.ppnam.station2aa.domain.model.MachineScan
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.SourceFit
import com.mitas.ppnam.station2aa.domain.model.SourceInfo
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.IdentifyResult
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupResult
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.math.BigDecimal

class ScanSoundsTest {

    private val source = SourceInfo(
        code = "TAG-RESIN", materialCode = "RESIN", name = "Resin", unit = "kg", bagSize = null,
        available = BigDecimal("100"), usable = true, reason = "",
    )
    private val row: PrepMaterial = mock()
    private val identified = IdentifyResult.Identified(source, mock())

    @Test
    fun `an identified pallet on an outstanding row is the only accepted identify`() {
        assertEquals(ScanSound.SUCCESS, ScanSounds.forIdentify(identified, SourceFit.InMix(row)))
        assertEquals(ScanSound.FAIL, ScanSounds.forIdentify(identified, SourceFit.NotInMix))
        assertEquals(ScanSound.FAIL, ScanSounds.forIdentify(identified, SourceFit.Excluded(row)))
        assertEquals(ScanSound.FAIL, ScanSounds.forIdentify(identified, SourceFit.Complete(row)))
        assertEquals(ScanSound.FAIL, ScanSounds.forIdentify(identified, null))
        val blocked = IdentifyResult.Identified(source.copy(usable = false, reason = "QC hold"), mock())
        assertEquals(ScanSound.FAIL, ScanSounds.forIdentify(blocked, SourceFit.InMix(row)))
    }

    @Test
    fun `not found, timed out, unsupported and failed identifies fail`() {
        assertEquals(ScanSound.FAIL, ScanSounds.forIdentify(IdentifyResult.Unknown("x", null), null))
        assertEquals(ScanSound.FAIL, ScanSounds.forIdentify(IdentifyResult.Unsupported("x", timedOut = true), null))
        assertEquals(ScanSound.FAIL, ScanSounds.forIdentify(IdentifyResult.Unsupported("x"), null))
        assertEquals(ScanSound.FAIL, ScanSounds.forIdentify(IdentifyResult.Failed("x", retryable = true), null))
    }

    @Test
    fun `a command answer sounds, a queued or unsent one does not`() {
        val cmd = PendingCommand(messageId = "m", action = "capture", targetId = "P", payload = "{}")
        assertEquals(ScanSound.SUCCESS, ScanSounds.forCommand(CollectionResult.Done(mock())))
        assertEquals(ScanSound.SUCCESS, ScanSounds.forCommand(CollectionResult.AlreadySettled("m")))
        assertEquals(ScanSound.FAIL, ScanSounds.forCommand(CollectionResult.Rejected(ErrorCode("rev2_rejected"), "x", null)))
        assertNull(ScanSounds.forCommand(CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "x")))
        assertNull(ScanSounds.forCommand(CollectionResult.NotSent("x")))
    }

    @Test
    fun `a wrong machine fails, an accepted one waits for its Start or Finish answer`() {
        val m = Machine("M", "Mixer 2", "Main", "MIX-02", true, MachineKind.Main, false, null, emptyList())
        assertEquals(ScanSound.FAIL, ScanSounds.forMachineScan(MachineScan.NotAMachine("X")))
        assertEquals(ScanSound.FAIL, ScanSounds.forMachineScan(MachineScan.NotActive("Mixer 3")))
        assertEquals(ScanSound.FAIL, ScanSounds.forMachineScan(MachineScan.NotNow("later")))
        assertNull(ScanSounds.forMachineScan(MachineScan.StartMix(m, 1, 2, true, null)))
        assertNull(ScanSounds.forMachineScan(MachineScan.Finish(m, 1, 2, false, "C")))
    }

    @Test
    fun `job lookups sound on the answer`() {
        assertEquals(ScanSound.SUCCESS, ScanSounds.forJobLookup(JobLookupResult.Loaded(mock())))
        assertEquals(ScanSound.SUCCESS, ScanSounds.forJobLookup(JobLookupResult.OpenPreparation("PREP_1")))
        assertEquals(ScanSound.FAIL, ScanSounds.forJobLookup(JobLookupResult.Failed("x")))
        assertEquals(ScanSound.SUCCESS, ScanSounds.forRajooLookup(ReadResult.Loaded(mock())))
        assertEquals(ScanSound.FAIL, ScanSounds.forRajooLookup(ReadResult.Failed("x", null, retryable = true)))
    }

    @Test
    fun `a repeat of the value in hand is the same scan`() {
        val gate = ScanRepeatGate { 0L }
        assertTrue(gate.isRepeat("TAG-1", inHand = "TAG-1"))
        assertFalse(gate.isRepeat("TAG-2", inHand = "TAG-1"))
        assertFalse(gate.isRepeat("TAG-1"))
    }

    @Test
    fun `a repeat of a failed value is silent for 1500 ms`() {
        var now = 1_000L
        val gate = ScanRepeatGate { now }
        gate.failed("TAG-1")
        now += 1_499
        assertTrue(gate.isRepeat("TAG-1"))
        assertFalse(gate.isRepeat("TAG-2"))
        now += 1
        assertFalse(gate.isRepeat("TAG-1"))
    }
}
