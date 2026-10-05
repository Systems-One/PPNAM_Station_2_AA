package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestFingerprintTest {

    @Test
    fun `fingerprint is uppercase hex SHA-256`() {
        assertEquals(
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
            RequestFingerprint.of(ByteArray(0)),
        )
    }

    @Test
    fun `every contract example's requestFingerprint is the hash of its exact request bytes`() {
        val pairs = ContractFixtures.pairs()
        assertEquals(43, pairs.size)
        for (name in pairs) {
            val expected = JsonParser.parseString(ContractFixtures.text("${name}_response.json"))
                .asJsonObject["requestFingerprint"].asString
            assertEquals(name, expected, RequestFingerprint.of(ContractFixtures.bytes("${name}_request.json")))
        }
    }
}
