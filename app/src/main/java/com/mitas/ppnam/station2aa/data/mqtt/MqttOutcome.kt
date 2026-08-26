package com.mitas.ppnam.station2aa.data.mqtt

/**
 * The result of one contract v4.0 request/response exchange.
 *
 * `Rejected` deliberately carries the typed body: the contract guarantees every response topic has
 * one stable payload shape, so a rejected ingredient scan still returns the full refreshed
 * ingredients[], and a rejected machine-cycle start still returns the refreshed `areaStatus`.
 * Discarding that would force a redundant refresh.
 *
 * `NoResponse` means Station 2 never answered — distinct from a decision it actually made, and an
 * operator must treat it differently.
 *
 * Note `Accepted` means Station 2 processed the request, not that the answer was favourable: a
 * pallet lookup returning found=false is Accepted.
 */
sealed interface MqttOutcome<out T> {

    data class Accepted<T>(
        val body: T,
        val nextAction: NextAction,
    ) : MqttOutcome<T>

    data class Rejected<T>(
        val body: T,
        val errorCode: ErrorCode?,
        /** 4.1's canonical `errorMessage`, falling back to the rollout `reason` mirror. */
        val reason: String?,
        val nextAction: NextAction,
        /** Station 2's id for the logged exception. Worth showing when support has to trace it. */
        val exceptionId: String? = null,
        /** Field-level validation detail keyed by request property name; empty when not supplied. */
        val fieldErrors: Map<String, String> = emptyMap(),
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
