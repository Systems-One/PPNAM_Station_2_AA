package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import com.mitas.ppnam.station2aa.data.auth.ManagerAuthorization
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Parses bytes captured off the wire, not JSON we wrote ourselves.
 *
 * [MixingMessagesTest]'s fixtures were transcribed from what the app expected, so a field the
 * server spells differently parses to a default there and the suite stays green — which is exactly
 * how `readyMixes[].mixerCode` went unnoticed. This fixture is a verbatim excerpt of
 * `PPNAM/station_2/handheld_1/res/mixing_overview_result` (2026-07-29T09:49:32Z), trimmed to whole array
 * entries with every field and null left as it arrived.
 */
class MixingOverviewWireCaptureTest {

    private val gson = Gson()
    private lateinit var mockMqtt: MqttRepository
    private lateinit var useCase: MixingBoardUseCase

    private val capturedOverview = """
        {
          "accepted": true,
          "reason": null,
          "mixingArea": null,
          "jobCardNumber": null,
          "collectionId": null,
          "equipment": [
            {
              "mixingArea": "MainMixingRoom",
              "equipmentRole": "Mixer",
              "machineCode": "MXR-03",
              "displayName": "Main Mixer 3",
              "isEnabled": true,
              "isAvailable": true,
              "status": "Available",
              "productLayer": null,
              "currentCycleId": null,
              "currentProductionRunId": null,
              "currentJobCardNumber": null,
              "currentCollectionId": null,
              "scanAllowed": true,
              "currentMixBatchIds": [],
              "fixedDestinationMachineCode": null,
              "validDestinationMachineCodes": ["DOL-01", "JAN-02", "MAC-EXT-01"],
              "routeDescription": "Finish the mix, then select one compatible non-Rajoo production destination."
            },
            {
              "mixingArea": "JandiBulkMixing",
              "equipmentRole": "Transfer",
              "machineCode": "JAN-DRUM-01",
              "displayName": "JANDI Transfer Drum",
              "isEnabled": true,
              "isAvailable": true,
              "status": "Available",
              "productLayer": null,
              "currentCycleId": null,
              "currentProductionRunId": null,
              "currentJobCardNumber": null,
              "currentCollectionId": null,
              "scanAllowed": true,
              "currentMixBatchIds": [],
              "fixedDestinationMachineCode": null,
              "validDestinationMachineCodes": [],
              "routeDescription": "JANDI Drum: start and finish decanting before JANDI 4."
            }
          ],
          "readyCollections": [],
          "activeCycles": [],
          "readyMixes": [
            {
              "jobCardNumber": "510019339",
              "productionOrderDocumentNumber": "510019339",
              "collectionId": "COL_000037",
              "mixBatchId": "MIX_000008",
              "mixingArea": "MainMixingRoom",
              "mixerCode": "MXR-03",
              "mixerDisplayName": "Main Mixer 3",
              "productLayer": null,
              "status": "ReadyForAllocation",
              "completionMode": "Normal",
              "quarantineState": null,
              "quarantinedAtUtc": null,
              "quarantineReason": null,
              "dispositionedBy": null,
              "dispositionedAtUtc": null,
              "dispositionReason": null,
              "validNextMachineCodes": ["DOL-01", "JAN-02", "MAC-EXT-01"],
              "nextStepDescription": "Select and scan one compatible non-Rajoo production destination."
            }
          ],
          "quarantinedMixes": [],
          "activeRuns": [],
          "jandiDrum": {
            "status": "Available",
            "jobCardNumber": null,
            "collectionId": null,
            "mixBatchId": null,
            "activeTransferCycleId": null,
            "filledAtUtc": null,
            "scanGuidance": "Finish a JANDI mixer on the Drum route, then scan JAN-DRUM-01."
          },
          "nextAction": "refresh_mixing_overview",
          "schemaVersion": "4.1"
        }
    """.trimIndent()

    @Before
    fun setup() {
        mockMqtt = mock()
        useCase = MixingBoardUseCase(mockMqtt, mock<ManagerAuthorization>())
    }

    @Test
    fun `readyMixes carries the mixer under the key the server actually sends`() {
        val parsed = gson.fromJson(capturedOverview, MixingOverviewResponse::class.java)

        // The server spells this `mixerCode` on a mix and `sourceMixerCode` only on a run input.
        assertEquals("MXR-03", parsed.readyMixes.single().sourceMixerCode)
    }

    @Test
    fun `a normally completed mix stays assignable under the server's ReadyForAllocation status`() {
        val parsed = gson.fromJson(capturedOverview, MixingOverviewResponse::class.java)

        assertTrue(parsed.readyMixes.single().isAssignable)
    }

    @Test
    fun `an empty drum arrives with explicit nulls and must survive mapping`() = runTest {
        val parsed = gson.fromJson(capturedOverview, MixingOverviewResponse::class.java)
        whenever(mockMqtt.request(
            eq("mixing_overview_requested"), eq("mixing_overview_result"), any(), anyOrNull(),
            eq(MixingOverviewResponse::class.java)
        )).thenReturn(MqttOutcome.Accepted(parsed, NextAction.NONE))

        val overview = useCase.fetchOverview().getOrThrow()

        val drum = assertNotNull(overview.jandiDrum)
        assertEquals("Available", overview.jandiDrum!!.status)
        assertNull(overview.jandiDrum!!.jobCardNumber)
        assertNull(overview.jandiDrum!!.collectionId)
        assertNull(overview.jandiDrum!!.mixBatchId)
        assertEquals("MXR-03", overview.readyMixes.single().sourceMixerCode)
        assertNotNull(drum)
    }
}
