package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Adjustment
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2IdentifyRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooAdjustIngredientRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooCaptureRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooCloseRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooFinishRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooLookupRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RajooStartRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.model.WireDecimal
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Rajoo family (contract §5) on `rev2_rajoo_requested`. Reads (`read`, `lookup`, `identify`)
 * are plain requests; every physical command goes through the outbox exactly like General, so a
 * lost reply is retried identically or recovered, never repeated.
 *
 * Snapshots come back as [com.mitas.ppnam.station2aa.domain.model.PrepSnapshot] with
 * [com.mitas.ppnam.station2aa.domain.model.PrepSnapshot.rajoo] set and the job's rows as its
 * `preparation`.
 */
@Singleton
class RajooUseCase @Inject constructor(
    private val mqtt: MqttRepository,
) {
    /** The Rajoo job list (no [targetId]) or one JC's full-job progress and session. */
    suspend fun read(targetId: String? = null): ReadResult =
        readLike(Rev2GeneralRequest(action = "read", targetId = targetId?.trim()?.takeIf { it.isNotEmpty() }))

    /** Loads [jobCard] for Rajoo on the scanned [machineCode]; a plain request, safe to repeat. */
    suspend fun lookup(jobCard: String, machineCode: String): ReadResult =
        readLike(Rev2RajooLookupRequest(jobCard.trim(), machineCode.trim()))

    /** Contract §4.1 in Rajoo: `data` is `{result, preparation:null, source}`; no job is returned. */
    suspend fun identify(jobCard: String, code: String): IdentifyResult =
        when (val o = mqtt.request(REQUEST, RESPONSE, Rev2IdentifyRequest(jobCard.trim(), code.trim()),
            Rev2Snapshot::class.java)) {
            is MqttOutcome.Accepted -> o.body.source?.toDomain()
                ?.let { IdentifyResult.Identified(it, o.body.toPrepSnapshot()) }
                ?: IdentifyResult.Failed("Station 2 identified nothing for this code", retryable = false)
            is MqttOutcome.Rejected -> {
                val codes = setOfNotNull(o.error?.raw, o.body?.result?.errorCode)
                val message = o.operatorMessage ?: o.body?.result?.message?.takeIf { it.isNotBlank() }
                if (codes.any { it in NOT_FOUND }) IdentifyResult.Unknown(message ?: "Station 2 doesn't know this pallet code.", null)
                else IdentifyResult.Failed(message ?: "Station 2 rejected the lookup", retryable = false)
            }
            is MqttOutcome.NoResponse -> IdentifyResult.Failed(o.kind.message(),
                retryable = o.kind == FailureKind.Timeout || o.kind == FailureKind.NotConnected)
        }

    /** `rajoo_start`. [choices] and [exceptionListRevision] only for the job's first session. */
    suspend fun start(jobCard: String, machineCode: String, exceptionListRevision: Int?,
                      choices: List<Rev2ChoiceAnswer>?): CollectionResult {
        require(machineCode.isNotBlank()) { "blank machine code" }
        return send(Rev2RajooStartRequest(jobCard.trim(), machineCode.trim(), exceptionListRevision, choices))
    }

    suspend fun capture(jobCard: String, code: String, sessionId: String, quantity: BigDecimal?): CollectionResult {
        require(code.isNotBlank()) { "blank source code" }
        require(sessionId.isNotBlank()) { "no active session" }
        require(quantity == null || quantity.signum() > 0) { "quantity must be positive" }
        return send(Rev2RajooCaptureRequest(jobCard.trim(), code.trim(), sessionId.trim(), quantity?.let(WireDecimal::of)))
    }

    /*
     * Contract §6 decisions, in the exact active session. [quantity] is always the new TOTAL for the
     * full job; none of them captures stock. A stale [collectionRevision] is a conflict — re-read.
     */

    /** Kind `Add`: the scanned source's material becomes a new full-job row. */
    suspend fun addIngredient(jobCard: String, sessionId: String, collectionRevision: Int, sourceCode: String,
                              quantity: BigDecimal, reason: String): CollectionResult {
        require(sourceCode.isNotBlank()) { "blank source code" }
        return adjust(jobCard, sessionId, collectionRevision, reason,
            Rev2Adjustment(Rev2Adjustment.KIND_ADD, sourceCode = sourceCode.trim(), targetQuantity = total(quantity)))
    }

    /** Kind `Substitute`: [materialCode] (uncaptured) is excluded and the source's material replaces it. */
    suspend fun substitute(jobCard: String, sessionId: String, collectionRevision: Int, materialCode: String,
                           sourceCode: String, quantity: BigDecimal, reason: String): CollectionResult {
        require(materialCode.isNotBlank()) { "blank material code" }
        require(sourceCode.isNotBlank()) { "blank source code" }
        return adjust(jobCard, sessionId, collectionRevision, reason,
            Rev2Adjustment(Rev2Adjustment.KIND_SUBSTITUTE, materialCode.trim(), sourceCode.trim(), total(quantity)))
    }

    /** Kind `Quantity`: Less or More ingredient — [quantity] is the row's new full-job total. */
    suspend fun changeQuantity(jobCard: String, sessionId: String, collectionRevision: Int, materialCode: String,
                               quantity: BigDecimal, reason: String): CollectionResult {
        require(materialCode.isNotBlank()) { "blank material code" }
        return adjust(jobCard, sessionId, collectionRevision, reason,
            Rev2Adjustment(Rev2Adjustment.KIND_QUANTITY, materialCode = materialCode.trim(), targetQuantity = total(quantity)))
    }

    /** Kind `Omit`: the uncaptured row's target becomes zero and it is excluded. */
    suspend fun omit(jobCard: String, sessionId: String, collectionRevision: Int, materialCode: String,
                     reason: String): CollectionResult {
        require(materialCode.isNotBlank()) { "blank material code" }
        return adjust(jobCard, sessionId, collectionRevision, reason,
            Rev2Adjustment(Rev2Adjustment.KIND_OMIT, materialCode = materialCode.trim()))
    }

    private suspend fun adjust(jobCard: String, sessionId: String, collectionRevision: Int, reason: String,
                               adjustment: Rev2Adjustment): CollectionResult {
        val trimmedReason = reason.trim()
        require(trimmedReason.length in 1..GeneralCollectionUseCase.ADJUST_REASON_MAX) { "reason must be 1..1000 characters" }
        require(sessionId.isNotBlank()) { "no active session" }
        return send(Rev2RajooAdjustIngredientRequest(jobCard.trim(), sessionId.trim(), collectionRevision, trimmedReason, adjustment))
    }

    private fun total(quantity: BigDecimal): BigDecimal {
        require(quantity.signum() > 0) { "quantity must be positive" }
        require(quantity <= MAX_TARGET) { "quantity must be at most 999999999" }
        return WireDecimal.of(quantity)
    }

    suspend fun finish(jobCard: String, machineCode: String, sessionId: String): CollectionResult =
        send(Rev2RajooFinishRequest(jobCard.trim(), machineCode.trim(), sessionId.trim()))

    suspend fun close(jobCard: String, machineCode: String): CollectionResult =
        send(Rev2RajooCloseRequest(jobCard.trim(), machineCode.trim()))

    suspend fun retry(command: PendingCommand): CollectionResult =
        mqtt.retryCommand(command, Rev2Snapshot::class.java).toCollectionResult()

    private suspend fun readLike(payload: Any): ReadResult =
        when (val o = mqtt.request(REQUEST, RESPONSE, payload, Rev2Snapshot::class.java)) {
            is MqttOutcome.Accepted -> ReadResult.Loaded(o.body.toPrepSnapshot())
            is MqttOutcome.Rejected -> ReadResult.Failed(o.operatorMessage ?: "Station 2 rejected the request",
                o.body?.toPrepSnapshot(), retryable = false)
            is MqttOutcome.NoResponse -> ReadResult.Failed(o.kind.message(), null,
                retryable = o.kind == FailureKind.Timeout || o.kind == FailureKind.NotConnected)
        }

    private suspend fun send(payload: Any): CollectionResult =
        mqtt.sendCommand(REQUEST, RESPONSE, payload, Rev2Snapshot::class.java).toCollectionResult()

    companion object {
        const val REQUEST = "rev2_rajoo_requested"
        const val RESPONSE = "rev2_rajoo_result"
        private val NOT_FOUND = setOf("pallet_not_found", "unknown_source")

        /** Contract §6: the largest `targetQuantity`, in the material unit. */
        val MAX_TARGET: BigDecimal = BigDecimal("999999999")
    }
}
