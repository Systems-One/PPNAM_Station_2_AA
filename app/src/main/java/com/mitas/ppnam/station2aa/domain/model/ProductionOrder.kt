package com.mitas.ppnam.station2aa.domain.model

data class ProductionOrder(
    val docNo: String,
    val collectionId: String = "",
    // The backflush BOM line (issueType "im_Backflush") represents the product being
    // made, not a component the operator scans — it's surfaced separately here rather
    // than in `lines`, and isn't always present in the response.
    val productBeingMade: String? = null,
    /** Collecting | ReadyForMixing | Mixing | Cancelled. */
    val collectionStatus: String = "",
    /** Station 2's own human-readable collection summary line. Mapped in sub-project 3's Task 2. */
    val summary: String = "",
    val lines: List<BomLine>
)

/**
 * A BOM line the operator collects.
 *
 * Identity is [lineNumber], NOT [itemCode] — the contract keeps duplicate SAP material rows separate
 * by line number, so two lines may legitimately share a material code.
 *
 * Bag fields are nullable because a bulk line has none. `null` means "bags are meaningless for this
 * material"; `0.0` means "zero bags". Conflating them makes a bulk line display "0 of 0 bags" and
 * risks treating it as bag-complete.
 */
data class BomLine(
    val lineNumber: Int,
    val itemCode: String,
    val itemName: String,
    val requiredQty: Double,
    val collectedQty: Double = 0.0,
    /** May exceed [collectedQty]: a scan inside tolerance records full weight, credits only what was required. */
    val weightReceived: Double = 0.0,
    val remainingQty: Double = 0.0,
    val availableQty: Double = 0.0,
    val uom: String = "",
    /** e.g. "25.000 kg". Null on a bulk line — see [isBagged]. */
    val bagSize: String? = null,
    val expectedBags: Double? = null,
    val scannedBags: Double? = null,
    val approvedExtraBags: Double? = null,
    val approvedShortBags: Double? = null,
    val remainingBags: Double? = null,
    val valid: Boolean = true,
    val reason: String? = null,
) {
    /** A bulk material has no bag size, so no bag arithmetic applies to it. */
    val isBagged: Boolean get() = bagSize != null

    val isFullyAllocated: Boolean get() = remainingQty <= 0.0

    /**
     * A bagged line needs both quantity and bags satisfied; a bulk line completes on quantity alone,
     * because its bag figures are absent rather than zero.
     */
    val isSatisfied: Boolean
        get() = isFullyAllocated && (!isBagged || (remainingBags ?: 0.0) <= 0.0)
}
