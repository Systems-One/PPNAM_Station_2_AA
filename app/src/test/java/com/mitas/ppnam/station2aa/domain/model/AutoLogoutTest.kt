package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoLogoutTest {
    @Test
    fun `whole minutes from 0 to 1440 parse, anything else is rejected`() {
        assertEquals(0, AutoLogout.parseMinutes("0"))
        assertEquals(15, AutoLogout.parseMinutes(" 15 "))
        assertEquals(1440, AutoLogout.parseMinutes("1440"))
        assertNull(AutoLogout.parseMinutes("1441"))
        assertNull(AutoLogout.parseMinutes("-1"))
        assertNull(AutoLogout.parseMinutes(""))
        assertNull(AutoLogout.parseMinutes("1.5"))
    }

    @Test
    fun `zero means never, otherwise minutes become milliseconds`() {
        assertEquals(0L, AutoLogout.timeoutMs(0))
        assertEquals(60_000L, AutoLogout.timeoutMs(1))
        assertEquals(900_000L, AutoLogout.timeoutMs(15))
    }
}
