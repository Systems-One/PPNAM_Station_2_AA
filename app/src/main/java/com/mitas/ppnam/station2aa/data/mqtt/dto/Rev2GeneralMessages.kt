package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * rev2.1 General Mixing: `rev2_general_requested` → `rev2_general_result`.
 *
 * Only `read` and `lookup` exist in this app. Every reply's `data` is the whole General snapshot
 * (see `Rev2ScannerProcessor.ProcessAsync` in the Station 2 repo), so one DTO serves both. Fields
 * the slice does not use — `machines`, `ingredientExceptions`, `requiredIngredientChoices`,
 * `exceptionListRevision`, `preparation`, the Rajoo fields — are not declared; Gson ignores them.
 *
 * Every constructor parameter keeps a default: see [ResponseEnvelope] for why that is load-bearing.
 */

/** Request payload. Null fields are omitted on the wire. */
data class Rev2GeneralRequest(
    val action: String,
    /** `lookup` only: the Production Order number, digits only. */
    val jobCard: String? = null,
    /** `read` only: a JC or preparation id to include as detail. */
    val targetId: String? = null,
)

data class Rev2GeneralSnapshot(
    val result: Rev2CommandResult? = null,
    val jobs: List<Rev2JobSummary> = emptyList(),
    val job: Rev2Job? = null,
    val preparations: List<Rev2Preparation> = emptyList(),
)

data class Rev2CommandResult(
    val success: Boolean = false,
    val message: String = "",
    val targetId: String? = null,
)

data class Rev2JobSummary(
    val id: String = "",
    val product: String = "",
    val closed: Boolean = false,
    val requiredMixes: Int = 0,
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
    val allocatedMixes: Int = 0,
    val capturedAtUtc: String? = null,
    val closed: Boolean = false,
    val materials: List<Rev2Material> = emptyList(),
)

/**
 * Station 2 also sends `remaining` (a getter-only property, which System.Text.Json serializes). It
 * is not declared: the device derives it with the same formula, `max(0, required - collected)`, and
 * ignores the wire value.
 */
data class Rev2Material(
    val code: String = "",
    val name: String = "",
    val unit: String = "",
    val perMix: Double = 0.0,
    val required: Double = 0.0,
    val collected: Double = 0.0,
    /** Excluded by an ingredient-exception choice: shown, never collected, `required` is 0. */
    val excluded: Boolean = false,
)

data class Rev2Preparation(
    val id: String = "",
    val jobId: String = "",
    val mixCount: Int = 0,
    val mixed: Int = 0,
    val produced: Int = 0,
    /** Enum name: Collecting, AwaitingConfirmation, ReadyForMixer, Mixing, … Completed. */
    val stage: String = "",
    val startedAtUtc: String? = null,
)
