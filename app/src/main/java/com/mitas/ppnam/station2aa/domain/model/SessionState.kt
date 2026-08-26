package com.mitas.ppnam.station2aa.domain.model

/**
 * Contract v3.0 session state. Constant names match the wire values exactly.
 *
 * This state machine is almost entirely Station 2's: presence drives Active/Suspended, and a valid
 * request on a Suspended session resumes it implicitly. The client mirrors the value for display
 * and reacts to `session_required`; it never drives the machine itself.
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
