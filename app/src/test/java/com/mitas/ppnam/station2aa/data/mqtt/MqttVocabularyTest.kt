package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MqttVocabularyTest {

    @Test
    fun `every error code carries its exact rev2_1 wire value`() {
        mapOf(
            ErrorCode.INVALID_ENVELOPE to "invalid_envelope",
            ErrorCode.PASSWORD_FIELD_FORBIDDEN to "password_field_forbidden",
            ErrorCode.OPERATOR_SESSION_INVALID to "operator_session_invalid",
            ErrorCode.ACTION_NOT_ALLOWED to "action_not_allowed",
            ErrorCode.MESSAGE_ID_CONFLICT to "message_id_conflict",
            ErrorCode.REV2_REJECTED to "rev2_rejected",
            ErrorCode.CLIENT_UPGRADE_REQUIRED to "client_upgrade_required",
            ErrorCode.OUTCOME_UNCONFIRMED to "outcome_unconfirmed",
            ErrorCode.AUTHENTICATION_FAILED to "authentication_failed",
            ErrorCode.PURPOSE_NOT_ENABLED to "purpose_not_enabled",
        ).forEach { (code, wire) -> assertEquals(wire, code.raw) }
    }

    @Test
    fun `an unknown code passes through intact`() {
        assertEquals("some_future_code", ErrorCode("some_future_code").raw)
    }

    @Test
    fun `codes compare by value`() {
        assertEquals(ErrorCode.REV2_REJECTED, ErrorCode("rev2_rejected"))
        assertNotEquals(ErrorCode.REV2_REJECTED, ErrorCode("REV2_REJECTED"))
    }
}
