package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode

/**
 * The envelope of every rev2.1 message Station 2 sends, parsed from the same JSON object as the
 * body (which lives under `data` and is parsed separately by the transport).
 *
 * Direct replies carry `inResponseToMessageId` and NO `messageId` of their own. Server pushes
 * (`active_job_cards_invalidated`) are the reverse: their own `messageId`, no correlation id, and
 * `mode`/`reason` instead of a result.
 *
 * Every constructor parameter here must keep a default value. Kotlin only emits the no-arg
 * constructor Gson needs when every parameter has a default; drop one and Gson falls back to
 * `UnsafeAllocator`, so every field deserializes to null regardless of its declared type.
 */
data class ResponseEnvelope(
    val schemaVersion: String = "",
    val deviceId: String = "",
    val inResponseToMessageId: String = "",
    val receivedAtUtc: String? = null,
    val sentAtUtc: String? = null,
    val durationMs: Double? = null,
    val success: Boolean = false,
    /** Stable lowercase code; `""` on success. */
    val error: String = "",
    val operatorMessage: String = "",
    /** Prose guidance, not a code. Never branch on it. */
    val nextAction: String = "",
    // Server pushes only.
    val messageId: String = "",
    val timestampUtc: String = "",
    val mode: String = "",
    val reason: String = "",
) {
    val errorCode: ErrorCode? get() = error.takeIf { it.isNotBlank() }?.let(::ErrorCode)

    val displayMessage: String? get() = operatorMessage.takeIf { it.isNotBlank() }
}
