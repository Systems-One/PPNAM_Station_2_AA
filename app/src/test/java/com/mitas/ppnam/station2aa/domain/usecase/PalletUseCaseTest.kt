package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupResultResponse
import com.mitas.ppnam.station2aa.domain.model.PalletState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PalletUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var useCase: PalletUseCase

    private val holdingPallet = PalletLookupResultResponse(
        found = true,
        usable = true,
        recoverable = false,
        palletRfidTag = "300833120000000000001A2B",
        palletId = "PAL-001",
        productCode = "1600000301",
        productName = "HD WHITE",
        batchNumber = "BATCH-01",
        remainingQuantity = 625.0,
        remainingBags = 25.0,
        unit = "kg",
        localLocation = "Holding",
        palletState = "Holding",
        blocked = false,
    )

    @Before
    fun setup() {
        mqtt = mock()
        useCase = PalletUseCase(mqtt)
    }

    private suspend fun stubLookup(outcome: MqttOutcome<PalletLookupResultResponse>) {
        whenever(
            mqtt.request(
                eq("pallet_lookup_requested"), eq("pallet_lookup_result"),
                any(), any(), eq(PalletLookupResultResponse::class.java)
            )
        ).thenReturn(outcome)
    }

    private suspend fun stubRecovery(outcome: MqttOutcome<PalletLookupResultResponse>) {
        whenever(
            mqtt.request(
                eq("holding_recovery_requested"), eq("holding_recovery_result"),
                any(), any(), eq(PalletLookupResultResponse::class.java)
            )
        ).thenReturn(outcome)
    }

    @Test
    fun `lookup maps an accepted response into PalletInfo`() = runTest {
        stubLookup(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        val info = useCase.lookup("300833120000000000001A2B").getOrThrow()

        assertTrue(info.found)
        assertTrue(info.usable)
        assertFalse(info.recoverable)
        assertEquals(PalletState.Holding, info.palletState)
        assertEquals("PAL-001", info.palletId)
        assertEquals("HD WHITE", info.productName)
        assertEquals(625.0, info.remainingQuantity, 0.001)
        assertEquals("kg", info.unit)
    }

    @Test
    fun `lookup sends the scanned tag as both payload and correlation key`() = runTest {
        stubLookup(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        useCase.lookup("300833120000000000001A2B")

        verify(mqtt).request(
            eq("pallet_lookup_requested"),
            eq("pallet_lookup_result"),
            argThat<Any> { this is PalletLookupPayload && palletRfidTag == "300833120000000000001A2B" },
            eq("300833120000000000001A2B"),
            eq(PalletLookupResultResponse::class.java),
        )
    }

    @Test
    fun `an unknown tag is a successful lookup that simply found nothing`() = runTest {
        // The contract is explicit: accepted means Station 2 answered, not that the answer was
        // favourable. found=false must NOT surface as an error.
        val notFound = PalletLookupResultResponse(found = false, usable = false, recoverable = false)
        stubLookup(MqttOutcome.Accepted(notFound, NextAction.NONE))

        val info = useCase.lookup("UNKNOWN-TAG").getOrThrow()

        assertFalse(info.found)
        assertFalse(info.usable)
        assertFalse(info.recoverable)
    }

    @Test
    fun `a recoverable pallet is reported as recoverable`() = runTest {
        val atStation1 = holdingPallet.copy(
            usable = false, recoverable = true, palletState = "AtStation1", localLocation = "Station 1"
        )
        stubLookup(MqttOutcome.Accepted(atStation1, NextAction.RECOVER_HOLDING))

        val info = useCase.lookup("300833120000000000001A2B").getOrThrow()

        assertFalse(info.usable)
        assertTrue(info.recoverable)
        assertEquals(PalletState.AtStation1, info.palletState)
    }

    @Test
    fun `a blocked pallet in a recoverable state is still recoverable`() = runTest {
        // recoverable is decided by palletState ALONE — blocked is an independent overlay.
        val blockedAtStation1 = holdingPallet.copy(
            usable = false, recoverable = true, palletState = "AtStation1", blocked = true
        )
        stubLookup(MqttOutcome.Accepted(blockedAtStation1, NextAction.RECOVER_HOLDING))

        val info = useCase.lookup("300833120000000000001A2B").getOrThrow()

        assertTrue(info.recoverable)
        assertTrue(info.blocked)
        assertFalse(info.usable)
    }

    @Test
    fun `usable and recoverable are read from the response, never recomputed`() = runTest {
        // Deliberately self-contradictory: state says Holding and unblocked with stock, which a
        // client re-deriving the rule would call usable. Station 2 says otherwise, and Station 2 wins.
        val contradictory = holdingPallet.copy(usable = false, recoverable = true)
        stubLookup(MqttOutcome.Accepted(contradictory, NextAction.NONE))

        val info = useCase.lookup("300833120000000000001A2B").getOrThrow()

        assertFalse("usable must come from the response", info.usable)
        assertTrue("recoverable must come from the response", info.recoverable)
    }

    @Test
    fun `every contract pallet state maps from its wire value`() = runTest {
        val cases = mapOf(
            "Holding" to PalletState.Holding,
            "Mixing" to PalletState.Mixing,
            "AtStation1" to PalletState.AtStation1,
            "Unknown" to PalletState.Unknown,
            "Consumed" to PalletState.Consumed,
        )
        for ((wire, expected) in cases) {
            stubLookup(MqttOutcome.Accepted(holdingPallet.copy(palletState = wire), NextAction.NONE))
            assertEquals(expected, useCase.lookup("tag").getOrThrow().palletState)
        }
    }

    @Test
    fun `an unrecognised pallet state degrades to Unknown rather than crashing`() = runTest {
        stubLookup(MqttOutcome.Accepted(holdingPallet.copy(palletState = "SomeNewState"), NextAction.NONE))
        assertEquals(PalletState.Unknown, useCase.lookup("tag").getOrThrow().palletState)
    }

    @Test
    fun `a rejected lookup fails with the operator-readable reason`() = runTest {
        stubLookup(
            MqttOutcome.Rejected(
                body = PalletLookupResultResponse(),
                errorCode = com.mitas.ppnam.station2aa.data.mqtt.ErrorCode.SESSION_REQUIRED,
                reason = "No valid session on this device.",
                nextAction = NextAction.LOGIN,
            )
        )

        val result = useCase.lookup("tag")

        assertTrue(result.isFailure)
        assertEquals("No valid session on this device.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a timeout fails with a connection message rather than a silent success`() = runTest {
        stubLookup(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.lookup("tag")

        assertTrue(result.isFailure)
        assertEquals("Station 2 did not respond", result.exceptionOrNull()?.message)
    }

    @Test
    fun `being disconnected fails with a connection message`() = runTest {
        stubLookup(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.lookup("tag")

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    @Test
    fun `recovery sends the tag and audit reason`() = runTest {
        stubRecovery(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        useCase.recoverToHolding(
            palletRfidTag = "300833120000000000001A2B",
            collectionId = "COL_000123",
            auditReason = "Pallet is physically at Station 2; fixed door read was missed.",
        )

        verify(mqtt).request(
            eq("holding_recovery_requested"),
            eq("holding_recovery_result"),
            argThat<Any> {
                this is HoldingRecoveryPayload &&
                    palletRfidTag == "300833120000000000001A2B" &&
                    collectionId == "COL_000123" &&
                    auditReason == "Pallet is physically at Station 2; fixed door read was missed."
            },
            eq("COL_000123"),
            eq(PalletLookupResultResponse::class.java),
        )
    }

    @Test
    fun `recovery without a collection omits collectionId and correlates on the tag`() = runTest {
        // collectionId is optional; the contract forbids sending null or "" as a stand-in for absence.
        stubRecovery(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        useCase.recoverToHolding(palletRfidTag = "TAG-1", collectionId = null, auditReason = "Missed door read")

        verify(mqtt).request(
            eq("holding_recovery_requested"),
            eq("holding_recovery_result"),
            argThat<Any> { this is HoldingRecoveryPayload && collectionId == null },
            eq("TAG-1"),
            eq(PalletLookupResultResponse::class.java),
        )
    }

    @Test
    fun `a successful recovery returns the refreshed pallet`() = runTest {
        stubRecovery(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        val info = useCase.recoverToHolding("TAG-1", null, "Missed door read").getOrThrow()

        assertEquals(PalletState.Holding, info.palletState)
        assertTrue(info.usable)
    }

    @Test
    fun `a successful recovery can still return an unusable pallet`() = runTest {
        // Recovery registers physical arrival; it does not clear a quality block. The scanner must
        // show that honest result rather than assuming success means ready-to-scan.
        val recoveredButBlocked = holdingPallet.copy(palletState = "Holding", blocked = true, usable = false)
        stubRecovery(MqttOutcome.Accepted(recoveredButBlocked, NextAction.NONE))

        val info = useCase.recoverToHolding("TAG-1", null, "Missed door read").getOrThrow()

        assertEquals(PalletState.Holding, info.palletState)
        assertTrue(info.blocked)
        assertFalse(info.usable)
    }

    @Test
    fun `a rejected recovery fails with its reason`() = runTest {
        stubRecovery(
            MqttOutcome.Rejected(
                body = PalletLookupResultResponse(),
                errorCode = com.mitas.ppnam.station2aa.data.mqtt.ErrorCode.STATE_CONFLICT,
                reason = "Consumed pallets cannot be recovered.",
                nextAction = NextAction.NONE,
            )
        )

        val result = useCase.recoverToHolding("TAG-1", null, "Missed door read")

        assertTrue(result.isFailure)
        assertEquals("Consumed pallets cannot be recovered.", result.exceptionOrNull()?.message)
    }

    /**
     * Regression, 2026-07-23: scanning any tag Station 2 does not know crashed the whole app.
     *
     * A not-found lookup is `accepted: true, found: false` with every descriptive field omitted —
     * `palletState` included. Gson writes that JSON null straight into the DTO field (it does not
     * honour Kotlin nullability, and a data-class default only applies when the key is *absent*),
     * and `PalletState.fromWire` then threw NullPointerException on its non-null parameter check.
     * On the floor that is an operator scanning a pallet from another line and the app dying.
     */
    @Test
    fun `lookup survives a not-found response whose descriptive fields are all null`() = runTest {
        stubLookup(
            MqttOutcome.Accepted(
                PalletLookupResultResponse(
                    found = false,
                    usable = false,
                    recoverable = false,
                    palletRfidTag = "EPC:UNKNOWN-TAG",
                    palletId = null,
                    productCode = null,
                    productName = null,
                    batchNumber = null,
                    unit = null,
                    localLocation = null,
                    palletState = null,
                    blocked = false,
                ),
                NextAction.NONE,
            )
        )

        val info = useCase.lookup("EPC:UNKNOWN-TAG").getOrThrow()

        assertFalse(info.found)
        assertEquals(PalletState.Unknown, info.palletState)
        assertEquals("EPC:UNKNOWN-TAG", info.palletRfidTag)
        assertEquals("", info.palletId)
        assertEquals("", info.productName)
    }
}
