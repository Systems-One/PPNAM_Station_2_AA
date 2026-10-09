package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryNoticeTextTest {

    private val cmd = PendingCommand(
        messageId = "m", action = "capture", targetId = "PREP_1", operatorId = "OP-9", sessionId = "S-old",
    )

    @Test
    fun `status line says whose move it is`() {
        assertEquals("capture PREP_1: waiting for Station 2", cmd.statusLine("OP-9", "S-now"))
        assertEquals(
            "capture PREP_1: waiting for Station 2 — reopen the job to retry it",
            cmd.statusLine("OP-9", "S-old"),
        )
        assertEquals("capture PREP_1: sent by operator OP-9. They must sign in on this scanner to resolve it, or a manager must reconcile it.", cmd.statusLine("OP-1", "S-old"))
        assertEquals("capture PREP_1: sent by operator OP-9. They must sign in on this scanner to resolve it, or a manager must reconcile it.", cmd.statusLine(null, null))
        assertEquals(
            "capture PREP_1: needs a manager",
            cmd.copy(status = PendingStatus.ManagerReconcile).statusLine("OP-1", "S-now"),
        )
    }

    @Test
    fun `clear is offered to anyone signed in, only for a command awaiting reconciliation`() {
        val reconcile = cmd.copy(status = PendingStatus.ManagerReconcile)
        assertTrue(reconcile.canBeCleared(signedIn = true))
        assertFalse(reconcile.canBeCleared(signedIn = false))
        assertFalse(cmd.canBeCleared(signedIn = true))   // Unresolved: retry or recovery settles it
    }

    @Test
    fun `another operator's command from another scanner needs a manager, not a sign-in here`() {
        val foreign = cmd.copy(deviceId = "scanner_2")
        val expected = "capture PREP_1: sent by operator OP-9 on another scanner. A manager must reconcile it."
        assertEquals(expected, foreign.statusLine("OP-1", "S-now", currentDeviceId = "scanner_1"))
        assertEquals(expected, foreign.statusLine(null, null, currentDeviceId = "scanner_1"))
        assertEquals(expected, RecoveryResult.OtherOperator(foreign).noticeText(currentDeviceId = "scanner_1"))
        // Sent here (or a legacy entry with no device): signing in on this scanner still works.
        assertEquals(
            "capture PREP_1: sent by operator OP-9. They must sign in on this scanner to resolve it, or a manager must reconcile it.",
            cmd.copy(deviceId = "scanner_1").statusLine("OP-1", "S-now", currentDeviceId = "scanner_1"),
        )
    }

    @Test
    fun `check again is offered only for the signed-in operator unresolved commands from an earlier session`() {
        val mine = cmd.copy(status = PendingStatus.Unresolved)
        assertTrue(shouldOfferCheckAgain(listOf(mine), "OP-9", "S-now"))
        // Same session: recovery skips it, so the button would do nothing.
        assertFalse(shouldOfferCheckAgain(listOf(mine), "OP-9", "S-old"))
        assertFalse(shouldOfferCheckAgain(listOf(mine), "OP-1", "S-now"))
        assertFalse(shouldOfferCheckAgain(listOf(mine), null, null))
        assertFalse(shouldOfferCheckAgain(listOf(mine.copy(status = PendingStatus.ManagerReconcile)), "OP-9", "S-now"))
    }

    @Test
    fun `notices are visible only to their own operator`() {
        val notices = listOf<RecoveryResult>(RecoveryResult.OtherOperator(cmd))
        assertEquals(notices, notices.visibleTo("OP-9"))
        assertTrue(notices.visibleTo("OP-1").isEmpty())
        assertTrue(notices.visibleTo(null).isEmpty())
    }

    @Test
    fun `each outcome tells the operator what to do`() {
        assertEquals(
            "capture PREP_1: Station 2 had already done this. It was not repeated.",
            RecoveryResult.Resolved(cmd, RecoveryOutcome.Committed, "Bag recorded", Rev2Snapshot()).noticeText(),
        )
        assertEquals(
            "capture PREP_1: Station 2 rejected it — Too much. Re-read before trying again.",
            RecoveryResult.Resolved(cmd, RecoveryOutcome.Rejected, "Too much", Rev2Snapshot()).noticeText(),
        )
        assertEquals(
            "capture PREP_1: this never happened. Re-read the job, then do it again only if it is still needed.",
            RecoveryResult.Resolved(cmd, RecoveryOutcome.NotExecuted, "", Rev2Snapshot()).noticeText(),
        )
        assertEquals(
            "capture PREP_1: a manager must reconcile this at the station. Owner unknown",
            RecoveryResult.NeedsManager(cmd, "Owner unknown").noticeText(),
        )
        assertEquals(
            "capture PREP_1: still unresolved — No reply. It is checked again when the scanner reconnects or you tap Check again.",
            RecoveryResult.StillUnresolved(cmd, "No reply").noticeText(),
        )
        assertEquals(
            "capture PREP_1: sent by operator OP-9. They must sign in on this scanner to resolve it, or a manager must reconcile it.",
            RecoveryResult.OtherOperator(cmd).noticeText(),
        )
    }

    @Test
    fun `a not-executed capture or confirm notice offers to open its PREP, a prepare its job`() {
        val prep = "PREP_440fbd8f9cd3411e9be943a33f1a3ceb"
        val capture = cmd.copy(targetId = prep)
        assertEquals(
            "Open PREP_…1a3ceb",
            RecoveryResult.Resolved(capture, RecoveryOutcome.NotExecuted, "", Rev2Snapshot()).openActionLabel(),
        )
        assertEquals(
            "Open PREP_…1a3ceb",
            RecoveryResult.Resolved(capture.copy(action = "confirm"), RecoveryOutcome.NotExecuted, "", Rev2Snapshot())
                .openActionLabel(),
        )
        assertEquals(
            "Open job 510019068",
            RecoveryResult.Resolved(cmd.copy(action = "prepare", targetId = "510019068"), RecoveryOutcome.NotExecuted, "", Rev2Snapshot())
                .openActionLabel(),
        )
        assertNull(RecoveryResult.Resolved(capture, RecoveryOutcome.Committed, "", Rev2Snapshot()).openActionLabel())
        assertNull(RecoveryResult.StillUnresolved(capture, "x").openActionLabel())
    }
}
