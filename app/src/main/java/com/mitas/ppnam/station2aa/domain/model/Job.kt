package com.mitas.ppnam.station2aa.domain.model

import java.time.Instant

/** One row of the General job list. */
data class JobSummary(
    val jobCard: String,
    val product: String,
    val requiredMixes: Int,
    val closed: Boolean,
    val mixProgress: MixProgress? = null,
)

/**
 * One BOM material. Quantities are for display: the server owns every calculation. The only thing
 * derived here is [remaining], with the server's own formula; the `remaining` Station 2 sends
 * alongside is ignored.
 */
data class JobMaterial(
    val code: String,
    val name: String,
    val unit: String,
    val perMix: Double,
    val required: Double,
    val collected: Double,
    val excluded: Boolean,
    val originalRequired: Double? = null,
) {
    val remaining: Double get() = maxOf(0.0, required - collected)
}

data class JobPreparation(
    val id: String,
    val mixCount: Int,
    val mixed: Int,
    val produced: Int,
    val stage: String,
)

data class JobDetail(
    val jobCard: String,
    val product: String,
    val unit: String,
    val outputPerMix: Double,
    val requiredMixes: Int,
    val allocatedMixes: Int,
    val capturedAtUtc: Instant?,
    val closed: Boolean,
    val materials: List<JobMaterial>,
    val preparations: List<JobPreparation>,
    val mixProgress: MixProgress? = null,
)

data class JobLookupSnapshot(
    val jobs: List<JobSummary>,
    val detail: JobDetail?,
)

/** Contract §8.1 job totals — the same projection the desktop shows. Server-computed; never derived here. */
data class MixProgress(
    val requiredMixes: Int,
    val allocatedMixes: Int,
    val activeMixes: Int,
    val availableToPrepareMixes: Int,
    val remainingToFinishMixes: Int,
    val collectedMixes: Int,
    val confirmedMixes: Int,
    val mixedMixes: Int,
    val producedMixes: Int,
    val activePreparations: List<ActivePreparation>,
)

/** A saved preparation still awaiting production finish — resume it by `read` with [id]. */
data class ActivePreparation(
    val id: String,
    val mixCount: Int,
    val stage: String,
    val mixed: Int,
    val produced: Int,
    val remainingToFinishMixes: Int,
)
