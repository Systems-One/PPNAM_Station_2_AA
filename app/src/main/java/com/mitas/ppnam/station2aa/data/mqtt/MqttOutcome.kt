package com.mitas.ppnam.station2aa.data.mqtt

/**
 * The result of one rev2.1 request/response exchange.
 *
 * `Rejected.body` is the reply's `data` when Station 2 sent one: a `rev2_rejected` General reply
 * still carries the full refreshed snapshot, and discarding it would force a redundant read.
 * Envelope-level rejections (`invalid_envelope`, `operator_session_invalid`, …) carry no `data`,
 * hence nullable.
 *
 * `NoResponse` means Station 2 never answered — distinct from a decision it actually made.
 */
sealed interface MqttOutcome<out T> {

    data class Accepted<T>(val body: T) : MqttOutcome<T>

    data class Rejected<T>(
        val body: T?,
        val error: ErrorCode?,
        /** rev2.1 `operatorMessage`; null when blank. Show it, never parse it. */
        val operatorMessage: String?,
    ) : MqttOutcome<T>

    data class NoResponse(val kind: FailureKind) : MqttOutcome<Nothing>
}

enum class FailureKind {
    /** Published, but no matching response arrived within the timeout and retry budget. */
    Timeout,

    /** Not connected to the broker, or the publish itself failed. */
    NotConnected,

    /** A response arrived but could not be parsed. */
    MalformedResponse,
}
