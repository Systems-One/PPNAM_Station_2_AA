package com.mitas.ppnam.station2aa.domain.model

import java.math.BigDecimal

/**
 * What Station 2 says is on a scanned pallet (`identify`, proposed). [bagSize] is null when
 * unconfigured (or not positive); [available] is the physical stock on the pallet when known.
 * [reason] explains an unusable pallet and is empty otherwise.
 */
data class SourceInfo(
    val code: String,
    val materialCode: String,
    val name: String,
    val unit: String,
    val bagSize: BigDecimal?,
    val available: BigDecimal?,
    val usable: Boolean,
    val reason: String,
)

/** Where an identified pallet's material sits in this PREP. */
sealed interface SourceFit {
    /** An outstanding effective row: collect it. */
    data class InMix(val material: PrepMaterial) : SourceFit
    /** An effective row that is already fully collected. */
    data class Complete(val material: PrepMaterial) : SourceFit
    /** An excluded row: can't be collected. */
    data class Excluded(val material: PrepMaterial) : SourceFit
    /** Not a row of this PREP: the operator may add it with a reason. */
    data object NotInMix : SourceFit
}

/** Matches [source]'s material code to this PREP's rows, ignoring case. */
fun PrepSnapshot.classify(source: SourceInfo): SourceFit {
    val rows = preparation?.materials.orEmpty().filter { it.code.equals(source.materialCode.trim(), ignoreCase = true) }
    val included = rows.filterNot { it.excluded }
    included.firstOrNull { !it.isComplete }?.let { return SourceFit.InMix(it) }
    included.firstOrNull()?.let { return SourceFit.Complete(it) }
    rows.firstOrNull()?.let { return SourceFit.Excluded(it) }
    return SourceFit.NotInMix
}
