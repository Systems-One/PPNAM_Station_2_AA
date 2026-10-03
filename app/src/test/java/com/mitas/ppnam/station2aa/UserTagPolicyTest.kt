package com.mitas.ppnam.station2aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTagPolicyTest {

    @Test
    fun `badge epc is a user tag`() {
        assertTrue(UserTagPolicy.isUserTag("50505501AABBCCDDEEFF001122334455"))
    }

    @Test
    fun `lower case with surrounding whitespace is a user tag`() {
        assertTrue(UserTagPolicy.isUserTag("  50505501aabbccddeeff001122334455\n"))
    }

    @Test
    fun `item epcs are not user tags`() {
        assertFalse(UserTagPolicy.isUserTag("E280689400005015ABCD1234"))
        assertFalse(UserTagPolicy.isUserTag("5A39F436A9F28F75D72A24205F22E81A"))
    }

    @Test
    fun `null and blank are not user tags`() {
        assertFalse(UserTagPolicy.isUserTag(null))
        assertFalse(UserTagPolicy.isUserTag(""))
        assertFalse(UserTagPolicy.isUserTag("   "))
    }
}
