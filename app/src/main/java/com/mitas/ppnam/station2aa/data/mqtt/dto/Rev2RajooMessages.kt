package com.mitas.ppnam.station2aa.data.mqtt.dto

import java.math.BigDecimal

/*
 * rev2.1 Rajoo family (`rev2_rajoo_requested` -> `rev2_rajoo_result`), contract §5. Replies are the
 * same [Rev2Snapshot] as General with `mode:"Rajoo"`: `preparation` is null and the selected `job`
 * carries the cumulative full-job targets and the active session. `read`, `identify` and `recover`
 * reuse [Rev2GeneralRequest], [Rev2IdentifyRequest] and [Rev2RecoverRequest] (same fields).
 */

/** `lookup`: [jobCard] digits only; [code] the scanned, enabled Rajoo machine. */
data class Rev2RajooLookupRequest(
    val jobCard: String,
    val code: String,
    val action: String = "lookup",
)

/**
 * `rajoo_start`: [targetId] the JC, [code] the same machine. The FIRST session also sends the
 * read's [exceptionListRevision] and explicit [ingredientChoices]; later sessions send neither
 * (null is omitted from the wire).
 */
data class Rev2RajooStartRequest(
    val targetId: String,
    val code: String,
    val exceptionListRevision: Int? = null,
    val ingredientChoices: List<Rev2ChoiceAnswer>? = null,
    val reason: String? = null,
    val action: String = "rajoo_start",
)

/** `capture` against the cumulative full-job target, in the exact active session [cycleId]. */
data class Rev2RajooCaptureRequest(
    val targetId: String,
    val code: String,
    val cycleId: String,
    val quantity: BigDecimal? = null,
    val action: String = "capture",
)

/**
 * `adjust_ingredient` for a Rajoo JC (Add, Substitute, Quantity or Omit): [collectionRevision] is
 * the JOB's, [cycleId] the exact active session; `targetQuantity` is the total for the full job.
 */
data class Rev2RajooAdjustIngredientRequest(
    val targetId: String,
    val cycleId: String,
    val collectionRevision: Int,
    val reason: String,
    val adjustment: Rev2Adjustment,
    val action: String = "adjust_ingredient",
)

/** `rajoo_finish`: same machine [code], exact active [cycleId]. Captured quantities stay saved. */
data class Rev2RajooFinishRequest(
    val targetId: String,
    val code: String,
    val cycleId: String,
    val action: String = "rajoo_finish",
)

/** `rajoo_close` (21 Oct 2026): the same configured Rajoo machine [code]; no job-end label. */
data class Rev2RajooCloseRequest(
    val targetId: String,
    val code: String,
    val action: String = "rajoo_close",
)
