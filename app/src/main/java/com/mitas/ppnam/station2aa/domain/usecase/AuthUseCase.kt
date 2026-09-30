package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ScramExchange
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.SessionState
import java.time.Instant
import javax.inject.Inject

class AuthUseCase @Inject constructor(
    private val sessionHolder: OperatorSessionHolder,
    private val scramExchange: ScramExchange,
) {

    /** A SCRAM-SHA-256 exchange — the password never goes on the wire. */
    suspend fun login(username: String, password: String): Result<OperatorSession> {
        val proof = scramExchange.authenticate(username, password)
            .getOrElse { return Result.failure(it) }

        val state = SessionState.fromWire(proof.sessionState)
        return when {
            proof.operatorSessionId.isBlank() ->
                Result.failure(Exception("Station 2 accepted the login but issued no session"))
            // Accepting an already-closed session would strand the operator in a UI that
            // rejects every action.
            state == SessionState.Closed ->
                Result.failure(Exception("Station 2 closed this session immediately"))
            else -> {
                val session = OperatorSession(
                    operatorSessionId = proof.operatorSessionId,
                    operatorId = proof.operatorId.orEmpty(),
                    operatorName = proof.displayName.orEmpty(),
                    role = proof.role.orEmpty(),
                    sessionState = state,
                    // A bad timestamp must not fail an otherwise valid login — expiry is
                    // display-only, and Station 2 enforces it regardless.
                    sessionExpiresAtUtc = proof.sessionExpiresAtUtc?.let {
                        try { Instant.parse(it) } catch (e: Exception) { null }
                    },
                    allowedActions = proof.allowedActions,
                    allowedTabs = proof.allowedTabs,
                )
                sessionHolder.set(session)
                Result.success(session)
            }
        }
    }

    /**
     * rev2.1 has no logout message, so this only forgets the session on this device. Station 2's
     * copy expires on its own at `expiresAtUtc`.
     */
    suspend fun logout(): Result<Unit> {
        sessionHolder.clear()
        return Result.success(Unit)
    }
}

internal fun FailureKind.message(): String = when (this) {
    FailureKind.NotConnected -> "Not connected to Station 2"
    FailureKind.Timeout -> "Station 2 did not respond"
    FailureKind.MalformedResponse -> "Station 2 sent an unreadable response"
}
