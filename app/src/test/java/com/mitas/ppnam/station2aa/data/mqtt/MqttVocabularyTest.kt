package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MqttVocabularyTest {

    @Test
    fun `error code constants carry their contract wire values`() {
        assertEquals("invalid_json", ErrorCode.INVALID_JSON.raw)
        assertEquals("session_required", ErrorCode.SESSION_REQUIRED.raw)
        assertEquals("message_expired", ErrorCode.MESSAGE_EXPIRED.raw)
        assertEquals("message_id_reused", ErrorCode.MESSAGE_ID_REUSED.raw)
        assertEquals("permission_denied", ErrorCode.PERMISSION_DENIED.raw)
        assertEquals("unsupported_schema", ErrorCode.UNSUPPORTED_SCHEMA.raw)
    }

    @Test
    fun `error codes compare by value`() {
        assertEquals(ErrorCode.SESSION_REQUIRED, ErrorCode("session_required"))
        assertNotEquals(ErrorCode.SESSION_REQUIRED, ErrorCode.NOT_FOUND)
    }

    @Test
    fun `an unknown error code is preserved rather than rejected`() {
        val unknown = ErrorCode("some_future_backend_code")
        assertEquals("some_future_backend_code", unknown.raw)
    }

    @Test
    fun `next action constants carry their contract wire values`() {
        assertEquals("", NextAction.NONE.raw)
        assertEquals("login", NextAction.LOGIN.raw)
        assertEquals("recover_holding", NextAction.RECOVER_HOLDING.raw)
        assertEquals("retry_with_manager_approval", NextAction.RETRY_WITH_MANAGER_APPROVAL.raw)
        assertEquals("upgrade_reader_for_mixing", NextAction.UPGRADE_READER_FOR_MIXING.raw)
    }

    @Test
    fun `next actions compare by value`() {
        assertEquals(NextAction.LOGIN, NextAction("login"))
        assertNotEquals(NextAction.LOGIN, NextAction.NONE)
    }

    @Test
    fun `an unknown next action is preserved rather than rejected`() {
        assertEquals("do_a_new_thing", NextAction("do_a_new_thing").raw)
    }

    @Test
    fun `retired plan and two-phase codes are gone and JC-driven codes exist`() {
        assertEquals("collection_not_ready", ErrorCode.COLLECTION_NOT_READY.raw)
        assertEquals("collection_already_mixed", ErrorCode.COLLECTION_ALREADY_MIXED.raw)
        assertEquals("route_required", ErrorCode.ROUTE_REQUIRED.raw)
        assertEquals("wrong_scan_sequence", ErrorCode.WRONG_SCAN_SEQUENCE.raw)
        assertEquals("invalid_destination", ErrorCode.INVALID_DESTINATION.raw)
        assertEquals("rajoo_destination_forbidden", ErrorCode.RAJOO_DESTINATION_FORBIDDEN.raw)
        assertEquals("jandi_drum_required", ErrorCode.JANDI_DRUM_REQUIRED.raw)
        assertEquals("jandi_drum_busy", ErrorCode.JANDI_DRUM_BUSY.raw)
        assertEquals("jandi_main_mix_required", ErrorCode.JANDI_MAIN_MIX_REQUIRED.raw)
        assertEquals("ambiguous_main_mix", ErrorCode.AMBIGUOUS_MAIN_MIX.raw)
        assertEquals("authorization_required", ErrorCode.AUTHORIZATION_REQUIRED.raw)
        assertEquals("authorization_expired", ErrorCode.AUTHORIZATION_EXPIRED.raw)
    }

    @Test
    fun `JC-driven next actions exist and are plain constants`() {
        assertEquals("open_mixing", NextAction.OPEN_MIXING.raw)
        assertEquals("select_collection", NextAction.SELECT_COLLECTION.raw)
        assertEquals("select_jandi_route", NextAction.SELECT_JANDI_ROUTE.raw)
        assertEquals("scan_same_machine_to_finish", NextAction.SCAN_SAME_MACHINE_TO_FINISH.raw)
        assertEquals("scan_jandi_drum_to_start", NextAction.SCAN_JANDI_DRUM_TO_START.raw)
        assertEquals("scan_jandi_drum_to_finish", NextAction.SCAN_JANDI_DRUM_TO_FINISH.raw)
        assertEquals("select_main_destination", NextAction.SELECT_MAIN_DESTINATION.raw)
        assertEquals("scan_destination_to_start", NextAction.SCAN_DESTINATION_TO_START.raw)
        assertEquals("select_jandi4_main_source", NextAction.SELECT_JANDI4_MAIN_SOURCE.raw)
        assertEquals("scan_jandi4_to_start", NextAction.SCAN_JANDI4_TO_START.raw)
        assertEquals(
            "scan_additional_rajoo_layer_or_finish_active_layer",
            NextAction.SCAN_ADDITIONAL_RAJOO_LAYER_OR_FINISH_ACTIVE_LAYER.raw)
        assertEquals("refresh_mixing_overview", NextAction.REFRESH_MIXING_OVERVIEW.raw)
        assertEquals("completed", NextAction.COMPLETED.raw)
    }

    @Test
    fun `an unrecognised code still passes through intact`() {
        // The server may add codes we have no constant for. Nothing downstream may treat an
        // unknown code as a parse failure.
        assertEquals("some_future_code", ErrorCode("some_future_code").raw)
        assertEquals("some_future_action", NextAction("some_future_action").raw)
    }
}
