package com.mitas.ppnam.station2aa.domain.model

/**
 * rev2.1 session state: Station 2's `OperatorSessionState`, sent by name as
 * `session.sessionState` in `scram_proof_result`. Constant names match the wire values exactly.
 *
 * This state machine is entirely Station 2's. The client mirrors the value for display and reacts
 * to `operator_session_invalid`; it never drives the machine itself.
 */
enum class SessionState {
    /** Device is online and the session is in use. */
    Active,

    /** The device went offline. The session is preserved, not destroyed — any valid request resumes it. */
    Suspended,

    /** Terminal: logged out, replaced by a newer login, or hit sessionExpiresAtUtc. */
    Closed;

    companion object {
        /**
         * Degrades an unknown or absent value to [Active] rather than locking an operator out of a
         * working session over an unrecognised string.
         */
        fun fromWire(raw: String?): SessionState =
            entries.firstOrNull { it.name == raw } ?: Active
    }
}
