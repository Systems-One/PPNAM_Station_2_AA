package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.model.CaptureIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class ReplacementOfferTest {

    private val prepId = "PREP_dbd54e211aa0456fb3a680e9cbba20d5"

    private fun cmd(action: String, payload: String, target: String? = prepId) = PendingCommand(
        messageId = "example-delayed-bag", action = action, targetId = target, payload = payload, operatorId = "OP-1",
    )

    private val captureCmd by lazy { cmd("capture", ContractFixtures.text("general_delayed_sealed_request.json")) }
    private val confirmCmd = cmd("confirm", "{\"action\":\"confirm\",\"targetId\":\"$prepId\"}")
    private val prepareCmd = cmd("prepare", "{\"action\":\"prepare\",\"targetId\":\"1\",\"mixCount\":1}", target = "1")

    private fun resolved(c: PendingCommand, outcome: RecoveryOutcome) =
        RecoveryResult.Resolved(c, outcome, "", Rev2Snapshot())

    @Test
    fun `a not_executed capture becomes a capture offer with the original code and quantity`() {
        val notice = resolved(captureCmd, RecoveryOutcome.NotExecuted)
        val offer = ReplacementOffer.from(notice) as ReplacementOffer.Capture
        assertEquals(notice, offer.notice)
        assertEquals(prepId, offer.prepId)
        assertEquals("TAG-RESIN", offer.intent.code)
        assertEquals(0, BigDecimal("10").compareTo(offer.intent.quantity))
    }

    @Test
    fun `a not_executed confirm becomes a confirm offer`() {
        val notice = resolved(confirmCmd, RecoveryOutcome.NotExecuted)
        assertEquals(ReplacementOffer.Confirm(notice, prepId), ReplacementOffer.from(notice))
    }

    @Test
    fun `committed and rejected never become offers`() {
        for (c in listOf(captureCmd, confirmCmd)) {
            assertNull(ReplacementOffer.from(resolved(c, RecoveryOutcome.Committed)))
            assertNull(ReplacementOffer.from(resolved(c, RecoveryOutcome.Rejected)))
            assertNull(ReplacementOffer.from(RecoveryResult.StillUnresolved(c, "x")))
            assertNull(ReplacementOffer.from(RecoveryResult.NeedsManager(c, "x")))
            assertNull(ReplacementOffer.from(RecoveryResult.OtherOperator(c)))
        }
    }

    @Test
    fun `a not_executed prepare is not an offer but opens its job`() {
        val notice = resolved(prepareCmd, RecoveryOutcome.NotExecuted)
        assertNull(ReplacementOffer.from(notice))
        assertEquals(NoticeTarget.Job("1"), notice.openTarget())
    }

    @Test
    fun `a not_executed capture or confirm opens its PREP, other outcomes open nothing`() {
        assertEquals(NoticeTarget.Prep(prepId), resolved(captureCmd, RecoveryOutcome.NotExecuted).openTarget())
        assertEquals(NoticeTarget.Prep(prepId), resolved(confirmCmd, RecoveryOutcome.NotExecuted).openTarget())
        assertNull(resolved(captureCmd, RecoveryOutcome.Committed).openTarget())
        assertNull(resolved(prepareCmd, RecoveryOutcome.Rejected).openTarget())
        assertNull(RecoveryResult.StillUnresolved(captureCmd, "x").openTarget())
    }

    @Test
    fun `a capture whose payload cannot be read is not an offer`() {
        assertNull(ReplacementOffer.from(resolved(cmd("capture", "not json"), RecoveryOutcome.NotExecuted)))
        assertNull(ReplacementOffer.from(resolved(captureCmd.copy(targetId = null), RecoveryOutcome.NotExecuted)))
    }

    @Test
    fun `a sealed command becomes an offer without a notice`() {
        assertEquals(
            ReplacementOffer.Capture(null, prepId, CaptureIntent("TAG-RESIN", BigDecimal("10"))),
            ReplacementOffer.fromSealed(captureCmd),
        )
        assertEquals(ReplacementOffer.Confirm(null, prepId), ReplacementOffer.fromSealed(confirmCmd))
        assertNull(ReplacementOffer.fromSealed(prepareCmd))
        assertNull(ReplacementOffer.fromSealed(null))
    }
}
