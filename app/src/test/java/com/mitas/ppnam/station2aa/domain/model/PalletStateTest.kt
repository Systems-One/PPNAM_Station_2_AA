package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PalletStateTest {

    @Test
    fun `fromWire maps every contract value`() {
        assertEquals(PalletState.Holding, PalletState.fromWire("Holding"))
        assertEquals(PalletState.Mixing, PalletState.fromWire("Mixing"))
        assertEquals(PalletState.AtStation1, PalletState.fromWire("AtStation1"))
        assertEquals(PalletState.Consumed, PalletState.fromWire("Consumed"))
        assertEquals(PalletState.Unknown, PalletState.fromWire("Unknown"))
    }

    @Test
    fun `fromWire degrades an unrecognised value instead of failing`() {
        assertEquals(PalletState.Unknown, PalletState.fromWire("SomethingStation2AddedLater"))
        assertEquals(PalletState.Unknown, PalletState.fromWire(""))
    }

    /**
     * Regression, 2026-07-23. `pallet_lookup_result` for an unknown tag omits palletState, Gson
     * writes that null into the non-null DTO field, and a non-null parameter here threw
     * NullPointerException — crashing the app on every scan of an unrecognised pallet.
     */
    @Test
    fun `fromWire treats a null from the wire as Unknown rather than throwing`() {
        assertEquals(PalletState.Unknown, PalletState.fromWire(null))
    }
}
