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
     * Contract §3.1 Android card login: one `badge_login_requested` carrying the complete card EPC.
     * Station 2 resolves the card from its own tables or the fleet mirror. Nothing secret goes on the wire.
     *
     * An unanswered tap keeps its bytes and message id, so tapping the same card again is the exact
     * retry the contract asks for and returns the session the first tap created. If that session has
     * since become unusable Station 2 answers `operator_session_invalid`, and this tap signs in afresh
     * under a new id.
     */
    suspend fun loginWithBadge(badgeTag: String): Result<OperatorSession> {
        val tag = badgeTag.trim()
        if (tag.isEmpty()) return Result.failure(Exception(BADGE_REJECTED_MESSAGE))

        var outcome = badgeLogin(tag)
        if ((outcome as? MqttOutcome.Rejected)?.error == ErrorCode.OPERATOR_SESSION_INVALID) {
            outcome = badgeLogin(tag)   // the reply settled the old id; this is a new one
        }
        val result = when (outcome) {
            is MqttOutcome.Accepted -> outcome.body
            is MqttOutcome.Rejected -> return Result.failure(Exception(outcome.badgeFailureMessage()))
            is MqttOutcome.NoResponse -> return Result.failure(Exception(outcome.kind.message()))
        }
        return acceptSession(result.session)
    }

    private suspend fun badgeLogin(tag: String): MqttOutcome<LoginResultResponse> =
        mqttRepository.requestRetainingUnanswered(
            requestType = BADGE_LOGIN_REQUESTED,
            responseType = BADGE_LOGIN_RESULT,
            payload = BadgeLoginPayload(badgeTag = tag),
            responseClass = LoginResultResponse::class.java,
        )

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
        const val BADGE_LOGIN_REQUESTED = "badge_login_requested"
        const val BADGE_LOGIN_RESULT = "badge_login_result"
        /** Unknown here and at the desk, revoked, or the holder is inactive: Station 2 does not say which. */
        const val BADGE_REJECTED_MESSAGE = "This badge is not registered or is no longer active."
    }
}

private fun <T> MqttOutcome.Rejected<T>.badgeFailureMessage(): String = when (error) {
    ErrorCode.BADGE_REJECTED -> AuthUseCase.BADGE_REJECTED_MESSAGE
    ErrorCode.INVALID_ENVELOPE, ErrorCode.CLIENT_UPGRADE_REQUIRED ->
        "Station 2 does not accept this kind of sign-in. Update the app."
    // A retry whose bytes differ from the original tap: a build defect, but a fresh tap gets a new id.
    ErrorCode.MESSAGE_ID_CONFLICT -> "Card sign-in was interrupted. Tap the card again."
    else -> operatorMessage?.takeIf { it.isNotBlank() } ?: "Badge sign-in failed"
}

internal fun FailureKind.message(): String = when (this) {
    FailureKind.NotConnected -> "Not connected to Station 2"
    FailureKind.Timeout -> "Station 2 did not respond. Check the station and retry."
    FailureKind.MalformedResponse -> "Station 2 sent an unreadable response"
}
