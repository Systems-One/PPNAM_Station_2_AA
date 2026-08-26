package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ScramExchange
import com.mitas.ppnam.station2aa.data.mqtt.EmptyPayload
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.BadgeLoginPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramPurpose
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.SessionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import javax.inject.Inject

sealed class LoginMethod {
    data class Credentials(val username: String, val password: String) : LoginMethod()
    data class Badge(val badgeTag: String) : LoginMethod()
}

class AuthUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val sessionHolder: OperatorSessionHolder,
    private val scramExchange: ScramExchange,
) {

    /**
     * Contract v4.1: a credentials login is a SCRAM-SHA-256 exchange, not a password on the wire.
     * Badge login carries no secret and keeps the original single `login_requested` shape.
     */
    suspend fun login(method: LoginMethod): Result<OperatorSession> = when (method) {
        is LoginMethod.Credentials -> loginWithScram(method)
        is LoginMethod.Badge -> loginWithBadge(method)
    }

    private suspend fun loginWithScram(method: LoginMethod.Credentials): Result<OperatorSession> {
        val proof = scramExchange.authenticate(
            username = method.username,
            password = method.password,
            purpose = ScramPurpose.LOGIN,
        ).getOrElse { return Result.failure(it) }

        return buildSession(
            operatorSessionId = proof.operatorSessionId,
            operatorId = proof.operatorId,
            displayName = proof.displayName,
            role = proof.role,
            sessionState = proof.sessionState,
            sessionExpiresAtUtc = proof.sessionExpiresAtUtc,
            allowedActions = proof.allowedActions,
            allowedTabs = proof.allowedTabs,
        )
    }

    private suspend fun loginWithBadge(method: LoginMethod.Badge): Result<OperatorSession> {
        val outcome = mqttRepository.request(
            requestType = "login_requested",
            responseType = "operator_context",
            payload = BadgeLoginPayload(method.badgeTag),
            correlationKey = null,
            responseClass = OperatorContextResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> outcome.body.let { response ->
                buildSession(
                    operatorSessionId = response.operatorSessionId,
                    operatorId = response.operatorId,
                    displayName = response.displayName,
                    role = response.role,
                    sessionState = response.sessionState,
                    sessionExpiresAtUtc = response.sessionExpiresAtUtc,
                    allowedActions = response.allowedActions,
                    allowedTabs = response.allowedTabs,
                )
            }
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Login failed"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    private fun buildSession(
        operatorSessionId: String,
        operatorId: String?,
        displayName: String?,
        role: String?,
        sessionState: String?,
        sessionExpiresAtUtc: String?,
        allowedActions: List<String>,
        allowedTabs: List<String>,
    ): Result<OperatorSession> {
        val state = SessionState.fromWire(sessionState)
        return when {
            operatorSessionId.isBlank() ->
                Result.failure(Exception("Station 2 accepted the login but issued no session"))
            // Accepting an already-closed session would strand the operator in a UI that
            // rejects every action.
            state == SessionState.Closed ->
                Result.failure(Exception("Station 2 closed this session immediately"))
            else -> {
                val session = OperatorSession(
                    operatorSessionId = operatorSessionId,
                    operatorId = operatorId.orEmpty(),
                    operatorName = displayName.orEmpty(),
                    role = role.orEmpty(),
                    sessionState = state,
                    // A bad timestamp must not fail an otherwise valid login — expiry is
                    // display-only, and Station 2 enforces it regardless.
                    sessionExpiresAtUtc = sessionExpiresAtUtc?.let {
                        try { Instant.parse(it) } catch (e: Exception) { null }
                    },
                    allowedActions = allowedActions,
                    allowedTabs = allowedTabs,
                )
                sessionHolder.set(session)
                Result.success(session)
            }
        }
    }

    /**
     * Closes this handheld's session. The local session is cleared regardless of the outcome:
     * stranding an operator logged-in because the network blipped would be worse than a server-side
     * session that expires on its own at sessionExpiresAtUtc.
     */
    suspend fun logout(): Result<Unit> {
        mqttRepository.request(
            requestType = "reader_logout_requested",
            responseType = "operator_context",
            payload = EmptyPayload,
            correlationKey = null,
            responseClass = OperatorContextResponse::class.java,
        )
        sessionHolder.clear()
        return Result.success(Unit)
    }
}

internal fun FailureKind.message(): String = when (this) {
    FailureKind.NotConnected -> "Not connected to Station 2"
    FailureKind.Timeout -> "Station 2 did not respond"
    FailureKind.MalformedResponse -> "Station 2 sent an unreadable response"
}
