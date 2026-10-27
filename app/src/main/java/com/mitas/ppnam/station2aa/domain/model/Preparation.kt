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
    /** Station 2's `isModified`: show the amber Modified label; never derived on the device. */
    val isModified: Boolean = false,
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
    val mixerId: String? = null,
    val productionId: String? = null,
    /** The active mix/production cycle (`CYC_…`); send it in `machine_finish`. */
    val cycleId: String? = null,
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
    val machines: List<Machine> = emptyList(),
    /** `data.preparations`: every preparation Station 2 listed (used to see which machines are busy). */
    val preparations: List<PrepSummary> = emptyList(),
    /**
     * Rajoo mode only (contract §5): the selected job's session state. Its cumulative full-job rows
     * are also presented as [preparation] (id = the JC), so the collect screen works unchanged.
     */
    val rajoo: RajooJob? = null,
)

/**
 * A Rajoo job card as Station 2 holds it (contract §5). Targets are the full Production Order
 * quantities; work happens in physical sessions on one machine, [activeSessionId] being the
 * `RAJ_…` id every capture, decision and finish must name.
 */
data class RajooJob(
    val id: String,
    val product: String,
    val unit: String,
    val overallQuantity: Double,
    /** The machine saved at lookup; start, finish and close all send this same code. */
    val machineId: String?,
    val activeSessionId: String?,
    val closed: Boolean,
    val collectionRevision: Int,
    /** The first session froze the Include/Exclude choices; later sessions send none. */
    val choicesFrozen: Boolean,
    val materials: List<PrepMaterial>,
) {
    val hasActiveSession: Boolean get() = !activeSessionId.isNullOrBlank()
    val allCollected: Boolean
        get() = materials.filterNot { it.excluded }.let { rows -> rows.isNotEmpty() && rows.all { it.isComplete } }
    val canClose: Boolean get() = !closed && !hasActiveSession && allCollected
}

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
