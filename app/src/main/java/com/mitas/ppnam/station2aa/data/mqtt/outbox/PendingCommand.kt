package com.mitas.ppnam.station2aa.data.mqtt.outbox

enum class PendingStatus {
    /** Outcome unknown: retry identically, or sign in and recover. */
    Unresolved,

    /** Station 2 cannot attribute the receipt (or the saved copy is damaged): a manager reconciles. */
    ManagerReconcile,
}

/**
 * A mutation whose outcome is not yet known (contract §2/§9). [payload] is the exact JSON that was
 * published; a retry republishes exactly these bytes with the same [messageId], and `recover`
 * quotes [fingerprint]. Defaults exist only so Gson can construct it.
 */
data class PendingCommand(
    val messageId: String = "",
    /** The request family suffix, e.g. `rev2_general_requested`. */
    val requestType: String = "",
    val responseType: String = "",
    val action: String = "",
    val targetId: String? = null,
    val payload: String = "",
    val fingerprint: String = "",
    /** The server operator who sent it; only that operator may recover it. */
    val operatorId: String = "",
    /** The session it was sent with; an identical retry is only meaningful in that session. */
    val sessionId: String = "",
    val createdAtUtc: String = "",
    val status: PendingStatus = PendingStatus.Unresolved,
) {
    val payloadBytes: ByteArray get() = payload.toByteArray(Charsets.UTF_8)
}
