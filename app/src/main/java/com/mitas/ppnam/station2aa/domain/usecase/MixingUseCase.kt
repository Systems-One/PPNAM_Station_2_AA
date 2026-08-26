package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ManagerAuthorization
import com.mitas.ppnam.station2aa.data.local.BomCacheDao
import com.mitas.ppnam.station2aa.data.local.BomCacheEntity
import com.mitas.ppnam.station2aa.data.mqtt.EmptyPayload
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.CollectionResumePayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.JobCardLoadPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.ManagerAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.ShortBagWaiverPayload
import com.mitas.ppnam.station2aa.domain.model.ActiveJobsPage
import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.mitas.ppnam.station2aa.domain.model.ProductionOrder
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MixingUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val bomCacheDao: BomCacheDao,
    private val palletUseCase: PalletUseCase,
    private val managerAuthorization: ManagerAuthorization,
) {
    private val gson = WireJson.gson

    /**
     * Loads a job card, or resumes an exact existing collection when [collectionId] is supplied.
     *
     * v3 splits what v2 sent as one message: a load always creates a NEW destination-neutral
     * collection (loading the same job again after earlier work is valid and traceable), whereas a
     * resume replays the stored immutable BOM snapshot without calling SAP.
     */
    suspend fun lookupJob(jobCardNumber: String, collectionId: String = ""): Result<ProductionOrder> {
        val resuming = collectionId.isNotBlank()
        val outcome = mqttRepository.request(
            requestType = if (resuming) "collection_resume_requested" else "job_card_load_requested",
            responseType = "bom_loaded",
            payload = if (resuming) {
                CollectionResumePayload(collectionId = collectionId)
            } else {
                JobCardLoadPayload(jobCardNumber = jobCardNumber)
            },
            correlationKey = if (resuming) collectionId else jobCardNumber,
            responseClass = BomLoadedResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> {
                val order = outcome.body.toProductionOrder()
                bomCacheDao.put(
                    BomCacheEntity(jobCardNumber, gson.toJson(order), Instant.now().toEpochMilli())
                )
                Result.success(order)
            }
            is MqttOutcome.Rejected -> Result.failure(Exception(humaniseLookupRejection(outcome.reason)))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    /**
     * Station 2's rejection reasons are written for engineers. The one an operator actually hits —
     * a job card number that doesn't exist — came back as "SAP production order lookup failed and
     * no stored local BOM snapshot is available", which tells a shop-floor user nothing about what
     * to do next.
     *
     * Only the reasons we recognise are rewritten; anything else passes through verbatim rather
     * than being flattened into a generic message that would hide a real fault.
     */
    private fun humaniseLookupRejection(reason: String?): String {
        val raw = reason?.trim().orEmpty()
        if (raw.isBlank()) return "Job card rejected"
        // Matched on substrings because Station 2 words this several ways and has changed the
        // wording at least once: it sent "SAP production order lookup failed and no stored local
        // BOM snapshot is available" in July, and "Job card 'N' does not map to a SAP production
        // order." by 2026-07-23 — which slipped straight through to the operator verbatim.
        // "SAP production order" is the stable part of every variant seen so far.
        val notFound = listOf(
            "SAP production order lookup failed",
            "no stored local BOM snapshot",
            "does not map to a SAP production order",
            "not map to a sap production order",
        )
        return if (notFound.any { raw.contains(it, ignoreCase = true) }) {
            "That job card number wasn't found. Check the number on the card and try again."
        } else {
            raw
        }
    }

    private fun BomLoadedResponse.toProductionOrder() = ProductionOrder(
        docNo = jobCardNumber,
        collectionId = collectionId,
        collectionStatus = collectionStatus,
        summary = collectionSummary.summary,
        // im_Backflush lines stay in Station 2's snapshot but are excluded from the handheld's
        // collection array — the one such line names the product being made.
        productBeingMade = ingredients.firstOrNull { it.issueType == "im_Backflush" }?.materialName,
        lines = ingredients
            .filter { it.issueType != "im_Backflush" }
            .map { it.toBomLine() },
    )

    private fun BomLineResponse.toBomLine() = BomLine(
        // Identity. Two lines may legitimately share a materialCode.
        lineNumber = lineNumber,
        itemCode = materialCode,
        itemName = materialName,
        requiredQty = requiredQuantity,
        collectedQty = collectedQuantity,
        weightReceived = weightReceived,
        remainingQty = remainingQuantity,
        availableQty = availableQuantity,
        // SAP UoM 269 displays as kg and 268 as each; unknown values pass through.
        // orEmpty() before ifBlank() is belt to WireJson's braces. A null `unit` used to reach here
        // and NPE on ifBlank, taking down every job card load — the same failure mode that crashed
        // the pallet lookup on 2026-07-23 and the mixing board on 2026-07-29. WireJson now deletes
        // wire nulls before Gson can bind them, so this can no longer be the thing that saves us.
        uom = unit.orEmpty().ifBlank { uomCode.orEmpty() },
        // Null on a bulk line, and null is meaningful — do NOT coalesce to 0.0.
        bagSize = bagSize,
        expectedBags = expectedBags,
        scannedBags = scannedBags,
        approvedExtraBags = approvedExtraBags,
        approvedShortBags = approvedShortBags,
        remainingBags = remainingBags,
    )

    /**
     * Fetches one page of the active collection queue (4.1 keyset paging).
     *
     * 4.0 returned the entire queue in one message, which grows without bound as collections
     * accumulate — backend issue B6. 4.1 pages it by `startedAtUtc DESC, collectionId DESC`.
     *
     * @param continuationToken null for the first page. A token from a superseded snapshot is
     *   rejected with `page_cursor_stale`, which is reported here as [ActiveJobsPage.cursorStale]
     *   rather than a failure: the caller's correct response is to re-request page one, not to show
     *   the operator an error about a cursor they know nothing about.
     */
    suspend fun fetchActiveJobCards(
        pageSize: Int? = null,
        continuationToken: String? = null,
        statuses: List<String>? = null,
        search: String? = null,
    ): Result<ActiveJobsPage> =
        when (
            val outcome = mqttRepository.request(
                requestType = "active_job_cards_requested",
                responseType = "active_job_cards_list",
                payload = ActiveJobCardsPayload(
                    pageSize = pageSize,
                    continuationToken = continuationToken,
                    statuses = statuses?.takeIf { it.isNotEmpty() },
                    search = search?.takeIf { it.isNotBlank() },
                ),
                correlationKey = null,
                responseClass = ActiveJobCardsListResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(
                ActiveJobsPage(
                    jobs = outcome.body.jobs,
                    totalCount = outcome.body.totalCount,
                    hasMore = outcome.body.hasMore,
                    nextContinuationToken = outcome.body.nextContinuationToken,
                    snapshotRevision = outcome.body.snapshotRevision,
                )
            )
            is MqttOutcome.Rejected ->
                if (outcome.errorCode == ErrorCode.PAGE_CURSOR_STALE) {
                    Result.success(ActiveJobsPage.CURSOR_STALE)
                } else {
                    Result.failure(Exception(outcome.reason ?: "Could not load active jobs"))
                }
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }

    /**
     * Cancels an eligible collection. Manager credentials are ALWAYS required — v3 authorises this
     * on the approver named in the request, never on the sending session, so a Manager cancelling
     * from their own handheld must still supply credentials. Rejected once routing or other
     * protected downstream activity has happened.
     */
    suspend fun cancelJob(
        collectionId: String,
        jobCardNumber: String,
        reason: String,
        managerUsername: String,
        managerPassword: String,
    ): Result<IngredientCollectionCancelResultResponse> {
        // 4.1: prove the manager's credentials in a scoped SCRAM exchange first; only the
        // resulting single-use token travels on the cancel itself.
        val token = managerAuthorization.authorize(
            managerUsername = managerUsername,
            managerPassword = managerPassword,
            action = ManagerAction.CancelCollection,
            targetId = collectionId,
        ).getOrElse { return Result.failure(it) }

        return when (
            val outcome = mqttRepository.request(
                requestType = "ingredient_collection_cancel_requested",
                responseType = "ingredient_collection_cancel_result",
                payload = IngredientCollectionCancelPayload(
                    collectionId = collectionId,
                    authorizationToken = token,
                    auditReason = reason,
                ),
                correlationKey = collectionId.ifBlank { jobCardNumber },
                responseClass = IngredientCollectionCancelResultResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(outcome.body)
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Cancel rejected"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    /**
     * @param sourceBarcode the pallet RFID tag or Station 3 master-batch label. 4.1 made this one
     *   canonical field because a source is no longer necessarily a pallet.
     */
    suspend fun scanIngredient(
        collectionId: String,
        sourceBarcode: String,
        requestedMaterialCode: String,
        bagSizeOption: String? = null,
        bagCount: Double? = null,
        quantity: Double? = null,
        managerUsername: String? = null,
        managerPassword: String? = null,
        auditReason: String? = null,
    ): Result<IngredientScanOutcome> {
        // The two capture shapes are mutually exclusive on the wire (contract §5): bag
        // fields for bagged stock, quantity for direct weight. Fail closed before publishing.
        val bagShape = bagSizeOption != null || bagCount != null
        if (bagShape == (quantity != null)) {
            return Result.failure(IllegalArgumentException(
                "Send either bagSizeOption+bagCount or quantity, never both or neither."))
        }
        if (bagShape && (bagSizeOption == null || bagCount == null)) {
            return Result.failure(IllegalArgumentException(
                "A bag scan needs both bagSizeOption and bagCount."))
        }

        // 4.1: an override is authorized by a scoped, single-use token rather than by a password
        // riding along on the scan. Only exchanged when the caller actually supplied manager
        // credentials — an ordinary first scan carries no authorization at all.
        val authorizationToken = if (!managerUsername.isNullOrBlank() && !managerPassword.isNullOrBlank()) {
            managerAuthorization.authorize(
                managerUsername = managerUsername,
                managerPassword = managerPassword,
                action = ManagerAction.ApproveOverride,
                targetId = collectionId,
            ).getOrElse { return Result.failure(it) }
        } else {
            null
        }

        val outcome = mqttRepository.request(
            requestType = "ingredient_scan_requested",
            responseType = "ingredient_scan_result",
            payload = IngredientScanPayload(
                collectionId = collectionId,
                sourceBarcode = sourceBarcode,
                requestedMaterialCode = requestedMaterialCode,
                bagSizeOption = bagSizeOption,
                bagCount = bagCount,
                quantity = quantity,
                authorizationToken = authorizationToken,
                auditReason = auditReason,
            ),
            correlationKey = collectionId,
            responseClass = IngredientScanResultResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> Result.success(
                IngredientScanOutcome.Accepted(
                    updatedLines = outcome.body.ingredients
                        // Same rule as the initial load: the backflush line is the product being
                        // made, not a component to collect. Without this filter a scan response
                        // reintroduces it as a collectible line, because MixingViewModel replaces
                        // the whole line list wholesale with this output.
                        .filter { it.issueType != "im_Backflush" }
                        .map { it.toBomLine() },
                    collectionSummary = outcome.body.collectionSummary.summary,
                    collectionStatus = outcome.body.collectionStatus,
                    overCollectionToleranceBags = outcome.body.overCollectionToleranceBags,
                    nextAction = outcome.nextAction,
                )
            )
            is MqttOutcome.Rejected -> Result.success(
                when {
                    outcome.body.requiresManagerApproval -> IngredientScanOutcome.NeedsManagerApproval(
                        // Rebuilt from the REQUEST — the response doesn't echo these back.
                        collectionId = collectionId,
                        sourceBarcode = sourceBarcode,
                        requestedMaterialCode = requestedMaterialCode,
                        bagSizeOption = bagSizeOption,
                        bagCount = bagCount,
                        quantity = quantity,
                        reason = outcome.reason ?: "Manager approval required",
                    )
                    outcome.nextAction == NextAction.RECOVER_HOLDING ->
                        IngredientScanOutcome.NeedsRecovery(outcome.reason)
                    else -> IngredientScanOutcome.Rejected(outcome.reason ?: "Ingredient scan rejected")
                }
            )
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    /**
     * Waives a short bag on a line. NOT a reject-then-retry: credentials travel on the FIRST
     * submission, because there is no scan to attempt and fail first — the operator is declaring
     * up front that a line will be short. Shares `ingredient_scan_requested` but sends a distinct
     * [ShortBagWaiverPayload]: no pallet, no bag size, `shortBagCount` instead.
     */
    suspend fun waiveShortBags(
        collectionId: String,
        requestedMaterialCode: String,
        shortBagCount: Double,
        managerUsername: String,
        managerPassword: String,
        auditReason: String,
    ): Result<IngredientScanOutcome> {
        // Scoped to `ingredient_approve_short_bag` — a DIFFERENT action id from an override's
        // `ingredient_approve_override`, so a token minted for one cannot authorize the other.
        val token = managerAuthorization.authorize(
            managerUsername = managerUsername,
            managerPassword = managerPassword,
            action = ManagerAction.ApproveShortBag,
            targetId = collectionId,
        ).getOrElse { return Result.failure(it) }

        val outcome = mqttRepository.request(
            requestType = "ingredient_scan_requested",
            responseType = "ingredient_scan_result",
            payload = ShortBagWaiverPayload(
                collectionId = collectionId,
                requestedMaterialCode = requestedMaterialCode,
                shortBagCount = shortBagCount,
                authorizationToken = token,
                auditReason = auditReason,
            ),
            correlationKey = collectionId,
            responseClass = IngredientScanResultResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> Result.success(
                IngredientScanOutcome.Accepted(
                    // Same rule as scanIngredient: a waiver adjusts the line's requirement
                    // directly and never produces a scanned line, but the backflush line still
                    // needs filtering out of the wholesale-replaced line list.
                    updatedLines = outcome.body.ingredients
                        .filter { it.issueType != "im_Backflush" }
                        .map { it.toBomLine() },
                    collectionSummary = outcome.body.collectionSummary.summary,
                    collectionStatus = outcome.body.collectionStatus,
                    overCollectionToleranceBags = outcome.body.overCollectionToleranceBags,
                    nextAction = outcome.nextAction,
                )
            )
            is MqttOutcome.Rejected -> Result.success(
                when {
                    outcome.body.requiresManagerApproval -> IngredientScanOutcome.NeedsApprovalForWaiver(
                        collectionId = collectionId,
                        requestedMaterialCode = requestedMaterialCode,
                        shortBagCount = shortBagCount,
                        reason = outcome.reason ?: "Manager approval required",
                    )
                    outcome.nextAction == NextAction.RECOVER_HOLDING ->
                        IngredientScanOutcome.NeedsRecovery(outcome.reason)
                    else -> IngredientScanOutcome.Rejected(outcome.reason ?: "Waiver rejected")
                }
            )
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    /** Delegates to [PalletUseCase] — holding recovery has one implementation, in one place. */
    suspend fun recoverHolding(collectionId: String, palletRfidTag: String): Result<Unit> =
        palletUseCase.recoverToHolding(
            palletRfidTag = palletRfidTag,
            collectionId = collectionId.ifBlank { null },
            auditReason = "Pallet is physically at Station 2; fixed door read was missed.",
        ).map { }
}
