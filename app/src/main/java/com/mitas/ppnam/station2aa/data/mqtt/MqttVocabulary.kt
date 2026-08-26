package com.mitas.ppnam.station2aa.data.mqtt

/**
 * Contract v4.1 `errorCode`. A value class rather than an enum: codes are shared across message
 * families and message-specific codes may also arrive. An unknown code must pass through intact
 * rather than fail the parse.
 *
 * 4.1 promises these are "stable lowercase symbolic" values — backend issue B5 was a GUID arriving
 * here instead, which is why nothing downstream may assume a code it doesn't recognise is a bug.
 */
@JvmInline
value class ErrorCode(val raw: String) {
    companion object {
        val INVALID_JSON = ErrorCode("invalid_json")
        val INVALID_ENVELOPE = ErrorCode("invalid_envelope")
        val UNSUPPORTED_SCHEMA = ErrorCode("unsupported_schema")
        val DEVICE_MISMATCH = ErrorCode("device_mismatch")
        val DEVICE_NOT_CONFIGURED = ErrorCode("device_not_configured")
        val MESSAGE_EXPIRED = ErrorCode("message_expired")
        val SESSION_REQUIRED = ErrorCode("session_required")
        val PERMISSION_DENIED = ErrorCode("permission_denied")
        val NOT_FOUND = ErrorCode("not_found")
        val STATE_CONFLICT = ErrorCode("state_conflict")
        val VALIDATION_FAILED = ErrorCode("validation_failed")
        val MESSAGE_ID_REUSED = ErrorCode("message_id_reused")
        val SERVICE_UNAVAILABLE = ErrorCode("service_unavailable")

        // v4.0 §10 — stable Mixing codes.
        val CLIENT_UPGRADE_REQUIRED = ErrorCode("client_upgrade_required")
        val INVALID_MIXING_AREA = ErrorCode("invalid_mixing_area")
        val UNKNOWN_OR_DISABLED_EQUIPMENT = ErrorCode("unknown_or_disabled_equipment")
        val EQUIPMENT_IN_USE = ErrorCode("equipment_in_use")
        val CYCLE_MISMATCH = ErrorCode("cycle_mismatch")
        val SOURCE_NOT_FOUND = ErrorCode("source_not_found")
        val SOURCE_NOT_READY = ErrorCode("source_not_ready")
        val SOURCE_ALREADY_ASSIGNED = ErrorCode("source_already_assigned")
        val INVALID_ROUTE = ErrorCode("invalid_route")
        val INVALID_LAYER_INPUTS = ErrorCode("invalid_layer_inputs")

        // v4.1 authentication. The app can only provoke this by sending a `password` or
        // `managerPassword` property on a 4.1 message — i.e. it is a build defect, not an
        // operator error, and must never be shown as "wrong password".
        val PLAINTEXT_CREDENTIALS_FORBIDDEN = ErrorCode("plaintext_credentials_forbidden")

        // v4.1 keyset paging. Discard accumulated pages and re-request page one.
        val PAGE_CURSOR_STALE = ErrorCode("page_cursor_stale")

        val DESTINATION_BUSY = ErrorCode("destination_busy")

        // JC-driven Mixing (2026-07-28). Mixing is driven by a completed collection, its job
        // card, equipment scans and server-issued cycle IDs — there are no plans to violate.
        val COLLECTION_NOT_READY = ErrorCode("collection_not_ready")
        val COLLECTION_ALREADY_MIXED = ErrorCode("collection_already_mixed")
        val ROUTE_REQUIRED = ErrorCode("route_required")
        val WRONG_SCAN_SEQUENCE = ErrorCode("wrong_scan_sequence")
        val INVALID_DESTINATION = ErrorCode("invalid_destination")
        /** Main output may never be allocated to a Rajoo machine. Always rejected server-side. */
        val RAJOO_DESTINATION_FORBIDDEN = ErrorCode("rajoo_destination_forbidden")
        val JANDI_DRUM_REQUIRED = ErrorCode("jandi_drum_required")
        val JANDI_DRUM_BUSY = ErrorCode("jandi_drum_busy")
        val JANDI_MAIN_MIX_REQUIRED = ErrorCode("jandi_main_mix_required")
        /** A JANDI 4 start named a Main mixer code that resolves to more than one eligible mix. */
        val AMBIGUOUS_MAIN_MIX = ErrorCode("ambiguous_main_mix")
        val AUTHORIZATION_REQUIRED = ErrorCode("authorization_required")
        val AUTHORIZATION_EXPIRED = ErrorCode("authorization_expired")

        // v4.1 Station 3 master-batch capture.
        val STATION3_UNAVAILABLE = ErrorCode("station3_unavailable")
        val MASTER_BATCH_LABEL_INVALID = ErrorCode("master_batch_label_invalid")
        val MASTER_BATCH_MAPPING_MISSING = ErrorCode("master_batch_mapping_missing")
        val UNSUPPORTED_MASTER_BATCH_UOM = ErrorCode("unsupported_master_batch_uom")
        val MASTER_BATCH_ALREADY_COLLECTED = ErrorCode("master_batch_already_collected")
        val SOURCE_BARCODE_REQUIRED = ErrorCode("source_barcode_required")
        val CONFLICTING_SOURCE_BARCODES = ErrorCode("conflicting_source_barcodes")
    }
}

/**
 * Contract v4.1 `nextAction`. Guidance for the scanner UI, never authorization. An empty value
 * means "no forced navigation".
 */
@JvmInline
value class NextAction(val raw: String) {
    companion object {
        val NONE = NextAction("")
        val LOGIN = NextAction("login")
        val SCAN_JOB_CARD = NextAction("scan_job_card")
        val ACTIVE_JOB_CARDS = NextAction("active_job_cards")
        val SCAN_INGREDIENT = NextAction("scan_ingredient")
        val RECOVER_HOLDING = NextAction("recover_holding")
        val RETRY_WITH_MANAGER_APPROVAL = NextAction("retry_with_manager_approval")
        val SCAN_SAME_MACHINE_TO_FINISH = NextAction("scan_same_machine_to_finish")
        val UPGRADE_READER_FOR_MIXING = NextAction("upgrade_reader_for_mixing")

        // v4.1 collection next actions (§ customer-confirmed extension).
        val CONTINUE_COLLECTING = NextAction("continue_collecting")
        val AWAIT_MANAGER_APPROVAL = NextAction("await_manager_approval")
        val RESCAN_APPROVED_MATERIAL = NextAction("rescan_approved_material")

        // v4.1 active-jobs invalidation push.
        val REFRESH_ACTIVE_JOBS = NextAction("refresh_active_jobs")

        // JC-driven Mixing (2026-07-28). Every value is a plain constant — the parameterised
        // `scan_reserved_mixer:` form went with the plans.
        val OPEN_MIXING = NextAction("open_mixing")
        val SELECT_COLLECTION = NextAction("select_collection")
        val SELECT_JANDI_ROUTE = NextAction("select_jandi_route")
        val SCAN_JANDI_DRUM_TO_START = NextAction("scan_jandi_drum_to_start")
        val SCAN_JANDI_DRUM_TO_FINISH = NextAction("scan_jandi_drum_to_finish")
        val SELECT_MAIN_DESTINATION = NextAction("select_main_destination")
        val SCAN_DESTINATION_TO_START = NextAction("scan_destination_to_start")
        val SELECT_JANDI4_MAIN_SOURCE = NextAction("select_jandi4_main_source")
        val SCAN_JANDI4_TO_START = NextAction("scan_jandi4_to_start")
        val SCAN_ADDITIONAL_RAJOO_LAYER_OR_FINISH_ACTIVE_LAYER =
            NextAction("scan_additional_rajoo_layer_or_finish_active_layer")
        val REFRESH_MIXING_OVERVIEW = NextAction("refresh_mixing_overview")
        val COMPLETED = NextAction("completed")
    }
}
