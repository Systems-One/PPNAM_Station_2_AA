package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * The envelope every contract v4.1 response carries. Parsed from the same flat JSON object as the
 * message-specific body, so the transport can route and classify a response without knowing its type.
 *
 * `inResponseToMessageId` is the only correct way to match a *direct* response to its request.
 * `correlationKey` is a trace-grouping key that several in-flight messages deliberately share.
 * 4.1 also introduces uncorrelated server pushes (`active_job_cards_invalidated`), which carry no
 * `inResponseToMessageId` at all — see [com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImpl].
 *
 * Every constructor parameter here must keep a default value. Kotlin only emits the no-arg
 * constructor Gson needs when every parameter has a default; drop one default and Kotlin silently
 * stops emitting that constructor, so Gson falls back to `UnsafeAllocator` and every field
 * deserializes to null regardless of its declared default and non-null type — with no compile error.
 */
data class ResponseEnvelope(
    val messageId: String = "",
    val inResponseToMessageId: String = "",
    val schemaVersion: String = "",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String? = null,
    val accepted: Boolean = false,
    /**
     * Compatibility mirror of [errorMessage] during the 4.1 rollout. The contract is explicit that
     * it "mirrors errorMessage during rollout", so it is the fallback, not the source of truth —
     * backend issue B9 was exactly this field carrying text while `errorMessage` was absent.
     */
    val reason: String? = null,
    /** Canonical human-readable failure text in 4.1. Prefer this over [reason]. */
    val errorMessage: String? = null,
    /** Stable lowercase symbolic code. Never a GUID in 4.1 — that was backend issue B5. */
    val errorCode: String? = null,
    /** Server-side correlation id for a logged exception. Show it, don't parse it. */
    val exceptionId: String? = null,
    /** Field-level validation detail, keyed by request property name. */
    val fieldErrors: Map<String, String>? = null,
    val serverReceivedAtUtc: String? = null,
    /** 4.1's authoritative send time. Legacy [timestampUtc] is documented as equal to this. */
    val serverSentAtUtc: String? = null,
    val processingDurationMs: Long? = null,
    val nextAction: String? = null,
) {
    /**
     * The failure text to show a human. 4.1 promises `errorMessage`; `reason` is the rollout
     * mirror and the only thing 4.0 sent. Blank is treated as absent — an empty string is not a
     * message, and falling through to the next source beats rendering "".
     */
    val displayMessage: String?
        get() = errorMessage?.takeIf { it.isNotBlank() } ?: reason?.takeIf { it.isNotBlank() }
}
