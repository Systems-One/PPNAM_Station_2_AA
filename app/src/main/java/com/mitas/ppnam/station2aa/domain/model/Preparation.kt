package com.mitas.ppnam.station2aa.domain.model

import com.google.gson.JsonParser
import java.math.BigDecimal

enum class PrepStage {
    Collecting, AwaitingConfirmation, ReadyForMixer, Mixing, ReadyForNextMix,
    ReadyForProduction, Producing, Completed, Cancelled, Unknown;

    companion object {
        fun fromWire(value: String): PrepStage = entries.firstOrNull { it.name == value } ?: Unknown
    }
}

/** One effective collection row (contract §8). Quantities are exact; compare with compareTo. */
data class PrepMaterial(
    val code: String,
    val name: String,
    val unit: String,
    val perMix: BigDecimal,
    val required: BigDecimal,
    val collected: BigDecimal,
    val excluded: Boolean,
    val originalRequired: BigDecimal?,
) {
    /** The server's own formula (Rev2MixingModels.cs:20). */
    val remaining: BigDecimal get() = (required - collected).max(BigDecimal.ZERO)
    val isComplete: Boolean get() = remaining.signum() == 0
    /** Added by an operator decision: not on the original BOM. */
    val isAdded: Boolean get() = originalRequired?.signum() == 0 && perMix.signum() == 0
    fun fits(quantity: BigDecimal): Boolean = quantity.signum() > 0 && quantity <= remaining
}

data class Preparation(
    val id: String,
    val jobId: String,
    val mixCount: Int,
    val mixed: Int,
    val produced: Int,
    val stage: PrepStage,
    val stageRaw: String,
    val collectionRevision: Int,
    val startedAtUtc: String?,
    val startedBy: String?,
    val confirmedAtUtc: String?,
    val materials: List<PrepMaterial>,
) {
    val allIncludedCollected: Boolean get() = materials.filterNot { it.excluded }.all { it.isComplete }
}

data class ChoicePrompt(val code: String, val description: String, val note: String)

data class CollectionExceptionRecord(
    val id: String,
    val timestampUtc: String,
    val operatorId: String,
    val device: String,
    val messageId: String,
    val jobId: String,
    val batchId: String?,
    val kind: String,
    val details: String,
    val reason: String,
    val reviewedAtUtc: String?,
    val reviewedBy: String?,
    val reviewReason: String?,
)

data class PrepSnapshot(
    val lookup: JobLookupSnapshot,
    val preparation: Preparation?,
    val requiredChoices: List<ChoicePrompt>,
    /** The catalog revision to echo in `prepare` (top-level `data.exceptionListRevision`). */
    val exceptionListRevision: Int?,
    val collectionExceptions: List<CollectionExceptionRecord>,
    val commandExceptions: List<CollectionExceptionRecord>,
    val resultTargetId: String?,
    val resultMessage: String,
)

/** What an outboxed `capture` asked for, read back from its exact saved JSON (for a replacement). */
data class CaptureIntent(val code: String, val quantity: BigDecimal?) {
    companion object {
        fun fromPayload(json: String): CaptureIntent? = runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj["action"]?.asString != "capture") return null
            val code = obj["code"]?.asString?.takeIf { it.isNotBlank() } ?: return null
            CaptureIntent(code, obj["quantity"]?.takeIf { it.isJsonPrimitive }?.asBigDecimal)
        }.getOrNull()
    }
}
