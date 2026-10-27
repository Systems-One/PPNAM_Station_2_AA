package com.mitas.ppnam.station2aa.data.mqtt.dto

import java.math.BigDecimal

/**
 * rev2.1 workflow DTOs, contract revision 2026-10-21 (§8). General (`rev2_general_*`) and Rajoo
 * (`rev2_rajoo_*`) replies share one snapshot shape; [Rev2Snapshot.mode] says which.
 *
 * Every constructor parameter keeps a default: see [ResponseEnvelope] for why that is load-bearing.
 * Nullable fields default to null — WireJson prunes JSON nulls, so the default is what a null
 * becomes. Rev2SnapshotFixtureTest fails if Station 2 sends a field declared nowhere here.
 */

/** Request payload. Null fields are omitted on the wire. */
data class Rev2GeneralRequest(
    val action: String,
    /** `lookup` only: the Production Order number, digits only. */
    val jobCard: String? = null,
    /** `read` only: a JC or preparation id to include as detail. */
    val targetId: String? = null,
)

/** Contract §9: recover an unanswered command in the same family. Sent with a NEW messageId. */
data class Rev2RecoverRequest(
    val action: String = "recover",
    /** Optional selection; recovery uses the saved result target when present. */
    val targetId: String? = null,
    val originalMessageId: String,
    /** 64 uppercase hex: SHA-256 of the original request's exact UTF-8 bytes. */
    val originalRequestFingerprint: String,
)

data class Rev2Snapshot(
    val result: Rev2CommandResult? = null,
    val recovery: Rev2Recovery? = null,
    /** `General` or `Rajoo`. */
    val mode: String = "",
    val capabilities: Rev2Capabilities? = null,
    val jobs: List<Rev2JobSummary> = emptyList(),
    val job: Rev2Job? = null,
    val preparation: Rev2Preparation? = null,
    val preparations: List<Rev2Preparation> = emptyList(),
    val machines: List<Rev2Machine> = emptyList(),
    /** Null when Station 2 sent none: never echo a made-up revision back in `prepare`. */
    val exceptionListRevision: Int? = null,
    val ingredientExceptions: List<Rev2CatalogEntry> = emptyList(),
    val requiredIngredientChoices: List<Rev2CatalogEntry> = emptyList(),
    val collectionExceptions: List<Rev2CollectionException> = emptyList(),
    /** The subset of [collectionExceptions] created by this device's message (or the recovered original). */
    val commandExceptions: List<Rev2CollectionException> = emptyList(),
    /** `identify` only (proposed): the scanned pallet; null for an unknown code and every other action. */
    val source: Rev2Source? = null,
)

/** Proposed `identify` response `data.source`. Quantities are in [unit]. */
data class Rev2Source(
    /** The scanned code, echoed. */
    val code: String = "",
    val materialCode: String = "",
    val name: String = "",
    val unit: String = "",
    /** Configured bag quantity; null when unconfigured. */
    val bagSize: BigDecimal? = null,
    /** Physical stock on the pallet. */
    val available: BigDecimal? = null,
    /** Unblocked local Station 1 pallet in Holding/Mixing with fresh evidence. */
    val usable: Boolean = false,
    /** Readable reason when [usable] is false, else empty. */
    val reason: String = "",
)

data class Rev2CommandResult(
    val success: Boolean = false,
    val message: String = "",
    val targetId: String? = null,
    /** Server-owned `CYC_…`/`RAJ_…` id when the operation started one. */
    val cycleId: String? = null,
    /** Omitted when null; e.g. `receipt_sealed` inside a not_executed recovery. */
    val errorCode: String? = null,
)

data class Rev2Recovery(
    val originalMessageId: String = "",
    /** `committed`, `rejected` or `not_executed`. */
    val outcome: String = "",
    val result: Rev2CommandResult? = null,
)

data class Rev2Capabilities(
    val operatorIngredientDecisions: Boolean = false,
    val receiptRecovery: Boolean = false,
    val managerReviewDesktopOnly: Boolean = false,
    val jobMixProgress: Boolean = false,
    /** §3.1 (2026-10-16): Station 2 accepts `badge_login_requested`. */
    val androidBadgeLogin: Boolean = false,
)

data class Rev2JobSummary(
    val id: String = "",
    val product: String = "",
    val closed: Boolean = false,
    val requiredMixes: Int = 0,
    /** Same values as the selected job's; null for Rajoo. */
    val mixProgress: Rev2MixProgress? = null,
)

/** Contract §8.1. All counters are nonnegative; **Active** does not mean a mixer is running. */
data class Rev2MixProgress(
    val requiredMixes: Int = 0,
    val allocatedMixes: Int = 0,
    val activeMixes: Int = 0,
    val availableToPrepareMixes: Int = 0,
    val remainingToFinishMixes: Int = 0,
    val collectedMixes: Int = 0,
    val confirmedMixes: Int = 0,
    val mixedMixes: Int = 0,
    val producedMixes: Int = 0,
    val activePreparationCount: Int = 0,
    val activePreparations: List<Rev2ActivePreparation> = emptyList(),
)

data class Rev2ActivePreparation(
    val id: String = "",
    val jobId: String = "",
    val mixCount: Int = 0,
    val stage: String = "",
    val mixed: Int = 0,
    val produced: Int = 0,
    val remainingToFinishMixes: Int = 0,
    val collectionRevision: Int = 0,
    val startedAtUtc: String? = null,
    val startedBy: String? = null,
    val mixerId: String? = null,
    val productionId: String? = null,
    val cycleId: String? = null,
)

data class Rev2Job(
    val id: String = "",
    val product: String = "",
    val unit: String = "",
    val overallQuantity: Double = 0.0,
    val scopeQuantity: Double = 0.0,
    val sapCompletedAtLookup: Double = 0.0,
    val outputPerMix: Double = 0.0,
    val requiredMixes: Int = 0,
    /** Kept by Station 2 for compatibility; prefer [mixProgress]. */
    val allocatedMixes: Int = 0,
    val capturedAtUtc: String? = null,
    val closed: Boolean = false,
    val materials: List<Rev2Material> = emptyList(),
    val mode: String = "",
    /** Rajoo only: the active `RAJ_…` session. */
    val activeSessionId: String? = null,
    val rajooMachineId: String? = null,
    /** Rajoo first-session choices; null for General. */
    val ingredientChoices: List<Rev2IngredientChoice>? = null,
    val exceptionListRevision: Int? = null,
    /** Rajoo decision guard. General decisions use the PREPARATION's revision, not this. */
    val collectionRevision: Int = 0,
    /** Null for Rajoo. */
    val mixProgress: Rev2MixProgress? = null,
)

/**
 * `required` is the effective target. `remaining` is sent by Station 2 as
 * `max(0, required - collected)`; the domain layer derives the same value.
 */
data class Rev2Material(
    val code: String = "",
    val name: String = "",
    val unit: String = "",
    val perMix: BigDecimal = BigDecimal.ZERO,
    val required: BigDecimal = BigDecimal.ZERO,
    val collected: BigDecimal = BigDecimal.ZERO,
    /** Excluded (initial choice, omit, or substituted away): shown, never collected. */
    val excluded: Boolean = false,
    /** Original scope target; 0 for an operator-added row; null when a legacy baseline is unknown. */
    val originalRequired: BigDecimal? = null,
    val remaining: BigDecimal = BigDecimal.ZERO,
    /** §8 (16 Oct 2026): excluded, or the effective target differs from a known original. */
    val isModified: Boolean = false,
)

data class Rev2Preparation(
    val id: String = "",
    val jobId: String = "",
    val mixCount: Int = 0,
    val mixed: Int = 0,
    val produced: Int = 0,
    /** Enum name: Collecting, AwaitingConfirmation, ReadyForMixer, Mixing, … Completed, Cancelled. */
    val stage: String = "",
    val startedAtUtc: String? = null,
    /** Legacy evidence only; never send it. */
    val includeTackifier: Boolean? = null,
    val ingredientChoices: List<Rev2IngredientChoice> = emptyList(),
    val exceptionListRevision: Int? = null,
    /** The guard for General ingredient decisions. */
    val collectionRevision: Int = 0,
    val mixerId: String? = null,
    val productionId: String? = null,
    val cycleId: String? = null,
    val confirmedAtUtc: String? = null,
    val startedBy: String? = null,
    val materials: List<Rev2Material> = emptyList(),
)

data class Rev2Machine(
    val id: String = "",
    val name: String = "",
    val area: String = "",
    /** Blank until an Admin commissions it. */
    val code: String = "",
    val enabled: Boolean = false,
    /** `FixedPair`, `Main`, `Mixer6` or `Rajoo`. */
    val kind: String = "",
    val isProduction: Boolean = false,
    val pairId: String? = null,
    val destinations: List<String> = emptyList(),
)

data class Rev2IngredientChoice(
    val code: String = "",
    val include: Boolean = true,
)

/** A Settings exception-catalog row (§7). */
data class Rev2CatalogEntry(
    val code: String = "",
    val description: String = "",
    val note: String = "",
)

/** An operational collection exception (§6). Review fields are null while open. */
data class Rev2CollectionException(
    val id: String = "",
    val timestampUtc: String = "",
    val operatorId: String = "",
    val device: String = "",
    val messageId: String = "",
    val jobId: String = "",
    val batchId: String? = null,
    val sessionId: String? = null,
    val kind: String = "",
    val details: String = "",
    val reason: String = "",
    val beforeJson: String = "",
    val afterJson: String = "",
    val reviewedAtUtc: String? = null,
    val reviewedBy: String? = null,
    val reviewReason: String? = null,
)

/**
 * Contract §4/§7 `prepare`. [ingredientChoices] is never null: `[]` when no code needs a choice.
 * [exceptionListRevision] is the READ's top-level catalog revision (`data.exceptionListRevision`),
 * never the job's. A blank [reason] must be passed as null so it is omitted.
 */
data class Rev2PrepareRequest(
    val targetId: String,
    val mixCount: Int,
    val exceptionListRevision: Int?,
    val ingredientChoices: List<Rev2ChoiceAnswer>,
    val reason: String? = null,
    val action: String = "prepare",
)

/** One explicit Include/Exclude answer. No default: an unanswered choice must not compile. */
data class Rev2ChoiceAnswer(val code: String, val include: Boolean)

/**
 * Contract §4 `capture`: one physical bag ([quantity] null, so it is omitted) or one fraction.
 * [quantity] must already be [com.mitas.ppnam.station2aa.domain.model.WireDecimal]-normalised.
 */
data class Rev2CaptureRequest(
    val targetId: String,
    val code: String,
    val quantity: BigDecimal? = null,
    val action: String = "capture",
)

data class Rev2ConfirmRequest(
    val targetId: String,
    val action: String = "confirm",
)

/** Contract §4 `machine_start`: [code] is the scanned machine code, exactly as scanned. */
data class Rev2MachineStartRequest(
    val targetId: String,
    val code: String,
    val action: String = "machine_start",
)

/** Contract §4 `machine_finish`: [cycleId] is the PREP's current `cycleId` from the latest snapshot. */
data class Rev2MachineFinishRequest(
    val targetId: String,
    val code: String,
    val cycleId: String,
    val action: String = "machine_finish",
)

/**
 * Contract §4.1 `identify`: read-only, no receipt. A plain request like `read` — never outboxed, safe
 * to resend. [targetId] is the PREP (optional on the wire; this app always names one), [code] the
 * scanned pallet/label code.
 */
data class Rev2IdentifyRequest(
    val targetId: String,
    val code: String,
    val action: String = "identify",
)

/**
 * Contract §6 `adjust_ingredient` for a General PREP. [collectionRevision] is the PREPARATION's
 * current revision; [reason] is trimmed, 1–1000 characters.
 */
data class Rev2AdjustIngredientRequest(
    val targetId: String,
    val collectionRevision: Int,
    val reason: String,
    val adjustment: Rev2Adjustment,
    val action: String = "adjust_ingredient",
)

/**
 * Contract §6 `adjustment`. Unused fields stay `""` as in Station 2's own generated requests:
 * `Add` has no [materialCode]; `Quantity` and `Omit` have no [sourceCode]; `Omit` has no
 * [targetQuantity] (null is omitted from the wire). [targetQuantity] is the scope's new TOTAL
 * target and must already be [com.mitas.ppnam.station2aa.domain.model.WireDecimal]-normalised.
 */
data class Rev2Adjustment(
    val kind: String = KIND_ADD,
    val materialCode: String = "",
    val sourceCode: String = "",
    val targetQuantity: BigDecimal? = null,
) {
    companion object {
        const val KIND_ADD = "Add"
        const val KIND_SUBSTITUTE = "Substitute"
        const val KIND_QUANTITY = "Quantity"
        const val KIND_OMIT = "Omit"
    }
}
