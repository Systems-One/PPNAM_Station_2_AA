package com.mitas.ppnam.station2aa.ui.preparation

import com.mitas.ppnam.station2aa.domain.model.CollectionExceptionRecord
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.ui.util.formatStationDateTime
import java.math.BigDecimal

/** Exact plain notation: never `1E+1`, never a Double (unlike the job-detail `formatQuantity`). */
internal fun BigDecimal.plainText(): String =
    if (signum() == 0) "0" else stripTrailingZeros().toPlainString()

private fun String.withUnit(unit: String): String = if (unit.isBlank()) this else "$this $unit"

/** The progress text under a material's name on the PREP screen. */
internal fun PrepMaterial.progressLine(): String {
    if (excluded) return "excluded — original ${(originalRequired ?: required).plainText().withUnit(unit)}"
    val of = "${collected.plainText()} of ${required.plainText().withUnit(unit)}"
    if (isAdded) return "added by operator · $of"
    val tail = if (isComplete) "complete" else "${remaining.plainText().withUnit(unit)} to go"
    return "$of · $tail"
}

/** "65cf…" short form of an operator or manager id. */
private fun String.shortId(): String = if (length <= 4) this else take(4) + "…"

/** The lines shown for one exception record: who and when, what, why (if given), and review status. */
internal fun CollectionExceptionRecord.lines(): List<String> = buildList {
    add("${formatStationDateTime(timestampUtc)} · operator ${operatorId.shortId()} · $kind")
    add(details)
    if (reason.isNotBlank()) add(reason)
    add(
        if (reviewedBy != null && reviewedAtUtc != null) {
            "Reviewed by ${reviewedBy.shortId()} on ${formatStationDateTime(reviewedAtUtc)}"
        } else {
            "Open"
        }
    )
}

/** `PREP_…last 6`, for the title. */
internal fun shortPrepId(prepId: String): String = "PREP_…${prepId.takeLast(6)}"
