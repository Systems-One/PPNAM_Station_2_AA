package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.local.BomCacheDao
import com.mitas.ppnam.station2aa.data.auth.ManagerAuthorization
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.CollectionResumePayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.CollectionSummaryResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.JobCardLoadPayload
import com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MixingUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var mockBomCacheDao: BomCacheDao
    private lateinit var mockPalletUseCase: PalletUseCase
    private lateinit var mockManagerAuthorization: ManagerAuthorization
    private lateinit var useCase: MixingUseCase

    @Before
    fun setup() = runTest {
        mockMqtt = mock()
        mockBomCacheDao = mock()
        mockPalletUseCase = mock()
        mockManagerAuthorization = mock()
        // 4.1: every privileged action first exchanges the manager's credentials for a scoped
        // single-use token. Default to a successful authorization so the tests that are about
        // the workflow message stay about the workflow message.
        whenever(mockManagerAuthorization.authorize(any(), any(), any(), any()))
            .thenReturn(Result.success("auth-token-1"))
        useCase = MixingUseCase(mockMqtt, mockBomCacheDao, mockPalletUseCase, mockManagerAuthorization)
    }

    // --- lookupJob ---

    @Test
    fun `lookupJob success caches bom and returns ProductionOrder`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            collectionId = "COL_000001",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", plannedQuantity = 50.0)
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))
        whenever(mockBomCacheDao.put(any())).thenReturn(Unit)

        val result = useCase.lookupJob("510019068")

        assertTrue(result.isSuccess)
        assertEquals("510019068", result.getOrThrow().docNo)
        assertEquals("COL_000001", result.getOrThrow().collectionId)
        verify(mockBomCacheDao).put(any())
    }

    @Test
    fun `lookupJob falls back to uomCode when unit is blank`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            collectionId = "COL_000001",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", plannedQuantity = 50.0, uomCode = "KG")
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals("KG", order.lines.single().uom)
    }

    @Test
    fun `lookupJob prefers the humanized unit over the raw SAP uomCode`() = runTest {
        // Real Station 2 schema 2.0 responses send the raw SAP code ("269") in uomCode
        // and the humanized value ("kg") in a separate unit field.
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            collectionId = "COL_000001",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", plannedQuantity = 50.0, uomCode = "269", unit = "kg")
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals("kg", order.lines.single().uom)
    }

    @Test
    fun `lookupJob uses jobCardNumber as docNo since productionOrderDocumentNumber is no longer returned`() = runTest {
        // Real Station 2 schema 2.0 ingredient_collection_loaded responses omit
        // productionOrderDocumentNumber entirely — only jobCardNumber comes back.
        val response = BomLoadedResponse(
            jobCardNumber = "510019359",
            collectionId = "COL_000004",
            ingredients = emptyList()
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019359").getOrThrow()

        assertEquals("510019359", order.docNo)
    }

    @Test
    fun `lookupJob separates the backflush line as the product being made`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019231",
            productionOrderDocumentNumber = "510019231",
            collectionId = "COL_000001",
            ingredients = listOf(
                BomLineResponse(
                    materialCode = "1500000326",
                    materialName = "MASTERBATCH WHITE MH 1316 HD / 5001",
                    plannedQuantity = 74.812,
                    issueType = "im_Manual",
                    requiresIngredientCollection = true
                ),
                BomLineResponse(
                    materialCode = "22306",
                    materialName = "CARRIER BAG LEVY",
                    plannedQuantity = 300000.0,
                    issueType = "im_Backflush",
                    requiresIngredientCollection = false
                )
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019231").getOrThrow()

        assertEquals("CARRIER BAG LEVY", order.productBeingMade)
        assertEquals(1, order.lines.size)
        assertEquals("1500000326", order.lines.single().itemCode)
    }

    @Test
    fun `lookupJob leaves productBeingMade null when no backflush line is present`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            collectionId = "COL_000001",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", issueType = "im_Manual")
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals(null, order.productBeingMade)
        assertEquals(1, order.lines.size)
    }

    @Test
    fun `lookupJob carries remainingQty through for every manual line`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            productionOrderDocumentNumber = "510019068",
            collectionId = "COL_000001",
            ingredients = listOf(
                BomLineResponse(
                    materialCode = "MAT-001", materialName = "Resin",
                    plannedQuantity = 50.0, issuedQuantity = 50.0, remainingQuantity = 0.0,
                    issueType = "im_Manual"
                ),
                BomLineResponse(
                    materialCode = "MAT-002", materialName = "Colorant",
                    plannedQuantity = 10.0, issuedQuantity = 3.0, remainingQuantity = 7.0,
                    issueType = "im_Manual"
                )
            )
        )
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019068").getOrThrow()

        val resin = order.lines.single { it.itemCode == "MAT-001" }
        val colorant = order.lines.single { it.itemCode == "MAT-002" }
        assertEquals(0.0, resin.remainingQty, 0.0001)
        assertTrue(resin.isFullyAllocated)
        assertEquals(7.0, colorant.remainingQty, 0.0001)
        assertFalse(colorant.isFullyAllocated)
    }

    @Test
    fun `duplicate material rows survive the mapping as separate lines`() = runTest {
        // Keyed on materialCode, these would collapse into one and corrupt both lines' progress.
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            collectionId = "COL_000123",
            ingredients = listOf(
                BomLineResponse(lineNumber = 0, materialCode = "1600000301", materialName = "HD WHITE",
                    plannedQuantity = 100.0, requiredQuantity = 100.0, remainingQuantity = 100.0, issueType = "im_Manual"),
                BomLineResponse(lineNumber = 1, materialCode = "1600000301", materialName = "HD WHITE",
                    plannedQuantity = 50.0, requiredQuantity = 50.0, remainingQuantity = 50.0, issueType = "im_Manual"),
            ),
        )
        whenever(mockMqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals(2, order.lines.size)
        assertEquals(listOf(0, 1), order.lines.map { it.lineNumber })
        assertEquals(listOf(100.0, 50.0), order.lines.map { it.requiredQty })
    }

    @Test
    fun `requiredQty comes from requiredQuantity, not SAP's original plannedQuantity`() = runTest {
        // The contract distinguishes them: plannedQuantity is SAP's original, requiredQuantity is
        // what remains required after an approved short-bag waiver adjusts the line. Sourcing the
        // wrong one makes the progress denominator jump when an exception is approved.
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            ingredients = listOf(
                BomLineResponse(
                    lineNumber = 0,
                    materialCode = "1600000301",
                    materialName = "HD WHITE",
                    plannedQuantity = 557.049,   // SAP's original
                    requiredQuantity = 532.049,  // after a 1-bag waiver at 25kg
                    remainingQuantity = 532.049,
                    approvedShortBags = 1.0,
                    bagSize = "25.000 kg",
                    issueType = "im_Manual",
                    unit = "kg",
                ),
            ),
        )
        whenever(mockMqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val line = useCase.lookupJob("510019068").getOrThrow().lines.single()

        assertEquals(532.049, line.requiredQty, 0.001)
        assertEquals(1.0, line.approvedShortBags!!, 0.001)
    }

    @Test
    fun `a bulk line maps with null bag fields, not zeroes`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            ingredients = listOf(
                BomLineResponse(lineNumber = 0, materialCode = "BULK-1", materialName = "Bulk Resin",
                    plannedQuantity = 500.0, requiredQuantity = 500.0, remainingQuantity = 500.0, issueType = "im_Manual",
                    bagSize = null, expectedBags = null, scannedBags = null, remainingBags = null),
            ),
        )
        whenever(mockMqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val line = useCase.lookupJob("510019068").getOrThrow().lines.single()

        assertFalse(line.isBagged)
        assertNull(line.expectedBags)
        assertNull(line.remainingBags)
    }

    @Test
    fun `a bagged line survives a job load with its bagSize and bag figures intact`() = runTest {
        // Task 1's review: toProductionOrder() mapped no bag fields at all, so every line read as
        // unbagged after a job load until its first scan refreshed it. On a resumed collection with
        // a bagged line whose quantity is satisfied but bags are not, that reports "Fully Allocated" —
        // a false positive. This pins the fix.
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            ingredients = listOf(
                BomLineResponse(lineNumber = 0, materialCode = "1600000301", materialName = "HD WHITE",
                    plannedQuantity = 557.049, requiredQuantity = 557.049, remainingQuantity = 0.0,
                    bagSize = "25.000 kg", expectedBags = 22.282, scannedBags = 20.0, remainingBags = 2.282,
                    issueType = "im_Manual"),
            ),
        )
        whenever(mockMqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val line = useCase.lookupJob("510019068").getOrThrow().lines.single()

        assertTrue(line.isBagged)
        assertEquals("25.000 kg", line.bagSize)
        assertEquals(22.282, line.expectedBags!!, 0.0001)
        assertEquals(2.282, line.remainingBags!!, 0.0001)
        // Quantity is satisfied but bags are not — this must NOT report as fully allocated/satisfied.
        assertTrue(line.isFullyAllocated)
        assertFalse(line.isSatisfied)
    }

    @Test
    fun `bom_loaded carries availableQuantity, bagSize and the collection summary through`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068",
            collectionStatus = "Collecting",
            collectionSummary = CollectionSummaryResponse(
                waitingProductCount = 1, waitingQuantity = 557.049, summary = "1 product waiting for collection."
            ),
            ingredients = listOf(
                BomLineResponse(lineNumber = 0, materialCode = "1600000301", materialName = "HD WHITE",
                    plannedQuantity = 557.049, requiredQuantity = 557.049, remainingQuantity = 557.049, availableQuantity = 625.0,
                    bagSize = "25.000 kg", expectedBags = 22.282, remainingBags = 22.282,
                    issueType = "im_Manual", unit = "kg"),
            ),
        )
        whenever(mockMqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val order = useCase.lookupJob("510019068").getOrThrow()

        assertEquals("Collecting", order.collectionStatus)
        assertEquals("1 product waiting for collection.", order.summary)
        val line = order.lines.single()
        assertEquals(625.0, line.availableQty, 0.001)
        assertEquals("25.000 kg", line.bagSize)
        assertEquals(22.282, line.expectedBags!!, 0.001)
    }

    @Test
    fun `lookupJob returns failure when rejected`() = runTest {
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(
            MqttOutcome.Rejected(BomLoadedResponse(), null, "Job card not found", NextAction.SCAN_JOB_CARD)
        )

        val result = useCase.lookupJob("510019068")
        assertTrue(result.isFailure)
        assertEquals("Job card not found", result.exceptionOrNull()?.message)
    }

    /**
     * Regression, 2026-07-23: the live backend now words an unknown job card as
     * "Job card 'N' does not map to a SAP production order." The humaniser only matched the older
     * "SAP production order lookup failed" / "no stored local BOM snapshot" phrasings, so this
     * reached the operator verbatim — engineer-speak about SAP for someone holding a paper card.
     */
    @Test
    fun `lookupJob humanises every known not-found phrasing Station 2 has used`() = runTest {
        val phrasings = listOf(
            "SAP production order lookup failed and no stored local BOM snapshot is available",
            "No stored local BOM snapshot is available",
            "Job card '999999999' does not map to a SAP production order.",
        )
        for (reason in phrasings) {
            whenever(
                mockMqtt.request(
                    eq("job_card_load_requested"), eq("bom_loaded"), any(), any(),
                    eq(BomLoadedResponse::class.java)
                )
            ).thenReturn(
                MqttOutcome.Rejected(BomLoadedResponse(), null, reason, NextAction.SCAN_JOB_CARD)
            )

            val result = useCase.lookupJob("999999999")

            assertEquals(
                "That job card number wasn't found. Check the number on the card and try again.",
                result.exceptionOrNull()?.message,
            )
        }
    }

    /** An unrecognised reason must still reach the operator intact, not be flattened away. */
    @Test
    fun `lookupJob passes an unrecognised rejection reason through verbatim`() = runTest {
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(),
                eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(
            MqttOutcome.Rejected(
                BomLoadedResponse(), null,
                "Collection is already routed to a mixer.", NextAction.NONE,
            )
        )

        val result = useCase.lookupJob("510019068")

        assertEquals("Collection is already routed to a mixer.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `lookupJob returns failure on no response`() = runTest {
        whenever(
            mockMqtt.request(
                eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java)
            )
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.lookupJob("510019068")
        assertTrue(result.isFailure)
    }

    @Test
    fun `a blank collectionId loads a new job card`() = runTest {
        whenever(
            mockMqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java))
        ).thenReturn(MqttOutcome.Accepted(BomLoadedResponse(jobCardNumber = "510019068"), NextAction.SCAN_INGREDIENT))

        useCase.lookupJob("510019068")

        verify(mockMqtt).request(
            eq("job_card_load_requested"), eq("bom_loaded"),
            argThat<Any> { this is JobCardLoadPayload && jobCardNumber == "510019068" },
            eq("510019068"), eq(BomLoadedResponse::class.java),
        )
    }

    @Test
    fun `a supplied collectionId resumes that exact collection instead of reloading SAP`() = runTest {
        whenever(
            mockMqtt.request(eq("collection_resume_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java))
        ).thenReturn(MqttOutcome.Accepted(BomLoadedResponse(jobCardNumber = "510019068"), NextAction.SCAN_INGREDIENT))

        useCase.lookupJob("510019068", "COL_000123")

        verify(mockMqtt).request(
            eq("collection_resume_requested"), eq("bom_loaded"),
            argThat<Any> {
                this is CollectionResumePayload && collectionId == "COL_000123"
            },
            eq("COL_000123"), eq(BomLoadedResponse::class.java),
        )
    }

    // --- cancelJob ---

    @Test
    fun `cancelJob succeeds and returns the result body`() = runTest {
        val response = IngredientCollectionCancelResultResponse(
            collectionId = "COL_000001",
            jobCardNumber = "510019068",
            collectionStatus = "Cancelled",
            nextAction = "scan_job_card"
        )
        whenever(
            mockMqtt.request(
                eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
                any(), any(), eq(IngredientCollectionCancelResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_JOB_CARD))

        val result = useCase.cancelJob("COL_000001", "510019068", "Operator cancelled — incorrect job card", "Manager1", "5678")

        assertTrue(result.isSuccess)
        assertEquals("Cancelled", result.getOrThrow().collectionStatus)
    }

    @Test
    fun `cancelJob sends manager credentials and audit reason in the request payload`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
                any(), any(), eq(IngredientCollectionCancelResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(IngredientCollectionCancelResultResponse(), NextAction.SCAN_JOB_CARD))

        useCase.cancelJob("COL_000001", "510019068", "reason", "Manager1", "5678")

        verify(mockMqtt).request(
            eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
            argThat<Any> {
                this is com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelPayload &&
                    collectionId == "COL_000001" && authorizationToken == "auth-token-1" &&
                    auditReason == "reason"
            },
            any(), eq(IngredientCollectionCancelResultResponse::class.java),
        )
    }

    @Test
    fun `cancelJob returns failure with backend reason when rejected`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
                any(), any(), eq(IngredientCollectionCancelResultResponse::class.java)
            )
        ).thenReturn(
            MqttOutcome.Rejected(
                IngredientCollectionCancelResultResponse(), null,
                "Manager or admin approval is required.", NextAction.SCAN_JOB_CARD
            )
        )

        val result = useCase.cancelJob("COL_000001", "510019068", "reason", "Manager1", "5678")

        assertTrue(result.isFailure)
        assertEquals("Manager or admin approval is required.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancelJob returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_collection_cancel_requested"), eq("ingredient_collection_cancel_result"),
                any(), any(), eq(IngredientCollectionCancelResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.cancelJob("COL_000001", "510019068", "reason", "Manager1", "5678")

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    // --- fetchActiveJobCards ---

    @Test
    fun `fetchActiveJobCards returns the job list on success`() = runTest {
        val response = ActiveJobCardsListResponse(
            jobs = listOf(
                ActiveJobCardSummary(
                    jobCardNumber = "510019068",
                    productionOrderDocumentNumber = "510019068",
                    collectionId = "COL_000001",
                    productName = "Layer Mash",
                    status = "Open"
                )
            )
        )
        whenever(
            mockMqtt.request(
                eq("active_job_cards_requested"), eq("active_job_cards_list"),
                any(), anyOrNull(), eq(ActiveJobCardsListResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.NONE))

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().jobs.size)
        assertEquals("510019068", result.getOrThrow().jobs.first().jobCardNumber)
        assertEquals("Layer Mash", result.getOrThrow().jobs.first().productName)
    }

    @Test
    fun `fetchActiveJobCards returns failure when backend rejects`() = runTest {
        whenever(
            mockMqtt.request(
                eq("active_job_cards_requested"), eq("active_job_cards_list"),
                any(), anyOrNull(), eq(ActiveJobCardsListResponse::class.java)
            )
        ).thenReturn(
            MqttOutcome.Rejected(
                ActiveJobCardsListResponse(), null,
                "Operator session is not active for this RFID device. Log in again on this reader.",
                NextAction.LOGIN,
            )
        )

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isFailure)
        assertEquals("Operator session is not active for this RFID device. Log in again on this reader.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fetchActiveJobCards returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.request(
                eq("active_job_cards_requested"), eq("active_job_cards_list"),
                any(), anyOrNull(), eq(ActiveJobCardsListResponse::class.java)
            )
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.fetchActiveJobCards()

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    // --- scanIngredient ---

    @Test
    fun `scanIngredient accepted maps ingredientProgress into updated BomLine list`() = runTest {
        val response = IngredientScanResultResponse(
            collectionId = "COL_000001",
            ingredients = listOf(
                BomLineResponse(
                    materialCode = "MAT-001", materialName = "Resin",
                    plannedQuantity = 50.0, issuedQuantity = 20.0, requiredQuantity = 50.0,
                    collectedQuantity = 20.0, weightReceived = 20.5, remainingQuantity = 30.0,
                    expectedBags = 5.0, scannedBags = 2.0, remainingBags = 3.0,
                    uomCode = "kg", unit = "kg"
                )
            )
        )
        whenever(
            mockMqtt.request(
                eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
                any(), any(), eq(IngredientScanResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val result = useCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)

        assertTrue(result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue(outcome is IngredientScanOutcome.Accepted)
        val line = (outcome as IngredientScanOutcome.Accepted).updatedLines.single()
        assertEquals("MAT-001", line.itemCode)
        assertEquals(50.0, line.requiredQty, 0.0001)
        assertEquals(20.0, line.collectedQty, 0.0001)
        assertEquals(20.5, line.weightReceived, 0.0001)
        assertEquals(30.0, line.remainingQty, 0.0001)
        assertEquals(3.0, line.remainingBags ?: 0.0, 0.0001)
        assertEquals("kg", line.uom)
    }

    @Test
    fun `scanIngredient accepted filters the im_Backflush line out of updatedLines`() = runTest {
        // MixingViewModel.handleScanOutcome() replaces the whole line list wholesale with this
        // output. Without this filter, the product being made would reappear as a collectible line.
        val response = IngredientScanResultResponse(
            collectionId = "COL_000001",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", issueType = "im_Manual"),
                BomLineResponse(materialCode = "22306", materialName = "CARRIER BAG LEVY", issueType = "im_Backflush"),
            )
        )
        whenever(
            mockMqtt.request(
                eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
                any(), any(), eq(IngredientScanResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_INGREDIENT))

        val outcome = useCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0).getOrThrow()

        val lines = (outcome as IngredientScanOutcome.Accepted).updatedLines
        assertEquals(1, lines.size)
        assertEquals("MAT-001", lines.single().itemCode)
    }

    @Test
    fun `scanIngredient Accepted carries summary, status, tolerance and nextAction through the boundary`() = runTest {
        val response = IngredientScanResultResponse(
            collectionId = "COL_000001",
            collectionStatus = "ReadyForMixing",
            overCollectionToleranceBags = 1.0,
            collectionSummary = CollectionSummaryResponse(summary = "All products collected."),
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin", requiredQuantity = 50.0, collectedQuantity = 50.0)
            )
        )
        whenever(
            mockMqtt.request(
                eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(),
                eq(IngredientScanResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(response, NextAction.OPEN_MIXING))

        val outcome = useCase.scanIngredient(
            "COL_000001", "TAG-1", "MAT-001", bagSizeOption = "full", bagCount = 2.0
        ).getOrThrow() as IngredientScanOutcome.Accepted

        assertEquals("All products collected.", outcome.collectionSummary)
        assertEquals("ReadyForMixing", outcome.collectionStatus)
        assertEquals(1.0, outcome.overCollectionToleranceBags!!, 0.0)
        assertEquals(NextAction.OPEN_MIXING, outcome.nextAction)
    }

    @Test
    fun `scanIngredient sends collectionId, sourceBarcode, bagSizeOption and bagCount in the request`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
                any(), any(), eq(IngredientScanResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.Timeout))

        useCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)

        verify(mockMqtt).request(
            eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
            argThat<Any> {
                this is com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanPayload &&
                    collectionId == "COL_000001" && sourceBarcode == "EPC:300833" &&
                    bagSizeOption == "full" && bagCount == 2.0
            },
            eq("COL_000001"), eq(IngredientScanResultResponse::class.java),
        )
    }

    @Test
    fun `a scan needing approval returns everything required to resubmit it`() = runTest {
        whenever(mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(
                MqttOutcome.Rejected(
                    IngredientScanResultResponse(collectionId = "COL_000123", requiresManagerApproval = true),
                    null, "Over tolerance", NextAction.RETRY_WITH_MANAGER_APPROVAL,
                )
            )

        val outcome = useCase.scanIngredient("COL_000123", "TAG-1", "1600000301", bagSizeOption = "1/2", bagCount = 3.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsManagerApproval)
        val needs = outcome as IngredientScanOutcome.NeedsManagerApproval
        // Without these, the retry cannot rebuild the scan.
        assertEquals("COL_000123", needs.collectionId)
        assertEquals("TAG-1", needs.sourceBarcode)
        assertEquals("1600000301", needs.requestedMaterialCode)
        assertEquals("1/2", needs.bagSizeOption)
        assertEquals(3.0, needs.bagCount!!, 0.001)
        assertEquals("Over tolerance", needs.reason)
    }

    @Test
    fun `an approved retry sends the original scan plus a scoped authorization token`() = runTest {
        whenever(mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(IngredientScanResultResponse(collectionId = "COL_000123"), NextAction.SCAN_INGREDIENT))

        useCase.scanIngredient(
            collectionId = "COL_000123", sourceBarcode = "TAG-1",
            bagSizeOption = "1/2", bagCount = 3.0, requestedMaterialCode = "1600000301",
            managerUsername = "manager1", managerPassword = "secret",
            auditReason = "Approved additional bag after verified spillage.",
        )

        verify(mockMqtt).request(
            eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
            argThat<Any> {
                this is com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanPayload &&
                    sourceBarcode == "TAG-1" && bagSizeOption == "1/2" && bagCount == 3.0 &&
                    authorizationToken == "auth-token-1" &&
                    auditReason == "Approved additional bag after verified spillage."
            },
            eq("COL_000123"), eq(IngredientScanResultResponse::class.java),
        )
    }

    @Test
    fun `an ordinary scan omits the authorization field entirely`() = runTest {
        // The contract forbids sending null or "" as a stand-in for absence; Gson omits nulls.
        // An unapproved first scan must also not trigger a manager exchange at all.
        whenever(mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(IngredientScanResultResponse(), NextAction.SCAN_INGREDIENT))

        useCase.scanIngredient("COL_000123", "TAG-1", "1600000301", bagSizeOption = "full", bagCount = 1.0)

        verify(mockMqtt).request(
            eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
            argThat<Any> {
                this is com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientScanPayload &&
                    authorizationToken == null && auditReason == null
            },
            any(), eq(IngredientScanResultResponse::class.java),
        )
    }

    @Test
    fun `a scan against an unarrived pallet asks for recovery`() = runTest {
        whenever(
            mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java))
        ).thenReturn(
            MqttOutcome.Rejected(
                IngredientScanResultResponse(), null, "Pallet is not at Station 2", NextAction.RECOVER_HOLDING
            )
        )

        val outcome = useCase.scanIngredient("COL_000123", "TAG-1", "1600000301", bagSizeOption = "full", bagCount = 1.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsRecovery)
        assertEquals("Pallet is not at Station 2", (outcome as IngredientScanOutcome.NeedsRecovery).reason)
    }

    @Test
    fun `scanIngredient plainly rejected returns Rejected`() = runTest {
        whenever(
            mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java))
        ).thenReturn(
            MqttOutcome.Rejected(IngredientScanResultResponse(), null, "Unknown pallet", NextAction.NONE)
        )

        val outcome = useCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.Rejected)
        assertEquals("Unknown pallet", (outcome as IngredientScanOutcome.Rejected).reason)
    }

    @Test
    fun `scanIngredient returns failure when disconnected`() = runTest {
        whenever(
            mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java))
        ).thenReturn(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    @Test
    fun `scanIngredient with quantity sends quantity and no bag fields`() = runTest {
        whenever(
            mockMqtt.request(
                eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(),
                eq(IngredientScanResultResponse::class.java)
            )
        ).thenReturn(MqttOutcome.Accepted(IngredientScanResultResponse(), NextAction.SCAN_INGREDIENT))

        useCase.scanIngredient("COL_1", "TAG-1", "MAT-BULK", quantity = 123.4)

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), any(), eq(IngredientScanResultResponse::class.java))
        }.firstValue as IngredientScanPayload
        assertEquals(123.4, payload.quantity!!, 0.0)
        assertNull(payload.bagSizeOption)
        assertNull(payload.bagCount)
    }

    @Test
    fun `scanIngredient refuses both shapes or neither without touching the wire`() = runTest {
        val both = useCase.scanIngredient("COL_1", "TAG-1", "MAT-1",
            bagSizeOption = "full", bagCount = 1.0, quantity = 5.0)
        val neither = useCase.scanIngredient("COL_1", "TAG-1", "MAT-1")
        assertTrue(both.isFailure)
        assertTrue(neither.isFailure)
        verifyNoInteractions(mockMqtt)
    }

    // --- waiveShortBags ---

    @Test
    fun `a waiver sends an authorization token on its first submission and carries no pallet`() = runTest {
        // Not a reject-then-retry: there is no scan to fail first.
        whenever(mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(MqttOutcome.Accepted(IngredientScanResultResponse(collectionId = "COL_000123"), NextAction.SCAN_INGREDIENT))

        useCase.waiveShortBags(
            collectionId = "COL_000123", requestedMaterialCode = "1600000301", shortBagCount = 1.0,
            managerUsername = "manager1", managerPassword = "secret",
            auditReason = "One damaged bag unavailable.",
        )

        verify(mockMqtt).request(
            eq("ingredient_scan_requested"), eq("ingredient_scan_result"),
            argThat<Any> {
                this is com.mitas.ppnam.station2aa.data.mqtt.dto.ShortBagWaiverPayload &&
                    collectionId == "COL_000123" &&
                    requestedMaterialCode == "1600000301" &&
                    shortBagCount == 1.0 &&
                    authorizationToken == "auth-token-1" &&
                    auditReason == "One damaged bag unavailable."
            },
            eq("COL_000123"), eq(IngredientScanResultResponse::class.java),
        )
    }

    @Test
    fun `a waiver without credentials surfaces as needing approval, not a generic failure`() = runTest {
        whenever(mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(
                MqttOutcome.Rejected(
                    IngredientScanResultResponse(requiresManagerApproval = true),
                    null, "Manager approval required.", NextAction.RETRY_WITH_MANAGER_APPROVAL,
                )
            )

        val outcome = useCase.waiveShortBags("COL_000123", "1600000301", 1.0, "", "", "One damaged bag unavailable.")
            .getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsApprovalForWaiver)
    }

    @Test
    fun `an accepted waiver returns the refreshed lines`() = runTest {
        whenever(mockMqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java)))
            .thenReturn(
                MqttOutcome.Accepted(
                    IngredientScanResultResponse(
                        collectionId = "COL_000123",
                        ingredients = listOf(
                            BomLineResponse(lineNumber = 0, materialCode = "1600000301",
                                requiredQuantity = 532.049, approvedShortBags = 1.0, bagSize = "25.000 kg")
                        ),
                    ),
                    NextAction.SCAN_INGREDIENT,
                )
            )

        val outcome = useCase.waiveShortBags("COL_000123", "1600000301", 1.0, "manager1", "secret", "One damaged bag unavailable.")
            .getOrThrow()

        // A waiver adjusts the line's requirement directly; it never produces a scanned line.
        val lines = (outcome as IngredientScanOutcome.Accepted).updatedLines
        assertEquals(1.0, lines.single().approvedShortBags!!, 0.001)
    }

    // --- recoverHolding ---

    @Test
    fun `recoverHolding delegates to PalletUseCase rather than re-implementing the message`() = runTest {
        whenever(mockPalletUseCase.recoverToHolding(eq("TAG-1"), eq("COL_000123"), any()))
            .thenReturn(Result.success(mock()))

        val result = useCase.recoverHolding("COL_000123", "TAG-1")

        assertTrue(result.isSuccess)
        verify(mockPalletUseCase).recoverToHolding(eq("TAG-1"), eq("COL_000123"), any())
    }

    @Test
    fun `recoverHolding treats a blank collectionId as null`() = runTest {
        whenever(mockPalletUseCase.recoverToHolding(eq("TAG-1"), eq(null), any()))
            .thenReturn(Result.success(mock()))

        val result = useCase.recoverHolding("", "TAG-1")

        assertTrue(result.isSuccess)
        verify(mockPalletUseCase).recoverToHolding(eq("TAG-1"), eq(null), any())
    }

    @Test
    fun `recoverHolding surfaces PalletUseCase failure`() = runTest {
        whenever(mockPalletUseCase.recoverToHolding(eq("TAG-1"), eq("COL_000123"), any()))
            .thenReturn(Result.failure(Exception("Pallet is blocked")))

        val result = useCase.recoverHolding("COL_000123", "TAG-1")

        assertTrue(result.isFailure)
        assertEquals("Pallet is blocked", result.exceptionOrNull()?.message)
    }

}
