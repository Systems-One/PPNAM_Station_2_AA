package com.mitas.ppnam.station2aa.domain.model

import java.time.Instant

/** One row of the General job list. */
data class JobSummary(
    val jobCard: String,
    val product: String,
    val requiredMixes: Int,
    val closed: Boolean,
)

/**
 * One BOM material. Quantities are for display: the server owns every calculation, and the only
 * thing derived here is [remaining], because the server computes it but does not send it.
 */
data class JobMaterial(
    val code: String,
    val name: String,
    val unit: String,
    val perMix: Double,
    val required: Double,
    val collected: Double,
    val excluded: Boolean,
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
)

data class JobLookupSnapshot(
    val jobs: List<JobSummary>,
    val detail: JobDetail?,
)
