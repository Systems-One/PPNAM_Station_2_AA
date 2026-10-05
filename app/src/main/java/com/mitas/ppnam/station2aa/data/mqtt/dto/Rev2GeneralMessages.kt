package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * rev2.1 workflow DTOs, contract revision 2026-10-01 (§8). General (`rev2_general_*`) and Rajoo
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
    val exceptionListRevision: Int = 0,
    val ingredientExceptions: List<Rev2CatalogEntry> = emptyList(),
    val requiredIngredientChoices: List<Rev2CatalogEntry> = emptyList(),
    val collectionExceptions: List<Rev2CollectionException> = emptyList(),
    /** The subset of [collectionExceptions] created by this device's message (or the recovered original). */
    val commandExceptions: List<Rev2CollectionException> = emptyList(),
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
    val perMix: Double = 0.0,
    val required: Double = 0.0,
    val collected: Double = 0.0,
    /** Excluded (initial choice, omit, or substituted away): shown, never collected. */
    val excluded: Boolean = false,
    /** Original scope target; 0 for an operator-added row; null when a legacy baseline is unknown. */
    val originalRequired: Double? = null,
    val remaining: Double = 0.0,
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
