package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var sessionFlow: MutableStateFlow<OperatorSession?>
    private lateinit var connectionFlow: MutableStateFlow<MqttConnectionState>
    private lateinit var coordinator: PendingCommandCoordinator
    private lateinit var viewModel: HomeViewModel

    private val sampleSession = OperatorSession(
        operatorSessionId = "sess-1",
        operatorId = "OP-1",
        operatorName = "Jane Smith",
        role = "Operator"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()
        sessionFlow = MutableStateFlow(sampleSession)

        connectionFlow = MutableStateFlow(MqttConnectionState.DISCONNECTED)
        whenever(mockMqttRepository.connectionState).thenReturn(connectionFlow)
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mockSessionHolder.session).thenReturn(sessionFlow)

        coordinator = mock<PendingCommandCoordinator>()
        whenever(coordinator.pending).thenReturn(MutableStateFlow(emptyList()))
        whenever(coordinator.notices).thenReturn(MutableStateFlow(emptyList()))
        viewModel = HomeViewModel(mockMqttRepository, mockAuthUseCase, mockSessionHolder, coordinator)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `another operator notice is not exposed`() = runTest {
        val own = com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand(messageId = "a", operatorId = "OP-1", action = "capture")
        val foreign = own.copy(messageId = "b", operatorId = "OP-2")
        val coordinator = mock<PendingCommandCoordinator>()
        whenever(coordinator.pending).thenReturn(MutableStateFlow(emptyList()))
        whenever(coordinator.notices).thenReturn(
            MutableStateFlow(
                listOf(
                    com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult.OtherOperator(own),
                    com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult.OtherOperator(foreign),
                )
            )
        )
        val vm = HomeViewModel(mockMqttRepository, mockAuthUseCase, mockSessionHolder, coordinator)
        assertEquals(listOf("a"), vm.recoveryNotices.value.map { it.command.messageId })

        sessionFlow.value = null
        assertEquals(0, vm.recoveryNotices.value.size)
    }

    @Test
    fun `recovery runs again each time the broker reconnects`() = runTest {
        verify(coordinator, times(1)).recoverForCurrentOperator()   // init

        connectionFlow.value = MqttConnectionState.CONNECTED
        verify(coordinator, times(2)).recoverForCurrentOperator()

        connectionFlow.value = MqttConnectionState.RECONNECTING
        verify(coordinator, times(2)).recoverForCurrentOperator()

        connectionFlow.value = MqttConnectionState.CONNECTED
        verify(coordinator, times(3)).recoverForCurrentOperator()
    }

    @Test
    fun `session reflects the current operator session`() = runTest {
        assertEquals(sampleSession, viewModel.session.value)

        sessionFlow.value = null
        assertNull(viewModel.session.value)
    }

    @Test
    fun `logout calls authUseCase and fires logoutEvent`() = runTest {
        val events = mutableListOf<Unit>()
        val job = launch(testDispatcher) { viewModel.logoutEvent.collect { events.add(it) } }

        viewModel.logout()
        advanceUntilIdle()

        verify(mockAuthUseCase).logout()
        assertEquals(1, events.size)
        job.cancel()
    }
}
