package com.mitas.ppnam.station2aa.data.session

import com.mitas.ppnam.station2aa.domain.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    /** Display and audit only. Nothing gates on role — never branch on this. */
    val role: String,
    val sessionState: SessionState = SessionState.Active,
    val sessionExpiresAtUtc: Instant? = null,
)

@Singleton
class OperatorSessionHolder @Inject constructor() {
    private val _session = MutableStateFlow<OperatorSession?>(null)
    val session: StateFlow<OperatorSession?> = _session.asStateFlow()

    fun set(session: OperatorSession) {
        _session.value = session
    }

    fun clear() {
        _session.value = null
    }

    /**
     * Clears the session only if it is still [sessionId], atomically — a compare-then-clear would
     * let a re-login slip in between and be wiped. Returns whether it cleared.
     */
    fun clearIf(sessionId: String): Boolean {
        var cleared = false
        _session.update {
            cleared = it?.operatorSessionId == sessionId
            if (cleared) null else it
        }
        return cleared
    }

    fun currentSessionIdOrEmpty(): String = _session.value?.operatorSessionId ?: ""
}
