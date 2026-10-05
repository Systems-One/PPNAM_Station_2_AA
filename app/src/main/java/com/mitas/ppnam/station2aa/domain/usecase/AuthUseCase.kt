package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ScramExchange
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.BadgeLoginPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.LoginResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Session
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.SessionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import javax.inject.Inject

class AuthUseCase @Inject constructor(
    private val sessionHolder: OperatorSessionHolder,
    private val scramExchange: ScramExchange,
    private val mqttRepository: MqttRepository,
) {

    /** A SCRAM-SHA-256 exchange — the password never goes on the wire. */
    suspend fun login(username: String, password: String): Result<OperatorSession> {
        val proof = scramExchange.authenticate(username, password)
            .getOrElse { return Result.failure(it) }

        return acceptSession(proof.session)
    }

    /**
     * A badge (RFID card) login: one `login_requested` carrying the scanned tag. Station 2 resolves
     * the card against its own badges and then the cards Account Management issued, so a card
     * from the desk signs in here the way it does at Station 1. Nothing secret goes on the wire.
     */
    suspend fun loginWithBadge(badgeTag: String): Result<OperatorSession> {
        val tag = badgeTag.trim()
        if (tag.isEmpty()) return Result.failure(Exception(BADGE_REJECTED_MESSAGE))

        val outcome = mqttRepository.request(
            requestType = "login_requested",
            responseType = "login_result",
            payload = BadgeLoginPayload(badgeTag = tag),
            responseClass = LoginResultResponse::class.java,
        )
        val result = when (outcome) {
            is MqttOutcome.Accepted -> outcome.body
            is MqttOutcome.Rejected -> return Result.failure(Exception(outcome.badgeFailureMessage()))
            is MqttOutcome.NoResponse -> return Result.failure(Exception(outcome.kind.message()))
        }
        return acceptSession(result.session)
    }

    private fun acceptSession(wire: Rev2Session?): Result<OperatorSession> {
        if (wire == null) {
            return Result.failure(Exception("Station 2 accepted the login but issued no session"))
        }
        val state = SessionState.fromWire(wire.sessionState)
        return when {
            wire.sessionId.isBlank() ->
                Result.failure(Exception("Station 2 accepted the login but issued no session"))
            // Accepting an already-closed or inactive session would strand the operator in a UI
            // that rejects every action.
            state == SessionState.Closed || !wire.isActive ->
                Result.failure(Exception("Station 2 closed this session immediately"))
            else -> {
                val session = OperatorSession(
                    operatorSessionId = wire.sessionId,
                    operatorId = wire.operatorId,
                    operatorName = wire.displayName,
                    role = wire.role,
                    sessionState = state,
                    // A bad timestamp must not fail an otherwise valid login — expiry is
                    // display-only, and Station 2 enforces it regardless.
                    sessionExpiresAtUtc = wire.expiresAtUtc?.let {
                        try { Instant.parse(it) } catch (e: Exception) { null }
                    },
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

    companion object {
        /** Unknown here and at the desk, revoked, or the holder is inactive: Station 2 does not say which. */
        const val BADGE_REJECTED_MESSAGE = "This badge is not registered or is no longer active."
    }
}

private fun <T> MqttOutcome.Rejected<T>.badgeFailureMessage(): String = when (error) {
    ErrorCode.BADGE_REJECTED -> AuthUseCase.BADGE_REJECTED_MESSAGE
    ErrorCode.BADGE_REQUIRED, ErrorCode.LOGIN_METHOD_INVALID, ErrorCode.CLIENT_UPGRADE_REQUIRED ->
        "Station 2 does not accept this kind of sign-in. Update the app."
    else -> operatorMessage?.takeIf { it.isNotBlank() } ?: "Badge sign-in failed"
}

internal fun FailureKind.message(): String = when (this) {
    FailureKind.NotConnected -> "Not connected to Station 2"
    FailureKind.Timeout -> "Station 2 did not respond. Check the station and retry."
    FailureKind.MalformedResponse -> "Station 2 sent an unreadable response"
}
