package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2GeneralRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Job
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2JobSummary
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Material
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Preparation
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class JobLookupUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var useCase: JobLookupUseCase

    private val job = Rev2Job(
        id = "510019068", product = "BAG CARRIER MIDI WHT", unit = "each",
        outputPerMix = 50.0, requiredMixes = 8, allocatedMixes = 2,
        capturedAtUtc = "2026-09-30T10:00:00.000000Z",
        materials = listOf(
            Rev2Material("1600000301", "HD WHITE", "kg", perMix = 69.631, required = 557.049, collected = 100.0),
            Rev2Material("1500000306", "TACKIFIER", "kg", perMix = 1.5, required = 0.0, collected = 0.0, excluded = true),
        ),
    )
    private val snapshot = Rev2Snapshot(
        jobs = listOf(Rev2JobSummary("510019068", "BAG CARRIER MIDI WHT", closed = false, requiredMixes = 8)),
        job = job,
        preparations = listOf(
            Rev2Preparation("PREP_1", "510019068", mixCount = 2, stage = "Collecting"),
            Rev2Preparation("PREP_2", "510099999", mixCount = 1, stage = "Mixing"),
        ),
    )

    @Before
    fun setup() {
        mqtt = mock()
        useCase = JobLookupUseCase(mqtt)
    }

    private suspend fun stub(outcome: MqttOutcome<Rev2Snapshot>) {
        whenever(mqtt.request(any(), any(), any(), eq(Rev2Snapshot::class.java))).thenReturn(outcome)
    }

    private suspend fun sentRequest(): Rev2GeneralRequest {
        val captor = argumentCaptor<Any>()
        verify(mqtt).request(eq("rev2_general_requested"), eq("rev2_general_result"), captor.capture(), eq(Rev2Snapshot::class.java))
        return captor.firstValue as Rev2GeneralRequest
    }

    @Test
    fun `read sends action read with no target`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = null)))
        useCase.read()
        assertEquals(Rev2GeneralRequest(action = "read"), sentRequest())
    }

    @Test
    fun `read with a target sends it as targetId`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        useCase.read("510019068")
        assertEquals(Rev2GeneralRequest(action = "read", targetId = "510019068"), sentRequest())
    }

    @Test
    fun `read maps the job list`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = null)))
        val result = useCase.read() as JobLookupResult.Loaded
        assertEquals(1, result.snapshot.jobs.size)
        assertEquals("510019068", result.snapshot.jobs[0].jobCard)
        assertEquals(8, result.snapshot.jobs[0].requiredMixes)
        assertNull(result.snapshot.detail)
    }

    @Test
    fun `an empty job list is loaded, not a failure`() = runTest {
        stub(MqttOutcome.Accepted(Rev2Snapshot()))
        val result = useCase.read() as JobLookupResult.Loaded
        assertTrue(result.snapshot.jobs.isEmpty())
    }

    @Test
    fun `detail carries the job, its materials and only its own preparations`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        val detail = (useCase.read("510019068") as JobLookupResult.Loaded).snapshot.detail!!
        assertEquals("510019068", detail.jobCard)
        assertEquals(8, detail.requiredMixes)
        assertEquals(2, detail.allocatedMixes)
        assertEquals(2, detail.materials.size)
        assertEquals(listOf("PREP_1"), detail.preparations.map { it.id })
    }

    @Test
    fun `remaining is required minus collected, never negative`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        val materials = (useCase.read("510019068") as JobLookupResult.Loaded).snapshot.detail!!.materials
        assertEquals(457.049, materials[0].remaining, 1e-9)
        assertEquals(0.0, materials[1].remaining, 0.0)
        assertTrue(materials[1].excluded)
    }

    @Test
    fun `read of an unknown target is a failure naming the job card`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = null)))
        val result = useCase.read("510000000") as JobLookupResult.Failed
        assertTrue(result.message, result.message.contains("510000000"))
        assertEquals(1, result.snapshot?.jobs?.size)
    }

    @Test
    fun `lookup sends the job card as action lookup`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        useCase.lookup("510019068")
        assertEquals(Rev2GeneralRequest(action = "lookup", jobCard = "510019068"), sentRequest())
    }

    @Test
    fun `lookup trims whitespace and a trailing newline`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        useCase.lookup("  510019068\n")
        assertEquals("510019068", sentRequest().jobCard)
    }

    @Test
    fun `lookup rejects a non-digit job card without sending anything`() = runTest {
        val result = useCase.lookup("JC-24001") as JobLookupResult.Failed
        assertEquals(JobLookupUseCase.NOT_DIGITS_MESSAGE, result.message)
        verify(mqtt, never()).request(any(), any(), any(), any<Class<Any>>())
    }

    @Test
    fun `lookup rejects a blank job card without sending anything`() = runTest {
        assertTrue(useCase.lookup("   ") is JobLookupResult.Failed)
        verify(mqtt, never()).request(any(), any(), any(), any<Class<Any>>())
    }

    @Test
    fun `lookup rejects non-ASCII digits`() = runTest {
        assertTrue(useCase.lookup("５１００") is JobLookupResult.Failed)
    }

    @Test
    fun `a successful lookup with no job in the reply is a failure`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = null)))
        assertTrue(useCase.lookup("510019068") is JobLookupResult.Failed)
    }

    @Test
    fun `a rev2_rejected lookup shows the operator message and keeps the snapshot`() = runTest {
        stub(MqttOutcome.Rejected(snapshot.copy(job = null), ErrorCode.REV2_REJECTED,
            "Only Standard Planned or Released jobs in header warehouse FAC can be loaded."))
        val result = useCase.lookup("510018531") as JobLookupResult.Failed
        assertEquals("Only Standard Planned or Released jobs in header warehouse FAC can be loaded.", result.message)
        assertEquals(1, result.snapshot?.jobs?.size)
    }

    @Test
    fun `outcome_unconfirmed asks the operator to try again`() = runTest {
        stub(MqttOutcome.Rejected(null, ErrorCode.OUTCOME_UNCONFIRMED, "The result could not be confirmed."))
        val result = useCase.read() as JobLookupResult.Failed
        assertEquals("Station 2 couldn't confirm that — try again", result.message)
        assertNull(result.snapshot)
    }

    @Test
    fun `operator_session_invalid says to sign in again`() = runTest {
        stub(MqttOutcome.Rejected(null, ErrorCode.OPERATOR_SESSION_INVALID, "Sign in on this device before continuing."))
        assertEquals("Your session has ended — sign in again", (useCase.read() as JobLookupResult.Failed).message)
    }

    @Test
    fun `a rejection with no message falls back to a generic line`() = runTest {
        stub(MqttOutcome.Rejected(null, null, null))
        assertEquals("Station 2 rejected the request", (useCase.read() as JobLookupResult.Failed).message)
    }

    @Test
    fun `no response reports the transport failure`() = runTest {
        stub(MqttOutcome.NoResponse(FailureKind.Timeout))
        assertEquals("Station 2 did not respond. Check the station and retry.", (useCase.read() as JobLookupResult.Failed).message)
    }

    @Test
    fun `only timeout and not-connected failures are retryable`() = runTest {
        stub(MqttOutcome.NoResponse(FailureKind.Timeout))
        assertTrue((useCase.read() as JobLookupResult.Failed).retryable)
        stub(MqttOutcome.NoResponse(FailureKind.NotConnected))
        assertTrue((useCase.read() as JobLookupResult.Failed).retryable)
        stub(MqttOutcome.NoResponse(FailureKind.MalformedResponse))
        assertFalse((useCase.read() as JobLookupResult.Failed).retryable)
        stub(MqttOutcome.Rejected(null, null, null))
        assertFalse((useCase.read() as JobLookupResult.Failed).retryable)
        assertFalse((useCase.lookup("JC-1") as JobLookupResult.Failed).retryable)
    }

    @Test
    fun `an unparseable capture time does not fail the read`() = runTest {
        stub(MqttOutcome.Accepted(snapshot.copy(job = job.copy(capturedAtUtc = "not-a-date"))))
        val detail = (useCase.read("510019068") as JobLookupResult.Loaded).snapshot.detail!!
        assertNull(detail.capturedAtUtc)
    }
}
