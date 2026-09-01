package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Contract v4.1 §7: "Android must deduplicate responses by response `messageId`... receiving the
 * same response `messageId` must never repeat navigation, sounds, dialogs, or local side effects."
 *
 * QoS 1 guarantees at-least-once, so a duplicate is a normal event, not a fault. Correlation alone
 * doesn't cover it: the side effects that run BEFORE correlation — the session_required clear
 * above all — would otherwise fire once per copy.
 */
class MqttResponseDeduplicationTest {

    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var repository: MqttRepositoryImpl

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        repository = MqttRepositoryImpl(mock(), mock<SettingsRepository>(), sessionHolder, mock())
    }

    private fun deliver(json: String) =
        repository.handleIncomingResponse("PPNAM/station_2/handheld_1/res/operator_context", json.toByteArray())

    @Test
    fun `a duplicate session_required does not re-clear a session that was already replaced`() {
        sessionHolder.set(session("session-A"))
        val rejection = """
            {"messageId":"resp-1","inResponseToMessageId":"req-1","operatorSessionId":"session-A",
             "accepted":false,"errorCode":"session_required"}
        """.trimIndent()

        deliver(rejection)
        assertNull("the first copy must clear the session", sessionHolder.session.value)

        // The operator logs back in; a QoS-1 redelivery of the SAME response then arrives late.
        sessionHolder.set(session("session-B"))
        deliver(rejection)

        assertEquals(
            "a duplicate must not log the operator out of their new session",
            "session-B",
            sessionHolder.session.value?.operatorSessionId,
        )
    }

    @Test
    fun `a duplicate upgrade_required is suppressed rather than re-latched`() {
        val upgrade = """
            {"messageId":"resp-2","inResponseToMessageId":"req-2",
             "accepted":false,"errorCode":"client_upgrade_required"}
        """.trimIndent()

        deliver(upgrade)
        assertTrue(repository.upgradeRequired.value)

        // Latched state, so the observable outcome is the same either way — what this pins is that
        // the duplicate is dropped before reaching any side effect at all.
        deliver(upgrade)
        assertTrue(repository.upgradeRequired.value)
    }

    @Test
    fun `distinct responses are all processed`() {
        sessionHolder.set(session("session-A"))

        // Different messageIds: genuinely different responses, not duplicates.
        deliver(
            """{"messageId":"resp-3","inResponseToMessageId":"req-3","accepted":false,
                "errorCode":"client_upgrade_required"}""".trimIndent()
        )
        deliver(
            """{"messageId":"resp-4","inResponseToMessageId":"req-4","operatorSessionId":"session-A",
                "accepted":false,"errorCode":"session_required"}""".trimIndent()
        )

        assertTrue(repository.upgradeRequired.value)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a response with no messageId is still processed rather than dropped`() {
        // Nothing to deduplicate on. Dropping it would lose a real response to protect against a
        // duplicate we cannot even detect.
        sessionHolder.set(session("session-A"))

        deliver(
            """{"inResponseToMessageId":"req-5","operatorSessionId":"session-A",
                "accepted":false,"errorCode":"session_required"}""".trimIndent()
        )

        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `an uncorrelated server push reaches the push handler`() {
        // 4.1 introduced pushes that answer no request, so "no inResponseToMessageId" is no longer
        // proof a message is junk.
        val seen = mutableListOf<Pair<String, ResponseEnvelope>>()
        repository.setServerPushHandler { topic, envelope, _ -> seen += topic to envelope }

        repository.handleIncomingResponse(
            "PPNAM/station_2/handheld_1/res/active_job_cards_invalidated",
            """{"messageId":"push-1","snapshotRevision":"rev-9","nextAction":"refresh_active_jobs"}"""
                .toByteArray(),
        )

        assertEquals(1, seen.size)
        assertEquals("PPNAM/station_2/handheld_1/res/active_job_cards_invalidated", seen.single().first)
        assertEquals("refresh_active_jobs", seen.single().second.nextAction)
    }

    @Test
    fun `a duplicate server push is suppressed before reaching the handler`() {
        var calls = 0
        repository.setServerPushHandler { _, _, _ -> calls++ }
        val push = """{"messageId":"push-2","snapshotRevision":"rev-9"}"""

        repository.handleIncomingResponse("PPNAM/station_2/handheld_1/res/active_job_cards_invalidated", push.toByteArray())
        repository.handleIncomingResponse("PPNAM/station_2/handheld_1/res/active_job_cards_invalidated", push.toByteArray())

        assertEquals("a redelivered push must not trigger a second reload", 1, calls)
    }

    private fun session(id: String) = OperatorSession(
        operatorSessionId = id,
        operatorId = "OP-001",
        operatorName = "Operator One",
        role = "Operator",
    )
}
