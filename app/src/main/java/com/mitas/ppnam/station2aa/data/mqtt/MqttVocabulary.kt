package com.mitas.ppnam.station2aa.data.mqtt

/**
 * rev2.1 `error`. A value class rather than an enum: an unknown code must pass through intact
 * rather than fail the parse — the SCRAM service can return its own codes.
 *
 * There is deliberately no `NextAction` type. rev2.1's `nextAction` is an English sentence
 * ("Follow the saved job or preparation state."), not a code, so nothing may branch on it.
 */
@JvmInline
value class ErrorCode(val raw: String) {
    companion object {
        val INVALID_ENVELOPE = ErrorCode("invalid_envelope")
        /** A field name containing `password` was sent. A build defect, never a wrong password. */
        val PASSWORD_FIELD_FORBIDDEN = ErrorCode("password_field_forbidden")
        val OPERATOR_SESSION_INVALID = ErrorCode("operator_session_invalid")
        val ACTION_NOT_ALLOWED = ErrorCode("action_not_allowed")
        val MESSAGE_ID_CONFLICT = ErrorCode("message_id_conflict")
        /** A definite business rejection. `data` still carries the refreshed snapshot. */
        val REV2_REJECTED = ErrorCode("rev2_rejected")
        val CLIENT_UPGRADE_REQUIRED = ErrorCode("client_upgrade_required")
        /** Station 2 faulted mid-request. Retry the identical request with the same messageId. */
        val OUTCOME_UNCONFIRMED = ErrorCode("outcome_unconfirmed")
        val AUTHENTICATION_FAILED = ErrorCode("authentication_failed")
        val PURPOSE_NOT_ENABLED = ErrorCode("purpose_not_enabled")
        /** `login_requested` with a blank `badgeTag`. A build defect, never an operator's fault. */
        val BADGE_REQUIRED = ErrorCode("badge_required")
        /** The card is unknown here and in the fleet mirror, revoked, or its holder is inactive. */
        val BADGE_REJECTED = ErrorCode("badge_rejected")
        /** `login_requested` carried a `username`; badge login is badge-only. */
        val LOGIN_METHOD_INVALID = ErrorCode("login_method_invalid")
    }
}
