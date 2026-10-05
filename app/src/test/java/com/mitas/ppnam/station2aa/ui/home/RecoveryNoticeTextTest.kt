package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
