package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * rev2.1 authentication: SCRAM-SHA-256 login only.
 *
 * There is no plaintext login, no badge login and no manager authorization — Station 2 answers any
 * `purpose` other than `login` with `purpose_not_enabled`, and rejects any field whose name
 * contains `password` with `password_field_forbidden`. Message-specific fields only; the transport
 * injects the envelope.
 */

/** `scram_start_requested`. */
data class ScramStartPayload(
    val username: String,
    val clientNonce: String,
    val purpose: String = "login",
)

/**
 * `scram_challenge`. Valid for 60 seconds, one use, bound to this device.
 *
 * [serverNonce] is the *combined* nonce and must begin with the client nonce we sent;
 * [serverFirstMessage] is the exact string that goes into the AuthMessage — it is reproduced
 * verbatim rather than rebuilt from the parts, since any difference in spelling breaks the proof.
 */
data class ScramChallengeResponse(
    val challengeId: String = "",
    val serverNonce: String = "",
    val salt: String = "",
    val iterations: Int = 0,
    val serverFirstMessage: String = "",
    val expiresAtUtc: String? = null,
)

/** `scram_proof_requested`. */
data class ScramProofPayload(
    val challengeId: String,
    val clientFinalWithoutProof: String,
    val clientProof: String,
    val purpose: String = "login",
)

/**
 * `scram_proof_result` `data`. [serverSignature] must be validated before [session] is trusted.
 *
 * rev2.1 sends no `allowedTabs`/`allowedActions` and never will (customer-confirmed 2026-09-30):
 * Station 2 authorizes every request by its `sessionId`, and nothing on the device gates on role.
 */
data class ScramProofResponse(
    val serverSignature: String = "",
    val session: Rev2Session? = null,
)

/** Station 2's `StationOperatorSession`, camelCase. Enum values arrive as names (`Worker`, `Active`). */
data class Rev2Session(
    val sessionId: String = "",
    val operatorId: String = "",
    val displayName: String = "",
    /** Display and audit only — never branch on it. */
    val role: String = "",
    val expiresAtUtc: String? = null,
    val sessionState: String? = null,
    val isActive: Boolean = false,
)
