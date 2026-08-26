package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * Contract v4.1 authentication.
 *
 * There is no plaintext login any more. Station 2 rejects any 4.1 JSON object containing a
 * `password` or `managerPassword` property with `plaintext_credentials_forbidden`, whatever the
 * message type — so those payloads are not merely unused here, they must not exist.
 *
 * Both operator login and manager authorization run the same SCRAM-SHA-256 challenge/proof, told
 * apart by `purpose`. Message-specific fields only; the transport injects the envelope.
 */

/** Purposes for a SCRAM exchange (`purpose` on both start and proof). */
object ScramPurpose {
    const val LOGIN = "login"
    const val MANAGER_ACTION = "manager_action"
}

/**
 * `scram_start_requested`.
 *
 * The scope fields are empty for a login and mandatory for a manager action: the token Station 2
 * issues is bound to this exact device, [actionTarget] and [managerAction], and cannot be moved to
 * another. They are sent as `""` rather than omitted because the contract's own example shows
 * them present-and-empty for login.
 */
data class ScramStartPayload(
    val username: String,
    val clientNonce: String,
    val purpose: String,
    val actionTarget: String = "",
    val managerAction: String = "",
)

/**
 * `scram_challenge`. Valid for 60 seconds, one use, bound to this device and purpose.
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
    val purpose: String,
    val actionTarget: String = "",
    val managerAction: String = "",
)

/**
 * Response to `scram_proof_requested`.
 *
 * For [ScramPurpose.LOGIN] this carries the operator context and session (the same shape
 * `operator_context` always had). For [ScramPurpose.MANAGER_ACTION] it carries
 * [authorizationToken] instead — single-use, 60 seconds, scoped to one device/target/action.
 *
 * [serverSignature] must be validated before either is trusted.
 */
data class ScramProofResponse(
    val serverSignature: String = "",
    val authorizationToken: String? = null,
    val authorizationExpiresAtUtc: String? = null,
    val approverUserId: String? = null,
    val approverDisplayName: String? = null,
    val approverRole: String? = null,
    // Login-purpose fields, identical to the operator_context shape.
    val operatorSessionId: String = "",
    val operatorId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList(),
    val sessionState: String? = null,
    val sessionExpiresAtUtc: String? = null,
)

/** Badge login carries no secret, so it survives the 4.1 cutover unchanged. */
data class BadgeLoginPayload(
    val badgeTag: String,
)

/**
 * Response to `login_requested` (badge) and `reader_logout_requested`.
 *
 * Note this DTO reads `operatorSessionId`, which is an envelope field rather than a body field —
 * the one deliberate overlap in the codebase. Login is where the session is issued, so the use case
 * has no other way to obtain it, and Gson parses envelope and body from the same flat JSON object.
 *
 * `role` is informational only. Nothing in the contract gates on it — see the privileged-actions
 * rules, which authorise on the approver's allowedActions, never on a role.
 */
data class OperatorContextResponse(
    val operatorSessionId: String = "",
    val operatorId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList(),
    val sessionState: String? = null,
    val sessionExpiresAtUtc: String? = null,
)

/**
 * The manager actions that can be authorized, with the `actionTarget` prefix each one requires.
 * Contract v4.1 §6 table — the target must name the exact entity, e.g. `Cycle:CYC_000042`.
 */
enum class ManagerAction(val wireValue: String, val targetPrefix: String) {
    ForceClose("machine_force_close", "Cycle"),
    CancelCollection("ingredient_collection_cancel", "IngredientCollection"),
    ApproveShortBag("ingredient_approve_short_bag", "ShortBag"),
    ApproveOverride("ingredient_approve_override", "IngredientScan"),
    ;

    /** Builds the scoped `actionTarget`, e.g. `Cycle:CYC_000042`. */
    fun targetFor(id: String): String = "$targetPrefix:$id"
}
