package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralSnapshot
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupResult
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCase
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A `rev2_general_result` exactly as Station 2 serializes it, parsed end to end: through
 * [MqttRepositoryImpl.request] and [MqttRepositoryImpl.handleIncomingResponse] into the DTOs, and
 * through [JobLookupUseCase] into the domain.
 *
 * The body is built by hand from `Rev2ScannerProcessor.ProcessAsync` (the `Reply` envelope and the
 * snapshot projection) and `Rev2MixingModels.cs`, serialized the way its `JsonSerializerOptions`
 * do it: camelCase, enums as names, nulls written out, decimals as bare numbers, the getter-only
 * `Remaining` included, and every field this app ignores present. Until the device is run against
 * a real Station 2 this is the only proof the app reads the wire shape it will actually get.
 */
class Rev2GeneralWireShapeTest {

    private val device = "scanner_5c64df8d86a8"
    private lateinit var repo: MqttRepositoryImpl

    private fun serverReply(inResponseTo: String) = """
        {"schemaVersion":"rev2.1","deviceId":"$device","inResponseToMessageId":"$inResponseTo",
         "receivedAtUtc":"2026-09-30T10:00:00.000000Z","sentAtUtc":"2026-09-30T10:00:00.014200Z",
         "durationMs":14.2311,"success":true,"error":"","operatorMessage":"Current progress.",
         "nextAction":"Follow the saved job or preparation state.",
         "data":{
          "result":{"success":true,"message":"Current progress.","targetId":"510019068","cycleId":null},
          "jobs":[
           {"id":"510019068","product":"BAG CARRIER","closed":false,"requiredMixes":8},
           {"id":"510019070","product":"FILM 40MU","closed":true,"requiredMixes":3}
          ],
          "job":{"id":"510019068","product":"BAG CARRIER","mode":"General","unit":"KG",
           "overallQuantity":400.000,"scopeQuantity":400,"sapCompletedAtLookup":0,
           "outputPerMix":50.0,"requiredMixes":8,
           "materials":[
            {"code":"RM-1001","name":"LLDPE BASE","unit":"KG","perMix":12.5,"required":100.000,"collected":37.25,"excluded":false,"remaining":62.750},
            {"code":"RM-2002","name":"TACKIFIER","unit":"KG","perMix":0,"required":0,"collected":0,"excluded":true,"remaining":0}
           ],
           "capturedAtUtc":"2026-09-30T08:15:00.000000Z","activeSessionId":null,"rajooMachineId":null,
           "closed":false,"ingredientChoices":null,"exceptionListRevision":null,"allocatedMixes":5},
          "preparation":null,
          "preparations":[
           {"id":"PREP-1","jobId":"510019068","mixCount":3,"mixed":1,"produced":0,"stage":"ReadyForMixer",
            "includeTackifier":null,"ingredientChoices":[],"exceptionListRevision":null,"mixerId":null,
            "productionId":null,"cycleId":null,"startedAtUtc":"2026-09-30T09:00:00.000000Z",
            "confirmedAtUtc":null,"startedBy":"OP-1",
            "materials":[{"code":"RM-1001","name":"LLDPE BASE","unit":"KG","perMix":12.5,"required":37.5,"collected":37.25,"excluded":false,"remaining":0.25}]},
           {"id":"PREP-2","jobId":"510019068","mixCount":2,"mixed":2,"produced":2,"stage":"Completed",
            "includeTackifier":true,"ingredientChoices":[{"code":"RM-2002","include":false}],"exceptionListRevision":4,
            "mixerId":"MX-1","productionId":"PR-2","cycleId":"c-77","startedAtUtc":"2026-09-30T07:00:00.000000Z",
            "confirmedAtUtc":"2026-09-30T07:05:00.000000Z","startedBy":"OP-2","materials":[]},
           {"id":"PREP-9","jobId":"510019070","mixCount":3,"mixed":3,"produced":3,"stage":"Completed",
            "includeTackifier":null,"ingredientChoices":[],"exceptionListRevision":null,"mixerId":null,
            "productionId":null,"cycleId":null,"startedAtUtc":"2026-09-29T07:00:00.000000Z",
            "confirmedAtUtc":null,"startedBy":"OP-1","materials":[]}
          ],
          "machines":[
           {"id":"M-1","name":"Mixer 1","area":"Mixing","code":"MX1","enabled":true,"kind":"Main",
            "isProduction":false,"pairId":null,"destinations":["P-1"]}
          ],
          "exceptionListRevision":4,
          "ingredientExceptions":[{"code":"RM-2002","description":"TACKIFIER","note":""}],
          "requiredIngredientChoices":[{"code":"RM-2002","description":"TACKIFIER","note":""}]
         }}
    """.trimIndent()

    @Before
    fun setup() {
        val identity = mock<DeviceIdentity>()
        whenever(identity.deviceId()).thenReturn(device)
        val sessionHolder = OperatorSessionHolder()
        sessionHolder.set(OperatorSession("sess-1", "OP-1", "Op", "Worker"))
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
            deviceIdentity = identity,
        )
        // Station 2 answers as soon as the request is published.
        repo.publishFn = { _, bytes ->
            val messageId = JsonParser.parseString(String(bytes)).asJsonObject["messageId"].asString
            repo.handleIncomingResponse(
                "PPNAM/station_2/$device/res/rev2_general_result", serverReply(messageId).toByteArray()
            )
        }
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    @Test
    fun `a server-shaped general result parses into the snapshot DTO`() = runTest {
        val outcome = repo.request(
            JobLookupUseCase.REQUEST_TYPE, JobLookupUseCase.RESPONSE_TYPE,
            Rev2GeneralRequest(action = "read", targetId = "510019068"), Rev2GeneralSnapshot::class.java,
        )
        assertTrue("expected Accepted, got $outcome", outcome is MqttOutcome.Accepted)
        val snapshot = (outcome as MqttOutcome.Accepted).body

        assertEquals("510019068", snapshot.result?.targetId)
        assertEquals(listOf("510019068", "510019070"), snapshot.jobs.map { it.id })
        assertEquals(true, snapshot.jobs[1].closed)
        val job = snapshot.job!!
        assertEquals("510019068", job.id)
        assertEquals(5, job.allocatedMixes)
        assertEquals(400.0, job.overallQuantity, 0.0)
        assertEquals(50.0, job.outputPerMix, 0.0)
        assertEquals("2026-09-30T08:15:00.000000Z", job.capturedAtUtc)
        assertEquals(37.25, job.materials[0].collected, 0.0)
        assertTrue(job.materials[1].excluded)
        assertEquals(3, snapshot.preparations.size)
        assertEquals("ReadyForMixer", snapshot.preparations[0].stage)
    }

    @Test
    fun `a server-shaped general result maps into the job detail`() = runTest {
        val result = JobLookupUseCase(repo).read("510019068")
        assertTrue("expected Loaded, got $result", result is JobLookupResult.Loaded)
        val snapshot = (result as JobLookupResult.Loaded).snapshot

        assertEquals(listOf("510019068", "510019070"), snapshot.jobs.map { it.jobCard })
        val detail = snapshot.detail!!
        assertEquals("510019068", detail.jobCard)
        assertEquals("BAG CARRIER", detail.product)
        assertEquals("KG", detail.unit)
        assertEquals(8, detail.requiredMixes)
        assertEquals(5, detail.allocatedMixes)
        assertEquals(Instant.parse("2026-09-30T08:15:00Z"), detail.capturedAtUtc)
        assertFalse(detail.closed)

        val base = detail.materials[0]
        assertEquals("RM-1001", base.code)
        assertEquals("LLDPE BASE", base.name)
        assertEquals(12.5, base.perMix, 0.0)
        assertEquals(100.0, base.required, 0.0)
        assertEquals(37.25, base.collected, 0.0)
        // Derived on the device with the server's formula; agrees with the wire's `remaining`.
        assertEquals(62.75, base.remaining, 1e-9)
        assertTrue(detail.materials[1].excluded)

        // Only this job's preparations; PREP-9 belongs to 510019070.
        assertEquals(listOf("PREP-1", "PREP-2"), detail.preparations.map { it.id })
        assertEquals("ReadyForMixer", detail.preparations[0].stage)
        assertEquals(3, detail.preparations[0].mixCount)
        assertEquals(1, detail.preparations[0].mixed)
        assertEquals("Completed", detail.preparations[1].stage)
    }
}
