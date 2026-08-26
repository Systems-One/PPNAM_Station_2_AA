package com.mitas.ppnam.station2aa.domain.model

import com.mitas.ppnam.station2aa.data.mqtt.NextAction

sealed class IngredientScanOutcome {
    /**
     * The scan/waiver was applied. Carries the refreshed collection picture through the use-case
     * boundary so the UI never re-derives readiness locally: Station 2's own summary line, the
     * collection status (ReadyForMixing gates the SP4b mixing entry point), the tolerance the
     * server actually applied, and its navigation hint.
     */
    data class Accepted(
        val updatedLines: List<BomLine>,
        val collectionSummary: String,
        val collectionStatus: String,
        val overCollectionToleranceBags: Double?,
        val nextAction: NextAction,
    ) : IngredientScanOutcome()

    /**
     * Station 2 rejected the scan pending manager approval. Carries the whole original scan,
     * because approval is a RESUBMIT of it — in 4.1 with a scoped single-use authorizationToken
     * attached rather than v3's inline credentials, but still the same scan sent again.
     */
    data class NeedsManagerApproval(
        val collectionId: String,
        /** Pallet RFID or Station 3 master-batch label — 4.1's canonical `sourceBarcode`. */
        val sourceBarcode: String,
        val requestedMaterialCode: String,
        val bagSizeOption: String?,
        val bagCount: Double?,
        val quantity: Double?,
        val reason: String,
    ) : IngredientScanOutcome()
    data class NeedsRecovery(val reason: String?) : IngredientScanOutcome()
    data class Rejected(val reason: String) : IngredientScanOutcome()

    /**
     * Station 2 rejected the waiver pending approval — typically missing or invalid manager
     * credentials. Distinct from [NeedsManagerApproval]: a waiver is never resubmitted through
     * the scan path (it has no pallet), the UI re-collects credentials into a fresh
     * waiveShortBags() call.
     */
    data class NeedsApprovalForWaiver(
        val collectionId: String,
        val requestedMaterialCode: String,
        val shortBagCount: Double,
        val reason: String,
    ) : IngredientScanOutcome()
}
