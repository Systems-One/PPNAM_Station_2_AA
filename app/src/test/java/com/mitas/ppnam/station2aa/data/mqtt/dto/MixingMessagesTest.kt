package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class MixingMessagesTest {

    private val gson = Gson()

    // Field names copied from the simulator's payload builders (handlers/mixing.py),
    // which mirror the contract §7/§8 examples.
    private val overviewJson = """
        {
          "mixingArea": "JandiBulkMixing",
          "equipment": [{
            "mixingArea": "JandiBulkMixing", "equipmentRole": "Mixer",
            "machineCode": "JAN-MIX-01", "displayName": "JANDI 2/3 Shared Bulk Mixer",
            "isEnabled": true, "isAvailable": true, "status": "Available",
            "productLayer": null, "currentCycleId": null,
            "currentJobCardNumber": null, "currentMixBatchIds": [],
            "validDestinationMachineCodes": ["JAN-02", "JAN-03", "JAN-04"],
            "routeDescription": "JANDI 2 or JANDI 3 direct; use the drum cycle before JANDI 4."
          }],
          "activeCycles": [{
            "cycleId": "CYC_000007", "machineCode": "JAN-MIX-01",
            "mixingArea": "JandiBulkMixing", "equipmentRole": "Mixer",
            "jobCardNumber": "510019068", "collectionId": "COL_000003",
            "mixBatchIds": ["MIX_000003"], "productionRunId": null,
            "startedAtUtc": "2026-07-21T08:20:00Z", "startedByOperatorId": "OP-001"
          }],
          "readyMixes": [{
            "mixBatchId": "MIX_000001", "collectionId": "COL_000001",
            "mixingArea": "JandiBulkMixing", "jobCardNumber": "510019068",
            "sourceMixerCode": "JAN-MIX-01", "mixerDisplayName": "JANDI 2/3 Shared Bulk Mixer",
            "productLayer": null, "status": "ReadyForProduction",
            "validNextMachineCodes": ["JAN-DRUM-01", "JAN-02", "JAN-03"],
            "nextStepDescription": "Start one of: JAN-DRUM-01, JAN-02, JAN-03."
          }],
          "activeRuns": [{
            "productionRunId": "RUN_000001", "machineCode": "EXT-03",
            "status": "InProgress", "startedAtUtc": "2026-07-21T08:30:00Z",
            "inputs": [
              {
                "inputRole": "Main", "jobCardNumber": "510019068",
                "productionOrderDocumentNumber": "PO-000001", "collectionId": "COL_000001",
                "mixBatchId": "MIX_000001", "sourceMixerCode": "JAN-MIX-01", "productLayer": null
              },
              {
                "inputRole": "Drum", "jobCardNumber": "510019099",
                "productionOrderDocumentNumber": "PO-000002", "collectionId": "COL_000002",
                "mixBatchId": "MIX_000002", "sourceMixerCode": "JAN-DRUM-01", "productLayer": null
              }
            ]
          }]
        }
    """.trimIndent()

    @Test
    fun `overview response parses the simulator shape`() {
        val r = gson.fromJson(overviewJson, MixingOverviewResponse::class.java)
        assertEquals("JandiBulkMixing", r.mixingArea)
        val eq = r.equipment.single()
        assertEquals("JAN-MIX-01", eq.machineCode)
        assertTrue(eq.isEnabled && eq.isAvailable)
        assertNull(eq.productLayer)
        assertEquals(listOf("JAN-02", "JAN-03", "JAN-04"), eq.validDestinationMachineCodes)
        assertEquals("CYC_000007", r.activeCycles.single().cycleId)
        assertEquals(listOf("JAN-DRUM-01", "JAN-02", "JAN-03"), r.readyMixes.single().validNextMachineCodes)
        val run = r.activeRuns.single()
        assertEquals("RUN_000001", run.productionRunId)
        assertEquals("InProgress", run.status)
        // Mixed-JC composite runs are the whole point of inputs[] — both entries must survive
        // parsing, with different jobCardNumber values, even though Gson would silently drop a
        // stale field name and let this pin the wrong wire shape.
        assertEquals(2, run.inputs.size)
        assertEquals("510019068", run.inputs[0].jobCardNumber)
        assertEquals("510019099", run.inputs[1].jobCardNumber)
        assertNotEquals(run.inputs[0].jobCardNumber, run.inputs[1].jobCardNumber)
        assertEquals("MIX_000001", run.inputs[0].mixBatchId)
        assertEquals("MIX_000002", run.inputs[1].mixBatchId)
        assertEquals("JAN-MIX-01", run.inputs[0].sourceMixerCode)
        assertEquals("JAN-DRUM-01", run.inputs[1].sourceMixerCode)
    }

    @Test
    fun `machine cycle result parses with embedded areaStatus and nullable ids`() {
        val json = """
            {
              "action": "Started", "mixingArea": "MainMixingRoom", "equipmentRole": "Mixer",
              "machineCode": "MXR-01", "cycleId": "CYC_000001",
              "jobCardNumber": "510019068", "collectionId": "COL_000001",
              "mixBatchId": "MIX_000001", "productionRunId": null,
              "affectedMixBatchIds": ["MIX_000001"], "alreadyFinished": false,
              "forceClosed": false, "approverUserId": null, "approverDisplayName": null,
              "approverRole": null, "sapIssueQueued": false, "sapProductionOrderChanged": false,
              "areaStatus": $overviewJson
            }
        """.trimIndent()
        val r = gson.fromJson(json, MachineCycleResultResponse::class.java)
        assertEquals("Started", r.action)
        assertEquals("CYC_000001", r.cycleId)
        assertNull(r.productionRunId)
        assertFalse(r.alreadyFinished)
        assertEquals(1, r.areaStatus.equipment.size)
    }

    @Test
    fun `start payload omits absent optional fields when serialized`() {
        val json = gson.toJson(MachineCycleStartPayload(
            machineCode = "MXR-01", collectionId = "COL_000001"))
        assertFalse("destinationMachineCode must be omitted, not null", json.contains("destinationMachineCode"))
        assertFalse("mixBatchId must be omitted, not null", json.contains("mixBatchId"))
        assertFalse("mainSourceMixBatchId must be omitted, not null", json.contains("mainSourceMixBatchId"))
        assertFalse("mainSourceMixerCode must be omitted, not null", json.contains("mainSourceMixerCode"))
        assertFalse("layerInputs must be omitted, not null", json.contains("layerInputs"))
        assertTrue(json.contains("\"collectionId\":\"COL_000001\""))
    }

    @Test
    fun `mixing area maps wire values both ways`() {
        assertEquals(com.mitas.ppnam.station2aa.domain.model.MixingArea.Jandi,
            com.mitas.ppnam.station2aa.domain.model.MixingArea.fromWire("JandiBulkMixing"))
        assertNull(com.mitas.ppnam.station2aa.domain.model.MixingArea.fromWire("Atlantis"))
        assertEquals(5, com.mitas.ppnam.station2aa.domain.model.MixingArea.entries.size)
    }
}
