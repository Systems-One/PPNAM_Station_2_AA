package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.EmptyPayload
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorEntryDto
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorListResponse
import com.mitas.ppnam.station2aa.data.settings.OperatorDirectoryStore
import com.mitas.ppnam.station2aa.domain.model.OperatorEntry
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The login dropdown's operator directory (rev2.1 `operator_list_requested` -> `operator_list`):
 * what the station sends is normalised and cached; any failure leaves the cache alone.
 */
class OperatorDirectoryUseCaseTest {

    private class InMemoryStore(initial: List<OperatorEntry> = emptyList()) : OperatorDirectoryStore {
        var saved: List<OperatorEntry> = initial
        var saves = 0
        override fun load(): List<OperatorEntry> = saved
        override fun save(entries: List<OperatorEntry>) { saved = entries; saves++ }
    }

    private lateinit var mqtt: MqttRepository
    private lateinit var store: InMemoryStore
    private lateinit var useCase: OperatorDirectoryUseCase

    private val cached = listOf(OperatorEntry("op.old", "Old Cache"))

    @Before
    fun setup() {
        mqtt = mock()
        store = InMemoryStore(cached)
        useCase = OperatorDirectoryUseCase(mqtt, store)
    }

    private suspend fun stationAnswers(outcome: MqttOutcome<OperatorListResponse>) {
        whenever(
            mqtt.request(
                eq("operator_list_requested"), eq("operator_list"), any(), eq(OperatorListResponse::class.java)
            )
        ).thenReturn(outcome)
    }

    @Test
    fun `cached reads the store without touching the wire`() = runTest {
        assertEquals(cached, useCase.cached())
        verify(mqtt, org.mockito.kotlin.never()).request(any(), any(), any(), any<Class<Any>>())
    }

    @Test
    fun `refresh sends an envelope-only operator_list_requested`() = runTest {
        stationAnswers(MqttOutcome.Accepted(OperatorListResponse()))
        useCase.refresh()
        verify(mqtt).request(
            eq("operator_list_requested"), eq("operator_list"), eq(EmptyPayload), eq(OperatorListResponse::class.java)
        )
    }

    @Test
    fun `refresh drops blank usernames, falls back displayName, sorts by displayName and persists`() = runTest {
        stationAnswers(
            MqttOutcome.Accepted(
                OperatorListResponse(
                    listOf(
                        OperatorEntryDto("op.tag", "Thandi Tag"),
                        OperatorEntryDto("op.both", "bongi Both"),
                        OperatorEntryDto("", "Nobody"),
                        OperatorEntryDto("   ", "Blank"),
                        OperatorEntryDto("op.nodisplay", ""),
                        OperatorEntryDto("op.anna", "Anna Able"),
                    )
                )
            )
        )

        val list = useCase.refresh()

        val expected = listOf(
            OperatorEntry("op.anna", "Anna Able"),
            OperatorEntry("op.both", "bongi Both"),
            OperatorEntry("op.nodisplay", "op.nodisplay"),
            OperatorEntry("op.tag", "Thandi Tag"),
        )
        assertEquals(expected, list)
        assertEquals(expected, store.saved)
        assertEquals(1, store.saves)
    }

    @Test
    fun `an accepted empty list is valid and replaces the cache`() = runTest {
        stationAnswers(MqttOutcome.Accepted(OperatorListResponse(emptyList())))
        assertEquals(emptyList<OperatorEntry>(), useCase.refresh())
        assertTrue(store.saved.isEmpty())
        assertEquals(1, store.saves)
    }

    @Test
    fun `a rejection returns null and keeps the cache`() = runTest {
        stationAnswers(MqttOutcome.Rejected(null, ErrorCode.INVALID_ENVELOPE, "Check the envelope."))
        assertNull(useCase.refresh())
        assertEquals(cached, store.saved)
        assertEquals(0, store.saves)
    }

    @Test
    fun `a timeout returns null and keeps the cache`() = runTest {
        stationAnswers(MqttOutcome.NoResponse(FailureKind.Timeout))
        assertNull(useCase.refresh())
        assertEquals(cached, store.saved)
        assertEquals(0, store.saves)
    }

    @Test
    fun `the dropdown label is username, em dash, display name`() {
        assertEquals("jsmith \u2014 J Smith", OperatorEntry("jsmith", "J Smith").label)
    }
}
