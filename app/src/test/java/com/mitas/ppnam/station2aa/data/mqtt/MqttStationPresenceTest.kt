package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class MqttStationPresenceTest {

    private lateinit var repo: MqttRepositoryImpl

    @Before
    fun setup() {
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = OperatorSessionHolder(),
        )
    }

    @Test
    fun `station is assumed offline until it announces itself`() = runTest {
        assertFalse(repo.stationOnline.value)
    }

    @Test
    fun `an online presence payload marks the station up`() = runTest {
        repo.handleStationPresence("online".toByteArray())
        assertTrue(repo.stationOnline.value)
    }

    @Test
    fun `an offline presence payload marks the station down`() = runTest {
        repo.handleStationPresence("online".toByteArray())
        repo.handleStationPresence("offline".toByteArray())
        assertFalse(repo.stationOnline.value)
    }

    @Test
    fun `presence parsing tolerates surrounding whitespace and case`() = runTest {
        repo.handleStationPresence("  ONLINE\n".toByteArray())
        assertTrue(repo.stationOnline.value)
    }

    @Test
    fun `an unrecognised presence payload is treated as offline rather than crashing`() = runTest {
        repo.handleStationPresence("online".toByteArray())
        repo.handleStationPresence("{\"unexpected\":true}".toByteArray())
        assertFalse(repo.stationOnline.value)
    }

    @Test
    fun `an empty presence payload is treated as offline`() = runTest {
        repo.handleStationPresence("online".toByteArray())
        repo.handleStationPresence(ByteArray(0))
        assertFalse(repo.stationOnline.value)
    }
}
