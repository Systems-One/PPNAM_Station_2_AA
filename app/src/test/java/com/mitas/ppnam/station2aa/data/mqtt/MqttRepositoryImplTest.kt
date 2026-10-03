package com.mitas.ppnam.station2aa.data.mqtt

import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MqttRepositoryImplTest {

    private lateinit var mockClientFactory: MqttClientFactory
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var repo: MqttRepositoryImpl

    @Before
    fun setup() {
        mockClientFactory = mock()
        mockSettingsRepository = mock()
        repo = MqttRepositoryImpl(
            mockClientFactory,
            mockSettingsRepository,
            OperatorSessionHolder(),
            mock(),
        )
    }

    @Test
    fun `initial connection state is DISCONNECTED`() = runTest {
        assertEquals(MqttConnectionState.DISCONNECTED, repo.connectionState.first())
    }

    @Test
    fun `connect is a no-op when transport is already connected`() = runTest {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("isTransportConnected")
        field.isAccessible = true
        (field.get(repo) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        repo.connect()

        verify(mockClientFactory, never()).build(any(), any(), any())
    }

    @Test
    fun `retryBounded returns immediately on first success`() = runTest {
        var callCount = 0
        val result = repo.retryBounded(maxAttempts = 3, delayMs = 1_000L) {
            callCount++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, callCount)
    }

    @Test
    fun `retryBounded succeeds on a later attempt without exhausting retries`() = runTest {
        var callCount = 0
        val result = repo.retryBounded(maxAttempts = 3, delayMs = 1_000L) {
            callCount++
            if (callCount < 2) throw IllegalStateException("not yet")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(2, callCount)
    }

    @Test
    fun `retryBounded retries up to maxAttempts then rethrows the last error`() = runTest {
        var callCount = 0
        val thrown = try {
            repo.retryBounded(maxAttempts = 3, delayMs = 1_000L) {
                callCount++
                throw IllegalStateException("attempt $callCount failed")
            }
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals(3, callCount)
        assertEquals("attempt 3 failed", thrown?.message)
    }

    @Test
    fun `handleTransportDisconnected sets RECONNECTING for the active client`() = runTest {
        val mockClient: Mqtt5AsyncClient = mock()
        val mqttClientField = MqttRepositoryImpl::class.java.getDeclaredField("mqttClient")
        mqttClientField.isAccessible = true
        mqttClientField.set(repo, mockClient)

        val method = MqttRepositoryImpl::class.java.getDeclaredMethod(
            "handleTransportDisconnected", Mqtt5AsyncClient::class.java
        )
        method.isAccessible = true
        method.invoke(repo, mockClient)

        assertEquals(MqttConnectionState.RECONNECTING, repo.connectionState.value)
        val transportField = MqttRepositoryImpl::class.java.getDeclaredField("isTransportConnected")
        transportField.isAccessible = true
        assertFalse((transportField.get(repo) as java.util.concurrent.atomic.AtomicBoolean).get())
    }

    @Test
    fun `handleTransportDisconnected ignores a stale superseded client`() = runTest {
        val activeClient: Mqtt5AsyncClient = mock()
        val staleClient: Mqtt5AsyncClient = mock()
        val mqttClientField = MqttRepositoryImpl::class.java.getDeclaredField("mqttClient")
        mqttClientField.isAccessible = true
        mqttClientField.set(repo, activeClient)

        val transportField = MqttRepositoryImpl::class.java.getDeclaredField("isTransportConnected")
        transportField.isAccessible = true
        (transportField.get(repo) as java.util.concurrent.atomic.AtomicBoolean).set(true)

        val method = MqttRepositoryImpl::class.java.getDeclaredMethod(
            "handleTransportDisconnected", Mqtt5AsyncClient::class.java
        )
        method.isAccessible = true
        method.invoke(repo, staleClient)

        assertEquals(MqttConnectionState.DISCONNECTED, repo.connectionState.value)
        // Regression guard for the reconnectWith() cycle: disconnecting the stale/old
        // client must NOT clobber isTransportConnected for the still-live active client,
        // otherwise a later connect() call would bypass the no-op guard and issue a
        // redundant connectWith() against an already-connected transport.
        assertTrue((transportField.get(repo) as java.util.concurrent.atomic.AtomicBoolean).get())
    }
}
