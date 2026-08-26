package com.mitas.ppnam.station2aa.data.mqtt.dto

/** `job_card_load_requested` — always starts a new collection; never silently resumes. */
data class JobCardLoadPayload(
    val jobCardNumber: String,
)

/**
 * `collection_resume_requested` — replays the stored BOM snapshot without calling SAP again.
 * Requires only the collection; Station 2 returns the job card.
 */
data class CollectionResumePayload(
    val collectionId: String,
)

/**
 * One entry in [BomLineResponse.bagSizeOptions] — the wire shape of the backend's
 * `BagSizeOptionMessage`.
 *
 * This is an OBJECT on the wire, not a string. It was declared as a bare `List<String>` here, and
 * because Gson cannot read an object into a String, a single line offering bag sizes aborted the
 * parse of the entire `bom_loaded` message — the operator saw "Station 2 sent an unreadable
 * response" and lost the whole job card, not just the one line's options.
 *
 * [availableBagCount] is fractional by construction: the backend divides a pallet's remaining
 * quantity by the bag size, so a part-used pallet contributes a non-integer count.
 */
data class BagSizeOptionResponse(
    val bagSize: Double = 0.0,
    val availableBagCount: Double = 0.0,
    val availableQuantity: Double = 0.0,
    val unit: String = "",
)

/**
 * One BOM line, as returned by BOTH `bom_loaded` and `ingredient_scan_result` — the contract returns
 * "the full refreshed ingredients[]" in the scan result, identical in shape.
 *
 * `lineNumber` is the identity: duplicate SAP material rows remain separate by it.
 *
 * On a bulk line `bagSize` is null and EVERY *Bags field is null — "no bags" is a different fact
 * from "zero bags", and a bulk line has no automatic over-collection tolerance at all.
 *
 * `collectedQuantity` and `weightReceived` legitimately differ: a scan inside tolerance credits only
 * the remaining required amount to collectedQuantity while recording the full weightReceived.
 */
data class BomLineResponse(
    val lineNumber: Int = 0,
    val materialCode: String = "",
    val materialName: String = "",
    val plannedQuantity: Double = 0.0,
    val issuedQuantity: Double = 0.0,
    val requiredQuantity: Double = 0.0,
    val collectedQuantity: Double = 0.0,
    val weightReceived: Double = 0.0,
    val remainingQuantity: Double = 0.0,
    val availableQuantity: Double = 0.0,
    val bagSize: String? = null,
    val expectedBags: Double? = null,
    val scannedBags: Double? = null,
    val approvedExtraBags: Double? = null,
    val approvedShortBags: Double? = null,
    val remainingBags: Double? = null,
    val action: String = "",
    val collected: Boolean = false,
    val requiresManagerApproval: Boolean = false,
    val issueType: String = "",
    val requiresIngredientCollection: Boolean = false,
    val uomCode: String = "",
    val unit: String = "",
    val warehouse: String = "",

    // ---- 4.1 §9: variable bag sizes ---------------------------------------------------------

    /**
     * True when the collectable pallets for this material come in SEVERAL bag sizes. Then
     * [bagSize] and [expectedBags] are both null — there is no single right answer to show — and
     * [bagSizeOptions] lists the choices.
     *
     * This is the contract-level fix for backend issue B1: previously one bag size was asserted
     * even when the pallets disagreed with it, so the operator was given a bag count computed at a
     * weight the pallet did not have.
     */
    val bagSizeIsVariable: Boolean = false,
    /**
     * Sent whenever collectable pallets exist, NOT only when [bagSizeIsVariable] is true — the live
     * backend populates it for a single-size line as well. Never assume it is empty.
     */
    val bagSizeOptions: List<BagSizeOptionResponse> = emptyList(),

    // ---- 4.1 §9: credited vs captured -------------------------------------------------------

    /**
     * The quantity credited against the requirement — this is what drives progress, and what
     * [collectedQuantity] has always meant.
     */
    val creditedQuantity: Double? = null,
    /** The physical quantity actually taken from stock. Differs from credited on an in-tolerance
     * over-collection: the operator moves the whole bag, the line is credited only what it needed. */
    val capturedQuantity: Double? = null,
    /** `capturedQuantity - creditedQuantity`, signed. Backend issue B7: this was recorded but
     * never exposed, so an over-collection was invisible to the operator who caused it. */
    val varianceQuantity: Double? = null,
    val hasVariance: Boolean = false,

    /** Typed identity of the captured line. */
    val ingredientItemId: String? = null,
    /** Present ONLY when this line consumed a prior approval — backend issue B11 was it being
     * returned when no approval was involved. */
    val consumedApprovalId: String? = null,
)

data class CollectionSummaryResponse(
    val waitingProductCount: Int = 0,
    val collectedProductCount: Int = 0,
    val waitingQuantity: Double = 0.0,
    val collectedQuantity: Double = 0.0,
    /** Station 2's own human-readable line, e.g. "1 product waiting for collection." */
    val summary: String = "",
)

data class BomLoadedResponse(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val resumed: Boolean = false,
    /** Collecting | ReadyForMixing | Mixing | Cancelled */
    val collectionStatus: String = "",
    val bomSnapshotCapturedAtUtc: String? = null,
    val collectionSummary: CollectionSummaryResponse = CollectionSummaryResponse(),
    val ingredients: List<BomLineResponse> = emptyList(),
)

/**
 * One entry in `active_job_cards_list`.
 *
 * Several concurrent collections per job card is intended, client-requested behaviour, so
 * [jobCardNumber] alone does NOT identify a row — [collectionId] does. The progress fields are
 * what let the operator tell four collections of the same card apart; they were already on the
 * wire and simply weren't being parsed.
 */
data class ActiveJobCardSummary(
    val jobCardNumber: String = "",
    val productionOrderDocumentNumber: String = "",
    val collectionId: String = "",
    val productName: String = "",
    val status: String = "",
    /**
     * 0–100, server-computed. Never recomputed from the counts below.
     *
     * Nullable because null and 0.0 mean different things and the live backend sends null: on
     * 2026-07-23 every one of these four arrived null, so a collection sitting at ReadyForMixing
     * rendered as a confident "0%". "Not told" must not be displayed as "no progress" — the same
     * null-is-not-zero rule [BomLine] already applies to its bag fields.
     */
    val progressPercent: Double? = null,
    val completedIngredientCount: Int? = null,
    val requiredIngredientCount: Int? = null,
    /** Lines sitting on a manager approval — worth flagging, the operator can't clear them alone. */
    val pendingApprovalCount: Int? = null,
) {
    /**
     * `status` reads as a wire token ("ReadyForMixing"). Operators read this list to pick the
     * collection they are working on, so it is spelled out. Unknown values pass through verbatim
     * rather than being flattened — a new server status must stay visible, not vanish.
     */
    val statusLabel: String
        get() = when (status) {
            "Collecting" -> "Collecting"
            "ReadyForMixing" -> "Ready to mix"
            "Mixing" -> "Mixing"
            "Cancelled" -> "Cancelled"
            else -> status
        }
}

/**
 * `active_job_cards_requested` — contract v4.1 keyset paging.
 *
 * 4.0 returned the whole queue in one message, which was backend issue B6: it grows without bound
 * as collections accumulate. 4.1 pages it, ordered by `startedAtUtc DESC, collectionId DESC`.
 *
 * Every field is optional; Gson omits nulls, and the contract requires absence rather than null
 * for an unused option. Omitting [pageSize] takes Station 2's default of 25 (max 100).
 */
data class ActiveJobCardsPayload(
    val pageSize: Int? = null,
    val continuationToken: String? = null,
    val statuses: List<String>? = null,
    /** Matched against job-card number and collection ID. */
    val search: String? = null,
)

/**
 * `active_job_cards_list`.
 *
 * [snapshotRevision] is the version this page was read at. An `active_job_cards_invalidated` push
 * carries a new revision, which is the signal to drop [nextContinuationToken] and re-request page
 * one — a token from an older revision is answered with `page_cursor_stale`.
 *
 * Note 4.1 removed the equipment roster from this message; equipment comes from
 * `mixing_overview_requested` only.
 */
data class ActiveJobCardsListResponse(
    val jobs: List<ActiveJobCardSummary> = emptyList(),
    val totalCount: Int? = null,
    val hasMore: Boolean = false,
    val nextContinuationToken: String? = null,
    val snapshotRevision: String? = null,
)

/**
 * `active_job_cards_invalidated` — a 4.1 server push carrying NO `inResponseToMessageId`, since it
 * answers no request.
 *
 * The contract is emphatic that this "is never permission for a workflow mutation" and is not a
 * replacement list: it means the collection queue changed, so discard the cursor and ask for page
 * one again.
 */
data class ActiveJobCardsInvalidatedResponse(
    val snapshotRevision: String? = null,
)

/**
 * `ingredient_collection_cancel_requested`. A privileged action.
 *
 * 4.1: the manager's password no longer travels here. They prove it in a SCRAM exchange scoped to
 * `IngredientCollection:<collectionId>` / `ingredient_collection_cancel`, and the resulting
 * single-use [authorizationToken] is what this message carries. Authorization is still checked
 * against the APPROVER's account, never the sender's session — so it is required even when the
 * sender is themselves a Manager.
 *
 * `auditReason` is the operator's justification, written to the audit trail. It is not the same
 * field as a response's `reason`, which is why Station 2 rejected something.
 */
data class IngredientCollectionCancelPayload(
    val collectionId: String,
    val authorizationToken: String,
    val auditReason: String,
)

data class IngredientCollectionCancelResultResponse(
    val collectionId: String = "",
    val jobCardNumber: String = "",
    val collectionStatus: String = "",
    val nextAction: String = "",
    val approverUserId: String = "",
    val approverDisplayName: String = "",
    val approverRole: String = ""
)
