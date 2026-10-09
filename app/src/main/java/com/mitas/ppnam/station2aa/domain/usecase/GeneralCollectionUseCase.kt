package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2CaptureRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ConfirmRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2PrepareRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutcome
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.WireDecimal
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ReadResult {
    data class Loaded(val snapshot: PrepSnapshot) : ReadResult
    data class Failed(val message: String, val snapshot: PrepSnapshot?, val retryable: Boolean) : ReadResult
}

sealed interface CollectionResult {
    /** Station 2 did it; [snapshot] is the fresh saved state. */
    data class Done(val snapshot: PrepSnapshot) : CollectionResult
    /** Station 2 definitely did not do it (rev2_rejected, receipt_sealed, …). Show [message] and [snapshot]'s commandExceptions. */
    data class Rejected(val error: ErrorCode?, val message: String, val snapshot: PrepSnapshot?) : CollectionResult
    /** Outcome unknown: the command is in the outbox. Never send a new one for this intent. */
    data class Unresolved(val command: PendingCommand, val reason: UnresolvedReason, val message: String) : CollectionResult
    /** Nothing was published (not connected, or the outbox could not save). A new tap is a new intent. */
    data class NotSent(val message: String) : CollectionResult
    /** A retry found the command already settled elsewhere. Re-read. */
    data class AlreadySettled(val messageId: String) : CollectionResult
}

@Singleton
class GeneralCollectionUseCase @Inject constructor(
    private val mqtt: MqttRepository,
) {
    suspend fun read(targetId: String): ReadResult =
        when (val o = mqtt.request(REQUEST, RESPONSE, Rev2GeneralRequest(action = "read", targetId = targetId.trim()),
            Rev2Snapshot::class.java)) {
            is MqttOutcome.Accepted -> ReadResult.Loaded(o.body.toPrepSnapshot())
            is MqttOutcome.Rejected -> ReadResult.Failed(o.operatorMessage ?: "Station 2 rejected the read",
                o.body?.toPrepSnapshot(), retryable = false)
            is MqttOutcome.NoResponse -> ReadResult.Failed(o.kind.message(), null,
                retryable = o.kind == FailureKind.Timeout || o.kind == FailureKind.NotConnected)
        }

    suspend fun prepare(jobCard: String, mixCount: Int, exceptionListRevision: Int?,
                        answers: List<Rev2ChoiceAnswer>, reason: String?): CollectionResult =
        send(Rev2PrepareRequest(jobCard, mixCount, exceptionListRevision, answers,
            reason?.trim()?.takeIf { it.isNotEmpty() }?.take(PREPARE_REASON_MAX)))

    suspend fun capture(prepId: String, code: String, quantity: BigDecimal?): CollectionResult {
        require(code.isNotBlank()) { "blank source code" }
        require(quantity == null || quantity.signum() > 0) { "quantity must be positive" }
        return send(Rev2CaptureRequest(prepId, code.trim(), quantity?.let(WireDecimal::of)))
    }

    suspend fun confirm(prepId: String): CollectionResult = send(Rev2ConfirmRequest(prepId))

    suspend fun retry(command: PendingCommand): CollectionResult =
        mqtt.retryCommand(command, Rev2Snapshot::class.java).toCollectionResult()

    private suspend fun send(payload: Any): CollectionResult =
        mqtt.sendCommand(REQUEST, RESPONSE, payload, Rev2Snapshot::class.java).toCollectionResult()

    companion object {
        const val REQUEST = "rev2_general_requested"
        const val RESPONSE = "rev2_general_result"
    }
}

internal fun CommandOutcome<Rev2Snapshot>.toCollectionResult(): CollectionResult = when (this) {
    is CommandOutcome.Settled -> when (val o = outcome) {
        is MqttOutcome.Accepted -> CollectionResult.Done(o.body.toPrepSnapshot())
        is MqttOutcome.Rejected -> CollectionResult.Rejected(o.error, o.operatorMessage ?: rejectedText(o.error),
            o.body?.toPrepSnapshot())
        is MqttOutcome.NoResponse -> CollectionResult.NotSent("Not connected — nothing was sent")
    }
    is CommandOutcome.Unresolved -> CollectionResult.Unresolved(command, reason, unresolvedText(reason))
    is CommandOutcome.NotSaved -> CollectionResult.NotSent("The scanner couldn't save this, so nothing was sent. Try again.")
    is CommandOutcome.NoLongerPending -> CollectionResult.AlreadySettled(messageId)
}

private fun rejectedText(error: ErrorCode?): String = when (error) {
    ErrorCode.INVALID_ENVELOPE, ErrorCode.PASSWORD_FIELD_FORBIDDEN ->
        "Station 2 rejected the message format (${error.raw}). Report this."
    ErrorCode.ACTION_NOT_ALLOWED -> "Station 2 doesn't allow this here."
    ErrorCode.RECEIPT_SEALED -> "Station 2 never ran this, and it can no longer run."
    ErrorCode.CLIENT_UPGRADE_REQUIRED -> "This app needs updating before it can talk to Station 2"
    else -> "Station 2 rejected the request"
}

private fun unresolvedText(reason: UnresolvedReason): String = when (reason) {
    UnresolvedReason.RetryIdentical -> "Station 2 has not confirmed this yet. Don't repeat it — retry."
    UnresolvedReason.LoginThenRecover -> "Your session ended. Sign in again; the scanner will ask Station 2 what happened."
    UnresolvedReason.Recover -> "Station 2 couldn't match this command. Checking what happened…"
    UnresolvedReason.ManagerReconcile -> "A manager must reconcile this at the station before you scan again."
}
