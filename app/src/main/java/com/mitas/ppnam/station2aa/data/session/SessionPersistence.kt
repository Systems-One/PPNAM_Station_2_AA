package com.mitas.ppnam.station2aa.data.session

import android.content.Context
import android.util.Log
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.security.SecureCredentialStore
import com.mitas.ppnam.station2aa.domain.model.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the signed-in operator across an app restart or crash, so a scanner that dies mid-
 * preparation comes back signed in. The session is encrypted at rest (Keystore, backup-excluded)
 * and restored only while it would still be valid: not closed, not past Station 2's expiry, and
 * not past the inactivity timeout measured in wall-clock time from the last interaction. Station 2
 * still has the final word — an `operator_session_invalid` reply clears it like any other session.
 */
@Singleton
class SessionPersistence @Inject constructor(
    @ApplicationContext context: Context,
    private val store: SecureCredentialStore,
) {
    private val activity by lazy { context.getSharedPreferences(ACTIVITY_PREFS, Context.MODE_PRIVATE) }
    private val gson = WireJson.gson
    private var lastWrite = 0L

    /** The session [restoreInto] brought back; its idle time carries over instead of restarting. */
    @Volatile private var restoredSessionId: String? = null

    /** Puts a still-valid saved session into [holder]; call once at process start, before any UI. */
    fun restoreInto(holder: OperatorSessionHolder, nowMs: Long = System.currentTimeMillis()) {
        val json = store.retrieveSecret(SLOT) ?: return
        val session = try { gson.fromJson(json, Saved::class.java)?.toSession() } catch (e: Exception) { null }
        val lastActivity = activity.getLong(KEY_LAST_ACTIVITY, -1L)
        val timeoutMs = activity.getLong(KEY_TIMEOUT_MS, -1L)
        if (session != null && canRestore(session, lastActivity, timeoutMs, nowMs)) {
            Log.i(TAG, "Restored the operator session after a restart")
            restoredSessionId = session.operatorSessionId
            holder.set(session)
        } else {
            forget()
        }
    }

    /**
     * How long [sessionId] has already been idle: the time since its last recorded interaction
     * when it was restored, else 0 (a fresh login). A restart never buys a fresh timeout window.
     */
    fun idleMsFor(sessionId: String, nowMs: Long = System.currentTimeMillis()): Long {
        if (sessionId != restoredSessionId) return 0L
        val last = activity.getLong(KEY_LAST_ACTIVITY, -1L)
        return if (last < 0) 0L else (nowMs - last).coerceAtLeast(0L)
    }

    /** Saves every new session and forgets it the moment the holder clears. */
    fun install(holder: OperatorSessionHolder, scope: CoroutineScope) {
        scope.launch {
            holder.session.collect { session ->
                if (session == null) forget() else save(session)
            }
        }
    }

    /** A real interaction, in wall-clock time. Throttled. Only touches and logins call this. */
    fun recordActivity(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastWrite in 0 until ACTIVITY_WRITE_INTERVAL_MS) return
        lastWrite = nowMs
        activity.edit().putLong(KEY_LAST_ACTIVITY, nowMs).apply()
    }

    /** The inactivity timeout in force (0 = off); never moves the last-activity time. */
    fun recordTimeout(timeoutMs: Long) {
        activity.edit().putLong(KEY_TIMEOUT_MS, timeoutMs).apply()
    }

    private fun save(session: OperatorSession) {
        if (session.operatorSessionId != restoredSessionId) {
            // A new login is itself the latest interaction.
            restoredSessionId = null
            lastWrite = 0L
            recordActivity()
        }
        if (!store.storeSecret(SLOT, gson.toJson(Saved.of(session)))) {
            Log.w(TAG, "Session not saved; a restart will need a login")
        }
    }

    private fun forget() {
        store.clearSecret(SLOT)
        activity.edit().clear().apply()
        lastWrite = 0L
        restoredSessionId = null
    }

    /** Stored copy with plain fields: Instant is kept as its ISO string. */
    private data class Saved(
        val operatorSessionId: String?,
        val operatorId: String?,
        val operatorName: String?,
        val role: String?,
        val sessionState: String?,
        val sessionExpiresAtUtc: String?,
    ) {
        fun toSession(): OperatorSession? {
            if (operatorSessionId.isNullOrBlank() || operatorId == null) return null
            return OperatorSession(
                operatorSessionId = operatorSessionId,
                operatorId = operatorId,
                operatorName = operatorName.orEmpty(),
                role = role.orEmpty(),
                sessionState = SessionState.entries.firstOrNull { it.name == sessionState } ?: SessionState.Active,
                sessionExpiresAtUtc = sessionExpiresAtUtc?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }

        companion object {
            fun of(s: OperatorSession) = Saved(
                s.operatorSessionId, s.operatorId, s.operatorName, s.role, s.sessionState.name,
                s.sessionExpiresAtUtc?.toString(),
            )
        }
    }

    companion object {
        private const val TAG = "SessionPersistence"
        private const val SLOT = "operator_session"
        private const val ACTIVITY_PREFS = "ppnam_session_activity"
        private const val KEY_LAST_ACTIVITY = "last_activity_ms"
        private const val KEY_TIMEOUT_MS = "timeout_ms"
        private const val ACTIVITY_WRITE_INTERVAL_MS = 15_000L

        /**
         * Whether a saved [session] may come back. [lastActivityMs] is wall-clock (-1 when never
         * recorded); [timeoutMs] 0 means auto sign-out is off, -1 unknown. Anything unknown, or a
         * clock that ran backwards, refuses rather than granting extra time.
         */
        internal fun canRestore(session: OperatorSession, lastActivityMs: Long, timeoutMs: Long, nowMs: Long): Boolean {
            if (session.sessionState == SessionState.Closed) return false
            session.sessionExpiresAtUtc?.let { if (!it.isAfter(Instant.ofEpochMilli(nowMs))) return false }
            if (timeoutMs < 0 || lastActivityMs < 0 || nowMs < lastActivityMs) return false
            return timeoutMs <= 0 || nowMs - lastActivityMs < timeoutMs
        }
    }
}
