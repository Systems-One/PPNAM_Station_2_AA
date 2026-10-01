package com.mitas.ppnam.station2aa.ui.joblookup

import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.JobDetail
import com.mitas.ppnam.station2aa.domain.model.JobLookupSnapshot
import com.mitas.ppnam.station2aa.domain.model.JobSummary
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupResult
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCase
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class JobLookupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var useCase: JobLookupUseCase
    private lateinit var mqtt: MqttRepository
    private lateinit var scanBus: ScanEventBus
    private lateinit var scans: MutableSharedFlow<ScanEvent>
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var vm: JobLookupViewModel
    private var pushHandler: ((String, ResponseEnvelope, String) -> Unit)? = null
    private lateinit var connection: MutableStateFlow<MqttConnectionState>

    private val jobs = listOf(JobSummary("510019068", "BAG CARRIER", 8, false))
    private val detail = JobDetail(
        jobCard = "510019068", product = "BAG CARRIER", unit = "each", outputPerMix = 50.0,
        requiredMixes = 8, allocatedMixes = 2, capturedAtUtc = null, closed = false,
        materials = emptyList(), preparations = emptyList(),
    )
    private val listOnly = JobLookupSnapshot(jobs, null)
    private val withDetail = JobLookupSnapshot(jobs, detail)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mock()
        mqtt = mock()
        scanBus = mock()
        scans = MutableSharedFlow(extraBufferCapacity = 8)
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(OperatorSession("sess-1", "OP-1", "Op", "Worker"))
        whenever(scanBus.events).thenReturn(scans)
        connection = MutableStateFlow(MqttConnectionState.CONNECTED)
        whenever(mqtt.connectionState).thenReturn(connection)
        whenever(mqtt.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mqtt.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        vm = JobLookupViewModel(useCase, mqtt, scanBus, sessionHolder, mock<AuthUseCase>())
        val captor = argumentCaptor<(String, ResponseEnvelope, String) -> Unit>()
        verify(mqtt).setServerPushHandler(captor.capture())
        pushHandler = captor.firstValue
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun push(mode: String = "General") = pushHandler!!(
        "PPNAM/station_2/scanner_x/res/active_job_cards_invalidated",
        ResponseEnvelope(messageId = "p-1", mode = mode, reason = "preparation_created", nextAction = "read"),
        "{}",
    )

    @Test
    fun `refreshList loads the job list`() = runTest {
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Loaded(listOnly))
        vm.refreshList()
        assertEquals(jobs, vm.uiState.value.jobs)
        assertFalse(vm.uiState.value.listLoading)
        assertNull(vm.uiState.value.listError)
    }

    @Test
    fun `a failed refresh keeps the previous list and shows the error`() = runTest {
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Loaded(listOnly))
        vm.refreshList()
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Failed("Station 2 did not respond"))
        vm.refreshList()
        assertEquals(jobs, vm.uiState.value.jobs)
        assertEquals("Station 2 did not respond", vm.uiState.value.listError)
    }

    @Test
    fun `an empty job list is shown as empty, not as an error`() = runTest {
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Loaded(JobLookupSnapshot(emptyList(), null)))
        vm.refreshList()
        assertEquals(emptyList<JobSummary>(), vm.uiState.value.jobs)
        assertNull(vm.uiState.value.listError)
    }

    @Test
    fun `a successful lookup stores the detail and navigates to it`() = runTest {
        whenever(useCase.lookup("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.lookup("510019068")
        assertEquals(detail, vm.uiState.value.detail)
        assertEquals("510019068", vm.navigateToDetail.first())
    }

    @Test
    fun `a rejected lookup shows the message, stays put and applies the snapshot's job list`() = runTest {
        whenever(useCase.lookup("510018531")).thenReturn(
            JobLookupResult.Failed("Only Standard Planned or Released jobs can be loaded.", listOnly)
        )
        vm.lookup("510018531")
        assertEquals("Only Standard Planned or Released jobs can be loaded.", vm.uiState.value.lookupError)
        assertEquals(jobs, vm.uiState.value.jobs)
        assertNull(vm.uiState.value.detail)
        assertFalse(vm.uiState.value.lookupInFlight)
    }

    @Test
    fun `a second lookup while one is in flight is ignored`() = runTest {
        whenever(useCase.lookup(any())).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.setLookupInFlightForTest(true)
        vm.lookup("510019068")
        verify(useCase, never()).lookup(any())
    }

    @Test
    fun `a scan runs a lookup while the lookup screen is active`() = runTest {
        whenever(useCase.lookup("510019068\n")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.setLookupScreenActive(true)
        scans.emit(ScanEvent.Barcode("510019068\n", "CODE128", Instant.EPOCH))
        verify(useCase).lookup("510019068\n")
    }

    @Test
    fun `a scan is ignored while the lookup screen is not active`() = runTest {
        vm.setLookupScreenActive(false)
        scans.emit(ScanEvent.Barcode("510019068", "CODE128", Instant.EPOCH))
        verify(useCase, never()).lookup(any())
    }

    @Test
    fun `openDetail reads the target and stores the detail`() = runTest {
        whenever(useCase.read("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.openDetail("510019068")
        assertEquals(detail, vm.uiState.value.detail)
        assertFalse(vm.uiState.value.detailLoading)
    }

    @Test
    fun `openDetail for the job a lookup just loaded does not read again`() = runTest {
        whenever(useCase.lookup("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.lookup("510019068")
        vm.openDetail("510019068")
        // anyOrNull, not any(): any() never matches read(null), so it would pass vacuously.
        verify(useCase, never()).read(anyOrNull())
    }

    @Test
    fun `a failed openDetail shows the error`() = runTest {
        whenever(useCase.read("510000000")).thenReturn(JobLookupResult.Failed("Station 2 has no General job 510000000"))
        vm.openDetail("510000000")
        assertEquals("Station 2 has no General job 510000000", vm.uiState.value.detailError)
    }

    @Test
    fun `a General push re-reads the list`() = runTest {
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Loaded(listOnly))
        push()
        verify(useCase).read(null)
    }

    @Test
    fun `a push while viewing a detail re-reads that detail`() = runTest {
        whenever(useCase.read("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.openDetail("510019068")
        push()
        verify(useCase, times(2)).read("510019068")
    }

    @Test
    fun `a push with no session sends nothing`() = runTest {
        sessionHolder.clear()
        push()
        verify(useCase, never()).read(anyOrNull())
    }

    @Test
    fun `a push for another mode is ignored`() = runTest {
        push(mode = "Rajoo")
        verify(useCase, never()).read(anyOrNull())
    }

    private fun detailOf(jobCard: String) = detail.copy(jobCard = jobCard)

    @Test
    fun `a late reply for a job left behind does not overwrite the job now viewed`() = runTest {
        val replyA = CompletableDeferred<JobLookupResult>()
        val replyB = CompletableDeferred<JobLookupResult>()
        whenever(useCase.read("A")).doSuspendableAnswer { replyA.await() }
        whenever(useCase.read("B")).doSuspendableAnswer { replyB.await() }
        vm.openDetail("A")
        vm.setLookupScreenActive(true)  // back to the list
        vm.setLookupScreenActive(false)
        vm.openDetail("B")
        replyB.complete(JobLookupResult.Loaded(JobLookupSnapshot(jobs, detailOf("B"))))
        replyA.complete(JobLookupResult.Failed("Station 2 has no General job A"))
        assertEquals("B", vm.uiState.value.detail?.jobCard)
        assertFalse(vm.uiState.value.detailLoading)
        assertNull(vm.uiState.value.detailError)
    }

    @Test
    fun `a late successful reply for a job left behind still applies its job list`() = runTest {
        val replyA = CompletableDeferred<JobLookupResult>()
        val newJobs = listOf(JobSummary("A", "OTHER", 1, false))
        whenever(useCase.read("A")).doSuspendableAnswer { replyA.await() }
        whenever(useCase.read("B")).thenReturn(JobLookupResult.Loaded(JobLookupSnapshot(jobs, detailOf("B"))))
        vm.openDetail("A")
        vm.openDetail("B")
        replyA.complete(JobLookupResult.Loaded(JobLookupSnapshot(newJobs, detailOf("A"))))
        assertEquals("B", vm.uiState.value.detail?.jobCard)
        assertEquals(newJobs, vm.uiState.value.jobs)
        assertFalse(vm.uiState.value.detailLoading)
    }

    @Test
    fun `reopening the same job later reads it again`() = runTest {
        whenever(useCase.lookup("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        whenever(useCase.read("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.lookup("510019068")
        vm.openDetail("510019068")      // straight after the lookup: no read
        vm.setLookupScreenActive(true)  // back to the list
        vm.openDetail("510019068")      // later: reads
        verify(useCase, times(1)).read("510019068")
    }

    @Test
    fun `reopening the same job keeps showing the cached detail while it reloads`() = runTest {
        val reply = CompletableDeferred<JobLookupResult>()
        whenever(useCase.read("510019068"))
            .thenReturn(JobLookupResult.Loaded(withDetail))
            .doSuspendableAnswer { reply.await() }
        vm.openDetail("510019068")
        vm.openDetail("510019068")
        assertEquals(detail, vm.uiState.value.detail)
        reply.complete(JobLookupResult.Loaded(withDetail))
        assertFalse(vm.uiState.value.detailLoading)
    }

    @Test
    fun `a reconnect with a session re-reads the list`() = runTest {
        whenever(useCase.read(null)).thenReturn(JobLookupResult.Loaded(listOnly))
        connection.value = MqttConnectionState.DISCONNECTED
        connection.value = MqttConnectionState.CONNECTED
        verify(useCase, times(1)).read(null)
    }

    @Test
    fun `a reconnect while viewing a detail re-reads that detail`() = runTest {
        whenever(useCase.read("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.openDetail("510019068")
        connection.value = MqttConnectionState.RECONNECTING
        connection.value = MqttConnectionState.CONNECTED
        verify(useCase, times(2)).read("510019068")
        verify(useCase, never()).read(null)
    }

    @Test
    fun `a reconnect with no session reads nothing`() = runTest {
        sessionHolder.clear()
        connection.value = MqttConnectionState.DISCONNECTED
        connection.value = MqttConnectionState.CONNECTED
        verify(useCase, never()).read(anyOrNull())
    }

    @Test
    fun `the initial connected state does not trigger a read`() = runTest {
        verify(useCase, never()).read(anyOrNull())
    }
}
