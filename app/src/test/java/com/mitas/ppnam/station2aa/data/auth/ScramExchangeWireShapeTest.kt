package com.mitas.ppnam.station2aa.data.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A full SCRAM login against replies shaped exactly as Station 2 serializes `ScramChallenge` and
 * `ScramProofResult` (with its `StationOperatorSession`): camelCase, enums as names, nulls written
 * out, the manager-only fields present and null. Runs over the real transport so the envelope and
 * the `data` unwrapping are exercised too. The server side is played with [ScramCrypto], which
 * yields the genuine server signature for the proof the device sent.
 */
class ScramExchangeWireShapeTest {

    private val device = "scanner_5c64df8d86a8"
    private val username = "op1"
    private val password = "s3cret-pässword"
    private val salt = Base64.getEncoder().encodeToString(ByteArray(16) { (it * 7 + 3).toByte() })
    private val iterations = 4096
    private lateinit var repo: MqttRepositoryImpl

    /** Set to replace the genuine server signature with a forged one. */
    private var forgedSignature: String? = null
    private var clientNonce = ""
    private var serverFirst = ""
    private var proofMatched = false

    @Before
    fun setup() {
        val identity = mock<DeviceIdentity>()
        whenever(identity.deviceId()).thenReturn(device)
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = OperatorSessionHolder(),
            deviceIdentity = identity,
        )
        repo.publishFn = { topic, bytes -> answer(topic, JsonParser.parseString(String(bytes)).asJsonObject) }
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun answer(topic: String, request: JsonObject) {
        val messageId = request["messageId"].asString
        when (topic.substringAfterLast('/')) {
            "scram_start_requested" -> {
                clientNonce = request["clientNonce"].asString
                val combined = clientNonce + "c2VydmVyLW5vbmNl"
                serverFirst = "r=$combined,s=$salt,i=$iterations"
                reply("scram_challenge", messageId, "Challenge issued.",
                    """{"challengeId":"5b1f0c3a9d2e4f6a8b7c6d5e4f3a2b1c","serverNonce":"$combined",""" +
                        """"salt":"$salt","iterations":$iterations,"serverFirstMessage":"$serverFirst",""" +
                        """"expiresAtUtc":"2026-09-30T10:01:00.000000Z"}""")
            }
            "scram_proof_requested" -> {
                val clientFinalWithoutProof = request["clientFinalWithoutProof"].asString
                val genuine = ScramCrypto.computeProof(
                    password = password,
                    saltBase64 = salt,
                    iterations = iterations,
                    authMessage = ScramCrypto.authMessage(
                        clientFirstBare = ScramCrypto.clientFirstBare(username, clientNonce),
                        serverFirstMessage = serverFirst,
                        clientFinalWithoutProof = clientFinalWithoutProof,
                    ),
                )
                proofMatched = genuine.clientProofBase64 == request["clientProof"].asString
                val signature = forgedSignature ?: genuine.expectedServerSignatureBase64
                reply("scram_proof_result", messageId, "Operator signed in.",
                    """{"serverSignature":"$signature",""" +
                        """"session":{"sessionId":"0d9c8b7a6f5e4d3c2b1a09f8e7d6c5b4","operatorId":"OP-1",""" +
                        """"displayName":"Thandi M","role":"Worker","sourceDevice":"$device",""" +
                        """"loggedInAtUtc":"2026-09-30T10:00:00.000000Z","loggedOutAtUtc":null,""" +
                        """"expiresAtUtc":"2026-09-30T18:00:00.000000Z","sessionState":"Active","isActive":true},""" +
                        """"approver":null,"authorizationToken":null,"authorizationExpiresAtUtc":null}""")
            }
            else -> error("unexpected request on $topic")
        }
    }

    private fun reply(responseType: String, inResponseTo: String, message: String, data: String) {
        val json = """{"schemaVersion":"rev2.1","deviceId":"$device","inResponseToMessageId":"$inResponseTo",""" +
            """"receivedAtUtc":"2026-09-30T10:00:00.000000Z","sentAtUtc":"2026-09-30T10:00:00.031000Z",""" +
            """"durationMs":31.0442,"success":true,"error":"","operatorMessage":"$message",""" +
            """"nextAction":"Follow the saved job or preparation state.","data":$data}"""
        repo.handleIncomingResponse("PPNAM/station_2/$device/res/$responseType", json.toByteArray())
    }

    @Test
    fun `a genuine exchange yields the operator session`() = runTest {
        val result = ScramExchange(repo).authenticate(username, password)

        assertTrue("expected success, got $result", result.isSuccess)
        assertTrue("the server should accept the device's proof", proofMatched)
        val session = result.getOrThrow().session!!
        assertEquals("0d9c8b7a6f5e4d3c2b1a09f8e7d6c5b4", session.sessionId)
        assertEquals("OP-1", session.operatorId)
        assertEquals("Thandi M", session.displayName)
        assertEquals("Worker", session.role)
        assertEquals("Active", session.sessionState)
        assertEquals("2026-09-30T18:00:00.000000Z", session.expiresAtUtc)
        assertTrue(session.isActive)
    }

    @Test
    fun `a bad server signature fails and never hands over the session`() = runTest {
        forgedSignature = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })

        val result = ScramExchange(repo).authenticate(username, password)

        assertTrue("expected failure, got $result", result.isFailure)
        assertNull(result.getOrNull())
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not trusted"))
    }
}
