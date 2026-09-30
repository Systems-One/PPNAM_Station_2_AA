package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.auth.ScramExchange
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Session
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramProofResponse
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.SessionState
import kotlinx.coroutines.test.runTest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuthUseCaseTest {

    private lateinit var scramExchange: ScramExchange
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var useCase: AuthUseCase

    /** What a successful rev2.1 SCRAM proof returns under `data`. */
    private val provedLogin = ScramProofResponse(
        serverSignature = "verified-by-ScramExchange",
        session = Rev2Session(
            sessionId = "session-id",
            operatorId = "OP-001",
            displayName = "Operator One",
            role = "Worker",
            expiresAtUtc = "2026-09-30T18:00:00.000000Z",
            sessionState = "Active",
            isActive = true,
        ),
    )

    @Before
    fun setup() {
        scramExchange = mock()
        sessionHolder = OperatorSessionHolder()
        useCase = AuthUseCase(sessionHolder, scramExchange)
    }

    private suspend fun stubScram(result: Result<ScramProofResponse>) {
        whenever(scramExchange.authenticate(any(), any())).thenReturn(result)
    }

    @Test
    fun `a login runs a SCRAM exchange with the operator's credentials`() = runTest {
        stubScram(Result.success(provedLogin))

        useCase.login("operator1", "secret")

        verify(scramExchange).authenticate("operator1", "secret")
    }

    @Test
    fun `a successful login stores the session`() = runTest {
        stubScram(Result.success(provedLogin))

        val session = useCase.login("operator1", "secret").getOrThrow()

        assertEquals("session-id", session.operatorSessionId)
        assertEquals("OP-001", session.operatorId)
        assertEquals("Operator One", session.operatorName)
        assertEquals("Worker", session.role)
        assertEquals("session-id", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `a proved login with no session id is still a failure`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = provedLogin.session!!.copy(sessionId = ""))))

        val result = useCase.login("operator1", "secret")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a failed SCRAM exchange fails the login and stores no session`() = runTest {
        stubScram(Result.failure(Exception("Incorrect username or password.")))

        val result = useCase.login("operator1", "wrong")

        assertTrue(result.isFailure)
        assertEquals("Incorrect username or password.", result.exceptionOrNull()?.message)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a successful login carries session state and expiry`() = runTest {
        stubScram(
            Result.success(provedLogin)
        )

        val session = useCase.login("operator1", "secret").getOrThrow()

        assertEquals(SessionState.Active, session.sessionState)
        assertEquals(Instant.parse("2026-09-30T18:00:00.000000Z"), session.sessionExpiresAtUtc)
    }

    @Test
    fun `a login answered with a Closed session is a failure`() = runTest {
        // Accepting a session Station 2 has already closed would strand the operator in a UI that
        // rejects every action.
        stubScram(Result.success(provedLogin.copy(session = provedLogin.session!!.copy(sessionState = "Closed"))))

        val result = useCase.login("operator1", "secret")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `an unparseable expiry does not fail the login`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = provedLogin.session!!.copy(expiresAtUtc = "not-a-date"))))

        val session = useCase.login("operator1", "secret").getOrThrow()

        assertNull(session.sessionExpiresAtUtc)
    }

    @Test
    fun `a proof with no session object fails the login`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = null)))

        val result = useCase.login("operator1", "pass")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a session Station 2 reports inactive fails the login`() = runTest {
        stubScram(Result.success(provedLogin.copy(session = provedLogin.session!!.copy(isActive = false))))

        val result = useCase.login("operator1", "pass")

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `the stored session takes identity from data_session`() = runTest {
        stubScram(Result.success(provedLogin))

        val session = useCase.login("operator1", "pass").getOrThrow()

        assertEquals("session-id", session.operatorSessionId)
        assertEquals("OP-001", session.operatorId)
        assertEquals("Operator One", session.operatorName)
        assertEquals("Worker", session.role)
    }

    @Test
    fun `logout clears the local session and sends nothing`() = runTest {
        stubScram(Result.success(provedLogin))
        useCase.login("operator1", "pass")

        useCase.logout()

        assertNull(sessionHolder.session.value)
    }
}
