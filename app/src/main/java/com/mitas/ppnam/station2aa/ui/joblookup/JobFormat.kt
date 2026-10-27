package com.mitas.ppnam.station2aa.ui.joblookup

import com.mitas.ppnam.station2aa.domain.model.ActivePreparation
import com.mitas.ppnam.station2aa.domain.model.JobMaterial
import com.mitas.ppnam.station2aa.domain.model.JobPreparation
import com.mitas.ppnam.station2aa.domain.model.MixProgress
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A quantity for a handheld: at most three decimals, trailing zeros dropped, never scientific
 * notation. SAP quantities arrive as doubles with float noise (`557.0490000001`), and a bare
 * toString() would put that — or `1.25E7` — on a shop-floor screen.
 */
internal fun formatQuantity(value: Double): String {
    val text = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return if (text == "-0") "0" else text
}

private fun withUnit(value: Double, unit: String): String =
    if (unit.isBlank()) formatQuantity(value) else "${formatQuantity(value)} $unit"

/**
 * A General job material is the frozen job basis (contract §8): Station 2 always sends `collected`
 * 0 there and tracks collection on each preparation, so only the plan is shown.
 */
internal fun JobMaterial.quantityLine(): String {
    val perMixText = "${withUnit(perMix, unit)} per mix"
    if (excluded) return "$perMixText · excluded from this job"
    return "$perMixText · ${withUnit(required, unit)} for the job"
}

internal fun JobPreparation.summaryLine(): String =
    "$mixCount ${if (mixCount == 1) "mix" else "mixes"} · $mixed mixed · $produced produced · $stage"

/** Contract §8.1's required labels, in its order. */
internal fun MixProgress.countsLine(): String =
    "Required $requiredMixes · Active $activeMixes · Available to prepare $availableToPrepareMixes · Finished $producedMixes"

internal fun ActivePreparation.summaryLine(): String =
    "$mixCount ${if (mixCount == 1) "mix" else "mixes"} · $stage · $mixed mixed · $produced finished"
