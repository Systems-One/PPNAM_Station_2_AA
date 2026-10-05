package com.mitas.ppnam.station2aa.data.mqtt.outbox

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome

/** The result of a mutating command (contract §9). */
sealed interface CommandOutcome<out T> {
    /**
     * Station 2 decided ([MqttOutcome.Accepted] or a definite [MqttOutcome.Rejected]) and the
     * command has left the outbox — or [MqttOutcome.NoResponse] NotConnected, meaning nothing was
     * published or persisted.
     */
    data class Settled<T>(val outcome: MqttOutcome<T>) : CommandOutcome<T>

    /** The outcome is unknown; [command] stays in the outbox. Never replace it with a new message id. */
    data class Unresolved<T>(
        val command: PendingCommand,
        val reason: UnresolvedReason,
        /** The snapshot, if the reply carried one. */
        val body: T?,
        val operatorMessage: String?,
    ) : CommandOutcome<T>
}

enum class UnresolvedReason {
    /** No reply / `outcome_unconfirmed`: republish the identical bytes while the session lasts. */
    RetryIdentical,

    /** `operator_session_invalid`: the same operator signs in again, then `recover`. */
    LoginThenRecover,

    /** `message_id_conflict` or an unrecognised failure: `recover` tells the truth. */
    Recover,

    /** `receipt_owner_mismatch` / `receipt_recovery_unavailable`: a manager reconciles. */
    ManagerReconcile,
}

private val DEFINITE = setOf(
    ErrorCode.REV2_REJECTED,
    ErrorCode.COLLECTION_REVISION_CONFLICT,
    ErrorCode.ACTION_NOT_ALLOWED,
    ErrorCode.INVALID_ENVELOPE,
    ErrorCode.PASSWORD_FIELD_FORBIDDEN,
    ErrorCode.CLIENT_UPGRADE_REQUIRED,
    ErrorCode.RECEIPT_SEALED,
)

/** Null when the outcome is settled. Branches on `error` only — never `nextAction` or prose. */
fun unresolvedReasonOf(outcome: MqttOutcome<*>): UnresolvedReason? = when (outcome) {
    is MqttOutcome.Accepted -> null
    is MqttOutcome.NoResponse -> UnresolvedReason.RetryIdentical
    is MqttOutcome.Rejected -> when (outcome.error) {
        in DEFINITE -> null
        ErrorCode.OUTCOME_UNCONFIRMED -> UnresolvedReason.RetryIdentical
        ErrorCode.OPERATOR_SESSION_INVALID -> UnresolvedReason.LoginThenRecover
        ErrorCode.RECEIPT_OWNER_MISMATCH, ErrorCode.RECEIPT_RECOVERY_UNAVAILABLE -> UnresolvedReason.ManagerReconcile
        else -> UnresolvedReason.Recover
    }
}
