package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * `ingredient_scan_requested`.
 *
 * ### 4.1: `sourceBarcode`, not `palletRfidTag`
 *
 * The canonical field is [sourceBarcode], because a source is no longer necessarily a pallet — it
 * may be a live Station 3 master-batch label. Station 2 still accepts the legacy `palletRfidTag`
 * from older handhelds, but sending BOTH is rejected with `conflicting_source_barcodes` unless
 * they match case-insensitively after trimming, so this build sends only the canonical one.
 *
 * ### 4.1: manager approval is a token, not a password
 *
 * A resubmitted scan used to carry `managerUsername` + `managerPassword` inline. Schema 4.1
 * rejects any message containing a `managerPassword` property. The manager now proves their
 * credentials in a separate SCRAM exchange scoped to `IngredientScan:<collectionId>` /
 * `ingredient_approve_override`, and only the resulting single-use [authorizationToken] travels
 * here — see [com.mitas.ppnam.station2aa.data.auth.ManagerAuthorization].
 *
 * The resubmit MUST carry a fresh messageId: reusing the rejected one is rejected as
 * `message_id_reused` and does NOT perform the approval. The transport mints a new UUID per
 * request() call, so a resubmit is automatically a new operation.
 *
 * `auditReason` is the operator's justification for the audit trail — not the same field as a
 * response's `reason`, which is why Station 2 rejected something.
 */
data class IngredientScanPayload(
    val collectionId: String,
    val sourceBarcode: String,
    val requestedMaterialCode: String? = null,
    val bagSizeOption: String? = null,
    val bagCount: Double? = null,
    val quantity: Double? = null,
    val authorizationToken: String? = null,
    val auditReason: String? = null,
)

/** `sourceType` on an accepted scan result. */
object SourceType {
    const val PALLET = "Pallet"
    const val STATION3_MASTER_BATCH = "Station3MasterBatch"
}

/** `station3StockStatus`. Cached stale totals are display-only — a scan needs live validation. */
object Station3StockStatus {
    const val LIVE = "Live"
    const val STALE = "Stale"
    const val UNAVAILABLE = "Unavailable"
}

/** `approvalState` on an off-BOM scan that created a durable approval request. */
object ApprovalState {
    const val PENDING = "Pending"
    const val APPROVED = "Approved"
    const val REJECTED = "Rejected"
}

/**
 * `ingredient_scan_result`.
 *
 * Returns the FULL refreshed `ingredients[]` — not just the line this scan touched — so the scanner
 * always holds the current picture. Every scan on one collection shares a correlationKey, so
 * `inResponseToMessageId` is the only way to tell which scan a result belongs to (the transport
 * handles that).
 *
 * `overCollectionToleranceBags` is the tolerance Station 2 ACTUALLY APPLIED — never hardcode it.
 * It is null on a bulk line, where no automatic tolerance applies and any over-collection needs
 * approval.
 *
 * The approver fields are null on an ordinary scan and name the account that authorised an override
 * or waiver. `approverRole` is informational only.
 */
data class IngredientScanResultResponse(
    val collectionId: String = "",
    /** Collecting | ReadyForMixing | Mixing | Cancelled — refreshed on every scan result. */
    val collectionStatus: String = "",
    val requiresManagerApproval: Boolean = false,
    /** Null on a bulk line: no automatic tolerance applies there. */
    val overCollectionToleranceBags: Double? = null,
    val approverUserId: String? = null,
    val approverDisplayName: String? = null,
    val approverRole: String? = null,

    // ---- 4.1 §9: what was actually captured -------------------------------------------------

    /** [SourceType.PALLET] or [SourceType.STATION3_MASTER_BATCH]. */
    val sourceType: String? = null,
    /** The pallet RFID or Station 3 barcode this scan resolved to. */
    val sourceReference: String? = null,
    /** Master-batch labels are collected in full kilograms, once only. */
    val capturedKilograms: Double? = null,
    /**
     * The authoritative bag size for the pallet actually captured.
     *
     * This is the fix for backend issue B1, where the BOM line advertised "20.000 kg" while the
     * pallet was really 25 kg — the operator was shown 10.99 bags to fetch and 9 completed the
     * line. Where this is present it wins over the BOM line's [BomLineResponse.bagSize].
     */
    val actualSourceBagSize: String? = null,

    // ---- 4.1 §9: Station 3 stock freshness --------------------------------------------------

    /** [Station3StockStatus]. */
    val station3StockStatus: String? = null,
    val station3StockStale: Boolean? = null,
    val station3LastSuccessfulRefreshUtc: String? = null,
    val masterBatchScansAllowed: Boolean? = null,

    // ---- 4.1: off-BOM approval handshake ----------------------------------------------------

    /**
     * [ApprovalState.PENDING] when an off-BOM scan created a durable approval request. Such a scan
     * does NOT consume stock: after approval the operator must rescan the material, and only that
     * rescan consumes it.
     */
    val approvalState: String? = null,
    /** Station 2's id for the approval request — quote it to the manager approving at the desk. */
    val exceptionId: String? = null,

    val collectionSummary: CollectionSummaryResponse = CollectionSummaryResponse(),
    val ingredients: List<BomLineResponse> = emptyList(),
)

/**
 * A short-bag waiver. Shares the `ingredient_scan_requested` topic but is a DISTINCT operation:
 * there is no pallet and no bag size — the operator is declaring up front that a line will be short.
 *
 * Authorization goes on the FIRST submission, not a retry: there is no scan to attempt and fail.
 * Sent without a token it is rejected outright with requiresManagerApproval.
 *
 * `requestedMaterialCode` is REQUIRED — there is no pallet to identify the line.
 *
 * The approver must hold `ingredient_approve_short_bag` — a different action id from an override's
 * `ingredient_approve_override`, and the token is scoped to it. Station 2 checks that against the
 * approver's account; we never do.
 */
data class ShortBagWaiverPayload(
    val collectionId: String,
    val requestedMaterialCode: String,
    val shortBagCount: Double,
    val authorizationToken: String,
    val auditReason: String,
)
