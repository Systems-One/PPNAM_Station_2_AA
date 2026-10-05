package com.mitas.ppnam.station2aa.data.mqtt.outbox

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandOutcomeTest {

    private fun rejected(code: String?) = MqttOutcome.Rejected<Unit>(null, code?.let(::ErrorCode), null)

    @Test
    fun `accepted and definite rejections settle`() {
        assertNull(unresolvedReasonOf(MqttOutcome.Accepted(Unit)))
        listOf(
            "rev2_rejected", "collection_revision_conflict", "action_not_allowed", "invalid_envelope",
            "password_field_forbidden", "client_upgrade_required", "receipt_sealed",
        ).forEach { assertNull(it, unresolvedReasonOf(rejected(it))) }
    }

    @Test
    fun `uncertain outcomes stay unresolved with the contract's next step`() {
        assertEquals(UnresolvedReason.RetryIdentical, unresolvedReasonOf(MqttOutcome.NoResponse(FailureKind.Timeout)))
        assertEquals(UnresolvedReason.RetryIdentical, unresolvedReasonOf(MqttOutcome.NoResponse(FailureKind.NotConnected)))
        assertEquals(UnresolvedReason.RetryIdentical, unresolvedReasonOf(MqttOutcome.NoResponse(FailureKind.MalformedResponse)))
        assertEquals(UnresolvedReason.RetryIdentical, unresolvedReasonOf(rejected("outcome_unconfirmed")))
        assertEquals(UnresolvedReason.LoginThenRecover, unresolvedReasonOf(rejected("operator_session_invalid")))
        assertEquals(UnresolvedReason.Recover, unresolvedReasonOf(rejected("message_id_conflict")))
        assertEquals(UnresolvedReason.ManagerReconcile, unresolvedReasonOf(rejected("receipt_owner_mismatch")))
        assertEquals(UnresolvedReason.ManagerReconcile, unresolvedReasonOf(rejected("receipt_recovery_unavailable")))
    }

    @Test
    fun `an unknown or missing error code is never assumed settled`() {
        assertEquals(UnresolvedReason.Recover, unresolvedReasonOf(rejected("some_future_code")))
        assertEquals(UnresolvedReason.Recover, unresolvedReasonOf(rejected(null)))
    }
}
