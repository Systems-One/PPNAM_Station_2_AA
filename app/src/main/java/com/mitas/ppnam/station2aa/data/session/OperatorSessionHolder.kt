package com.mitas.ppnam.station2aa.data.session

import com.mitas.ppnam.station2aa.domain.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Station 2 actions the UI knows how to show or hide a control for.
 *
 * Only the handful the app actually renders a button for — an exhaustive mirror of the server's
 * vocabulary would rot silently the moment Station 2 adds one. Measured on the wire: an Operator
 * carries 17 actions, an Admin 24, and every constant below is in the Admin-only difference.
 */
object StationAction {
    const val MACHINE_FORCE_CLOSE = "machine_force_close"
    const val INGREDIENT_COLLECTION_CANCEL = "ingredient_collection_cancel"
    const val INGREDIENT_APPROVE_SHORT_BAG = "ingredient_approve_short_bag"
}

data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    /** Display and audit only. No rule in the contract gates on role — never branch on this. */
    val role: String,
    val sessionState: SessionState = SessionState.Active,
    val sessionExpiresAtUtc: Instant? = null,
    /** A UI display hint only. The contract forbids enforcing anything with this list. */
    val allowedActions: List<String> = emptyList(),
    /** A UI display hint only. */
    val allowedTabs: List<String> = emptyList()
) {
    /**
     * Whether to OFFER [action] in the UI. Presentation only — never authorisation.
     *
     * The contract is explicit that [allowedActions] is a display hint and that Station 2 alone
     * decides what a session may do; every request still travels with its own credentials and is
     * re-checked server-side, so a wrong answer here can only ever cost a control, never grant
     * one. That is precisely why the app previously ignored the field entirely — but ignoring it
     * meant an operator discovered they lacked a privilege only after a 2.5–7.6 s round trip and a
     * generic rejection, with all four roles rendering an identical UI.
     *
     * Fails OPEN on an empty list: a session that arrived without the hint (an older Station 2, a
     * trimmed payload) must render the full UI and let the server reject, rather than silently
     * hiding controls the operator is entitled to.
     */
    fun canShow(action: String): Boolean = allowedActions.isEmpty() || action in allowedActions
}

/** [OperatorSession.canShow] for a possibly-absent session — no session yet means show nothing. */
fun OperatorSession?.canShow(action: String): Boolean = this?.canShow(action) ?: false

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

    fun currentSessionIdOrEmpty(): String = _session.value?.operatorSessionId ?: ""
}
