package com.mitas.ppnam.station2aa.data.auth

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramChallengeResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramProofPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramProofResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramStartPayload
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.message
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one contract v4.1 SCRAM-SHA-256 challenge/proof round trip.
 *
 * rev2.1 enables SCRAM for operator login only. Keeping
 * it in one place is also what makes the security properties checkable in one place: the password
 * is never stored, never logged, and never leaves this function's frame, and the server signature
 * is always verified before the caller is handed anything.
 */
@Singleton
class ScramExchange @Inject constructor(
    private val mqttRepository: MqttRepository,
) {

    /**
     */
    suspend fun authenticate(
        username: String,
        password: String,
    ): Result<ScramProofResponse> {
        val clientNonce = ScramCrypto.generateClientNonce()

        val startOutcome = mqttRepository.request(
            requestType = "scram_start_requested",
            responseType = "scram_challenge",
            payload = ScramStartPayload(
                username = username,
                clientNonce = clientNonce,
            ),
            correlationKey = null,
            responseClass = ScramChallengeResponse::class.java,
        )

        val challenge = when (startOutcome) {
            is MqttOutcome.Accepted -> startOutcome.body
            is MqttOutcome.Rejected -> return Result.failure(Exception(startOutcome.authFailureMessage()))
            is MqttOutcome.NoResponse -> return Result.failure(Exception(startOutcome.kind.message()))
        }

        if (challenge.challengeId.isBlank() || challenge.serverFirstMessage.isBlank()) {
            return Result.failure(Exception("Station 2 sent an incomplete authentication challenge"))
        }
        if (challenge.iterations <= 0) {
            return Result.failure(Exception("Station 2 sent an invalid authentication challenge"))
        }

        // RFC 5802: the combined nonce must extend the one we sent. Anything else means this
        // challenge is not an answer to our start — refuse rather than proving against it.
        val serverNonce = ScramCrypto.parseServerNonce(challenge.serverFirstMessage, clientNonce)
            ?: challenge.serverNonce.takeIf {
                it.startsWith(clientNonce) && it.length > clientNonce.length
            }
            ?: return Result.failure(
                Exception("Station 2's authentication challenge did not match this device's request")
            )

        val clientFinalWithoutProof = ScramCrypto.clientFinalWithoutProof(serverNonce)
        val proof = try {
            ScramCrypto.computeProof(
                password = password,
                saltBase64 = challenge.salt,
                iterations = challenge.iterations,
                authMessage = ScramCrypto.authMessage(
                    clientFirstBare = ScramCrypto.clientFirstBare(username, clientNonce),
                    serverFirstMessage = challenge.serverFirstMessage,
                    clientFinalWithoutProof = clientFinalWithoutProof,
                ),
            )
        } catch (e: Exception) {
            // A malformed salt lands here. Deliberately not echoing the exception text, which
            // would put challenge material into a user-facing string.
            return Result.failure(Exception("Station 2 sent an unusable authentication challenge"))
        }

        val proofOutcome = mqttRepository.request(
            requestType = "scram_proof_requested",
            responseType = "operator_context",
            payload = ScramProofPayload(
                challengeId = challenge.challengeId,
                clientFinalWithoutProof = clientFinalWithoutProof,
                clientProof = proof.clientProofBase64,
            ),
            correlationKey = null,
            responseClass = ScramProofResponse::class.java,
        )

        val result = when (proofOutcome) {
            is MqttOutcome.Accepted -> proofOutcome.body
            is MqttOutcome.Rejected -> return Result.failure(Exception(proofOutcome.authFailureMessage()))
            is MqttOutcome.NoResponse -> return Result.failure(Exception(proofOutcome.kind.message()))
        }

        // Mutual authentication. Without this check an attacker who can answer on the response
        // topic could hand us a session or an authorization token we never actually proved for,
        // which is precisely what SCRAM's server signature exists to prevent. The contract makes
        // it an explicit step: "Validate the returned serverSignature before trusting the session."
        if (!ScramCrypto.verifyServerSignature(proof.expectedServerSignatureBase64, result.serverSignature)) {
            return Result.failure(
                Exception("Station 2 failed authentication verification — this response is not trusted")
            )
        }

        return Result.success(result)
    }
}

/**
 * `plaintext_credentials_forbidden` means this build sent a `password`/`managerPassword` property
 * on a 4.1 message. That is a build defect, not a bad password, and showing it as "login failed"
 * would send an operator round a loop retyping a password that was never the problem.
 */
private fun <T> MqttOutcome.Rejected<T>.authFailureMessage(): String = when (errorCode) {
    ErrorCode.PLAINTEXT_CREDENTIALS_FORBIDDEN ->
        "This app build sent credentials in a form Station 2 no longer accepts. Update the app."
    else -> reason ?: "Authentication failed"
}
