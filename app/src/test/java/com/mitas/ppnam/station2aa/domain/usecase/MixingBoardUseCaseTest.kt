package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ManagerAuthorization
import com.mitas.ppnam.station2aa.data.mqtt.dto.ReadyCollectionDto
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardsListResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveRunDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLineResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.BomLoadedResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.CollectionResumePayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.EquipmentDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.JandiDrumDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.JandiRoute
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleResultResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.MachineCycleStartPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MixingOverviewPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.MixingOverviewResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.RunInputDto
import com.mitas.ppnam.station2aa.domain.model.LayerInput
import com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MixingBoardUseCaseTest {

    private lateinit var mockMqtt: MqttRepository
    private lateinit var mockManagerAuthorization: ManagerAuthorization
    private lateinit var useCase: MixingBoardUseCase

    @Before
    fun setup() = runTest {
        mockMqtt = mock()
        mockManagerAuthorization = mock()
        // 4.1: force-close first exchanges the manager's credentials for a token scoped to that
        // exact cycle. Default to success so the force-close tests stay about the cycle message.
        whenever(mockManagerAuthorization.authorize(any(), any(), any(), any()))
            .thenReturn(Result.success("auth-token-1"))
        useCase = MixingBoardUseCase(mockMqtt, mockManagerAuthorization)
    }

    @Test
    fun `fetchOverview sends jobCardNumber and collectionId, never productionOrderDocumentNumber`() = runTest {
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(MixingOverviewResponse(), NextAction.NONE))

        useCase.fetchOverview(MixingArea.Main, jobCardNumber = "JC-24001", collectionId = "COL_000123")

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(),
                eq(MixingOverviewResponse::class.java))
        }.firstValue as MixingOverviewPayload
        assertEquals("MainMixingRoom", payload.mixingArea)
        assertEquals("JC-24001", payload.jobCardNumber)
        assertEquals("COL_000123", payload.collectionId)
        // Absent, not null: the contract requires omission for unused optional fields.
        assertNull(MixingOverviewPayload().mixingArea)
        assertNull(MixingOverviewPayload().jobCardNumber)
        assertNull(MixingOverviewPayload().collectionId)
    }

    @Test
    fun `fetchCollectedMaterials resumes by collectionId alone`() = runTest {
        // The JC comes back from Station 2; the handheld does not assert it.
        val response = BomLoadedResponse(
            jobCardNumber = "JC-24001", collectionId = "COL_000123",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-001", materialName = "Resin",
                    collectedQuantity = 550.0, issueType = "im_Manual")))
        whenever(mockMqtt.request(
            eq("collection_resume_requested"), eq("bom_loaded"), any(), anyOrNull(),
            eq(BomLoadedResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.OPEN_MIXING))

        val materials = useCase.fetchCollectedMaterials("COL_000123").getOrThrow()

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(),
                eq(BomLoadedResponse::class.java))
        }.firstValue as CollectionResumePayload
        assertEquals("COL_000123", payload.collectionId)
        assertEquals(listOf("MAT-001"), materials.map { it.materialCode })
    }

    @Test
    fun `fetchOverview sends the area wire value and maps equipment`() = runTest {
        val response = MixingOverviewResponse(
            mixingArea = "JandiBulkMixing",
            equipment = listOf(EquipmentDto(
                mixingArea = "JandiBulkMixing", equipmentRole = "Mixer",
                machineCode = "JAN-MIX-01", displayName = "JANDI Mixer",
                isEnabled = true, isAvailable = true, status = "Available",
                validDestinationMachineCodes = listOf("JAN-02"))))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.SELECT_COLLECTION))

        val overview = useCase.fetchOverview(MixingArea.Jandi).getOrThrow()

        val payload = argumentCaptor<Any>().apply {
            verify(mockMqtt).request(any(), any(), capture(), anyOrNull(), eq(MixingOverviewResponse::class.java))
        }.firstValue as MixingOverviewPayload
        assertEquals("JandiBulkMixing", payload.mixingArea)
        val eq = overview.equipment.single()
        assertEquals("JAN-MIX-01", eq.machineCode)
        assertEquals(MixingArea.Jandi, eq.area)
        assertEquals("Mixer", eq.role)
    }

    @Test
    fun `fetchReadyCollections reads the mixing overview and carries no plan data`() = runTest {
        val response = MixingOverviewResponse(
            readyCollections = listOf(
                ReadyCollectionDto(
                    jobCardNumber = "JC-24001", collectionId = "COL_000123",
                    productName = "HD Film", status = "IngredientsCollected",
                    validMixerCodes = listOf("MXR-01", "MXR-02"),
                    nextAction = "scan_same_machine_to_finish"),
            ))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.SELECT_COLLECTION))

        val ready = useCase.fetchReadyCollections().getOrThrow()

        val collection = ready.single()
        assertEquals("COL_000123", collection.collectionId)
        assertEquals("JC-24001", collection.jobCardNumber)
        // One completed collection creates exactly one mix; the mixers it may start come from
        // the server, not from a saved plan.
        assertEquals(listOf("MXR-01", "MXR-02"), collection.validMixerCodes)
    }

    @Test
    fun `fetchCollectedMaterials resumes the collection and keeps collected manual lines`() = runTest {
        val response = BomLoadedResponse(
            jobCardNumber = "510019068", collectionId = "COL_1",
            ingredients = listOf(
                BomLineResponse(materialCode = "MAT-1", materialName = "Resin",
                    collectedQuantity = 550.0, issueType = "im_Manual"),
                BomLineResponse(materialCode = "MAT-2", materialName = "Uncollected",
                    collectedQuantity = 0.0, issueType = "im_Manual"),
                BomLineResponse(materialCode = "MAT-3", materialName = "Product",
                    collectedQuantity = 5.0, issueType = "im_Backflush"),
            ))
        whenever(mockMqtt.request(
            eq("collection_resume_requested"), eq("bom_loaded"), any(), anyOrNull(),
            eq(BomLoadedResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.OPEN_MIXING))

        val materials = useCase.fetchCollectedMaterials("COL_1").getOrThrow()

        assertEquals(listOf("MAT-1"), materials.map { it.materialCode })
        assertEquals(550.0, materials.single().collectedQty, 0.0)
    }

    private suspend fun captureStart(): MachineCycleStartPayload = argumentCaptor<Any>().apply {
        verify(mockMqtt).request(eq("machine_cycle_start_requested"), any(), capture(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java))
    }.firstValue as MachineCycleStartPayload

    private suspend fun stubStartAccepted() {
        whenever(mockMqtt.request(
            eq("machine_cycle_start_requested"), eq("machine_cycle_result"), any(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(MachineCycleResultResponse(), NextAction.SCAN_SAME_MACHINE_TO_FINISH))
    }

    @Test
    fun `a direct mixer start sends only machineCode and collectionId`() = runTest {
        stubStartAccepted()
        useCase.startMixerFromCollection("DOL-MIX-01", "COL_000123")

        val p = captureStart()
        assertEquals("DOL-MIX-01", p.machineCode)
        assertEquals("COL_000123", p.collectionId)
        assertNull(p.destinationMachineCode)
        assertNull(p.mixBatchId)
        assertNull(p.layerInputs)
        assertNull(p.mainSourceMixBatchId)
        assertNull(p.mainSourceMixerCode)
    }

    @Test
    fun `a JANDI mixer start carries the route`() = runTest {
        stubStartAccepted()
        useCase.startJandiMixer("JAN-MIX-01", "COL_000124", JandiRoute.DRUM)

        val p = captureStart()
        assertEquals("JAN-MIX-01", p.machineCode)
        assertEquals("COL_000124", p.collectionId)
        assertEquals("JAN-DRUM-01", p.destinationMachineCode)
        assertNull(p.mixBatchId)
        assertNull(p.layerInputs)
        assertNull(p.mainSourceMixBatchId)
        assertNull(p.mainSourceMixerCode)
    }

    @Test
    fun `a Rajoo layer start carries its dosing lines`() = runTest {
        stubStartAccepted()
        useCase.startRajooLayer("RAJ-GM-01", "COL_000125",
            listOf(LayerInput("MAT-001", 12.5)))

        val p = captureStart()
        assertEquals("RAJ-GM-01", p.machineCode)
        assertEquals("COL_000125", p.collectionId)
        assertEquals(1, p.layerInputs?.size)
        assertEquals("MAT-001", p.layerInputs?.single()?.materialCode)
        assertEquals(12.5, p.layerInputs?.single()?.dosingQuantity ?: 0.0, 0.0)
        assertNull(p.destinationMachineCode)
        assertNull(p.mixBatchId)
        assertNull(p.mainSourceMixBatchId)
        assertNull(p.mainSourceMixerCode)
    }

    @Test
    fun `a Rajoo layer start with no doses never reaches the wire`() = runTest {
        // 1-5 positive entries are required for each started layer. Rejecting locally tells the
        // operator immediately instead of spending a round trip on invalid_layer_inputs.
        val outcome = useCase.startRajooLayer("RAJ-GM-01", "COL_000125", emptyList())

        assertTrue(outcome is MachineCycleOutcome.Rejected)
        // No areaStatus: nothing was attempted, so the board must keep its current picture.
        assertNull((outcome as MachineCycleOutcome.Rejected).areaStatus)
        verifyNoInteractions(mockMqtt)
    }

    @Test
    fun `a Rajoo layer start with six doses never reaches the wire`() = runTest {
        val six = (1..6).map { LayerInput("MAT-00$it", 1.0) }
        val outcome = useCase.startRajooLayer("RAJ-GM-01", "COL_000125", six)

        assertTrue(outcome is MachineCycleOutcome.Rejected)
        verifyNoInteractions(mockMqtt)
    }

    @Test
    fun `a drum transfer start sends the mix, not a collection`() = runTest {
        stubStartAccepted()
        useCase.startDrumTransfer("JAN-DRUM-01", "MIX_000124")

        val p = captureStart()
        assertEquals("JAN-DRUM-01", p.machineCode)
        assertEquals("MIX_000124", p.mixBatchId)
        assertNull(p.collectionId)
        assertNull(p.destinationMachineCode)
        assertNull(p.layerInputs)
        assertNull(p.mainSourceMixBatchId)
        assertNull(p.mainSourceMixerCode)
    }

    @Test
    fun `a production destination start sends one mix`() = runTest {
        stubStartAccepted()
        useCase.startProductionDestination("EXT-03", "MIX_000126")

        val p = captureStart()
        assertEquals("EXT-03", p.machineCode)
        assertEquals("MIX_000126", p.mixBatchId)
        assertNull(p.collectionId)
        assertNull(p.destinationMachineCode)
        assertNull(p.layerInputs)
        assertNull(p.mainSourceMixBatchId)
        assertNull(p.mainSourceMixerCode)
    }

    @Test
    fun `a JANDI 4 start accepts an exact mix or a source mixer code, never both`() = runTest {
        stubStartAccepted()
        useCase.startJandi4("JAN-04", mainSourceMixBatchId = "MIX_000130", mainSourceMixerCode = null)
        val byMix = captureStart()
        assertEquals("JAN-04", byMix.machineCode)
        assertEquals("MIX_000130", byMix.mainSourceMixBatchId)
        assertNull(byMix.mainSourceMixerCode)
        assertNull(byMix.collectionId)
        assertNull(byMix.destinationMachineCode)
        assertNull(byMix.mixBatchId)
        assertNull(byMix.layerInputs)

        val both = useCase.startJandi4("JAN-04", "MIX_000130", "MXR-02")
        assertTrue(both is MachineCycleOutcome.Rejected)
        // Finding 5: "both" and "neither" are different mistakes and must read differently — the
        // shared "not both" wording used to tell an operator who gave nothing that they gave two.
        assertTrue((both as MachineCycleOutcome.Rejected).reason.contains("not both"))
        val neither = useCase.startJandi4("JAN-04", null, null)
        assertTrue(neither is MachineCycleOutcome.Rejected)
        assertFalse((neither as MachineCycleOutcome.Rejected).reason.contains("not both"))
        // Both branches are rejected locally before anything is sent — confirmed directly,
        // beyond the single wire call already captured for the by-mix case above.
        verify(mockMqtt, times(1)).request(
            eq("machine_cycle_start_requested"), any(), any(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java))
    }

    @Test
    fun `a rejected start still carries the embedded areaStatus`() = runTest {
        val response = MachineCycleResultResponse(
            areaStatus = MixingOverviewResponse(equipment = listOf(EquipmentDto(machineCode = "MXR-01"))))
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.Rejected(response, ErrorCode.EQUIPMENT_IN_USE, "Busy.", NextAction.NONE))

        val outcome = useCase.startMixerFromCollection("MXR-01", "COL_1")

        assertTrue(outcome is MachineCycleOutcome.Rejected)
        val rejected = outcome as MachineCycleOutcome.Rejected
        assertEquals(ErrorCode.EQUIPMENT_IN_USE, rejected.errorCode)
        assertEquals("Busy.", rejected.reason)
        assertEquals(1, rejected.areaStatus!!.equipment.size)
    }

    @Test
    fun `an envelope-level rejection with no areaStatus maps to null`() = runTest {
        val response = MachineCycleResultResponse()
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.Rejected(response, ErrorCode.SESSION_REQUIRED, "No valid session.", NextAction.NONE))

        val outcome = useCase.finish("MXR-01", "CYC_1")

        assertTrue(outcome is MachineCycleOutcome.Rejected)
        assertNull((outcome as MachineCycleOutcome.Rejected).areaStatus)
    }

    @Test
    fun `no response maps to Failed`() = runTest {
        whenever(mockMqtt.request(any(), any(), any(), anyOrNull(), eq(MachineCycleResultResponse::class.java)))
            .thenReturn(MqttOutcome.NoResponse(FailureKind.Timeout))

        val outcome = useCase.finish("MXR-01", "CYC_1")

        assertTrue(outcome is MachineCycleOutcome.Failed)
    }

    @Test
    fun `an active run maps every input, including inputs from different job cards`() = runTest {
        val response = MixingOverviewResponse(
            activeRuns = listOf(ActiveRunDto(
                productionRunId = "RUN_000200", machineCode = "JAN-04",
                status = "InProgress", startedAtUtc = "2026-07-28T08:40:00.000000Z",
                inputs = listOf(
                    RunInputDto(inputRole = "JandiDrum", jobCardNumber = "JC-24001",
                        productionOrderDocumentNumber = "PO-9001", collectionId = "COL_000124",
                        mixBatchId = "MIX_000124", sourceMixerCode = "JAN-MIX-01"),
                    RunInputDto(inputRole = "MainMix", jobCardNumber = "JC-24099",
                        productionOrderDocumentNumber = "PO-9099", collectionId = "COL_000130",
                        mixBatchId = "MIX_000130", sourceMixerCode = "MXR-02"),
                ))))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.NONE))

        val run = useCase.fetchOverview().getOrThrow().activeRuns.single()

        assertEquals("RUN_000200", run.productionRunId)
        assertEquals(listOf("JandiDrum", "MainMix"), run.inputs.map { it.inputRole })
        // Mixed job cards on one run are legal and must survive the mapping intact.
        assertEquals(listOf("JC-24001", "JC-24099"), run.inputs.map { it.jobCardNumber })
        assertEquals(listOf("PO-9001", "PO-9099"), run.inputs.map { it.productionOrderDocumentNumber })
    }

    @Test
    fun `duplicate activeRuns rows sharing a productionRunId are merged into one run`() = runTest {
        // A non-conformant server can flatten a run's accumulated inputs into one activeRuns[]
        // row PER input, all sharing the same productionRunId, instead of one row with a
        // multi-element inputs[] (the shape the test above exercises). The board's LazyColumn
        // keys on productionRunId and Compose crashes outright on a duplicate key, so the
        // mapping must defensively merge these back into a single run.
        val response = MixingOverviewResponse(
            activeRuns = listOf(
                ActiveRunDto(
                    productionRunId = "PRC_000011", machineCode = "RAJ-EXT-01",
                    status = "InProgress", startedAtUtc = "2026-07-30T14:40:00.000000Z",
                    inputs = listOf(RunInputDto(inputRole = "RajooLayer", jobCardNumber = "JC-1",
                        productionOrderDocumentNumber = "PO-1", collectionId = "COL_1",
                        mixBatchId = "MIX_1", sourceMixerCode = "RAJ-GM-01"))),
                ActiveRunDto(
                    productionRunId = "PRC_000011", machineCode = "RAJ-EXT-01",
                    status = "InProgress", startedAtUtc = "2026-07-30T14:40:00.000000Z",
                    inputs = listOf(RunInputDto(inputRole = "RajooLayer", jobCardNumber = "JC-2",
                        productionOrderDocumentNumber = "PO-2", collectionId = "COL_2",
                        mixBatchId = "MIX_2", sourceMixerCode = "RAJ-GM-02"))),
                ActiveRunDto(
                    productionRunId = "PRC_000011", machineCode = "RAJ-EXT-01",
                    status = "InProgress", startedAtUtc = "2026-07-30T14:40:00.000000Z",
                    inputs = listOf(RunInputDto(inputRole = "RajooLayer", jobCardNumber = "JC-3",
                        productionOrderDocumentNumber = "PO-3", collectionId = "COL_3",
                        mixBatchId = "MIX_3", sourceMixerCode = "RAJ-GM-03"))),
            ))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.NONE))

        val activeRuns = useCase.fetchOverview().getOrThrow().activeRuns

        // Exactly one run — a duplicate-keyed list here is what crashes MixingBoardScreen's
        // LazyColumn(key = { it.productionRunId }).
        val run = activeRuns.single()
        assertEquals("PRC_000011", run.productionRunId)
        // All three job cards' inputs survive, merged onto the one run.
        assertEquals(listOf("JC-1", "JC-2", "JC-3"), run.inputs.map { it.jobCardNumber })
    }

    @Test
    fun `the JANDI drum state maps through`() = runTest {
        val response = MixingOverviewResponse(
            jandiDrum = JandiDrumDto(
                status = "Filled", jobCardNumber = "JC-24001", collectionId = "COL_000124",
                mixBatchId = "MIX_000124", filledAtUtc = "2026-07-28T08:25:00.000000Z",
                scanGuidance = "Scan JANDI 4 to consume the drum."))
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.NONE))

        val drum = useCase.fetchOverview().getOrThrow().jandiDrum

        assertNotNull(drum)
        assertEquals("Filled", drum?.status)
        assertEquals("MIX_000124", drum?.mixBatchId)
    }

    @Test
    fun `a machine result carries the destination, resulting status and SAP preview flags`() = runTest {
        val response = MachineCycleResultResponse(
            action = "Started", machineCode = "EXT-03", cycleId = "CYC_000140",
            productionRunId = "RUN_000140", destinationMachineCode = "EXT-03",
            jobCardNumber = "JC-24001", mixBatchId = "MIX_000126",
            resultingStatus = "ProductionInProgress", sapIssuePrepared = true)
        whenever(mockMqtt.request(
            eq("machine_cycle_start_requested"), eq("machine_cycle_result"), any(), anyOrNull(),
            eq(MachineCycleResultResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(response, NextAction.SCAN_SAME_MACHINE_TO_FINISH))

        val outcome = useCase.startProductionDestination("EXT-03", "MIX_000126")

        val accepted = outcome as MachineCycleOutcome.Accepted
        assertEquals("EXT-03", accepted.destinationMachineCode)
        assertEquals("ProductionInProgress", accepted.resultingStatus)
        // No Mixing action posts to SAP; the preview is local and prepared-only.
        assertTrue(accepted.sapIssuePrepared)
    }
}
