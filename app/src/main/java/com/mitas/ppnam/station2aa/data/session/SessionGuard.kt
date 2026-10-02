package com.mitas.ppnam.station2aa.data.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.mitas.ppnam.station2aa.R
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AutoLogout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide owner of the inactivity auto sign-out (S1's SessionGuard, without the
 * station-offline trigger — S2 keeps its pill-only policy for that).
 *
 * Ends in [OperatorSessionHolder.clear] with a reason; `SessionWatcher` then navigates to Login,
 * which shows the reason once. Installed once from `PpnamApplication`; the Activity calls [touch]
 * on every interaction and [checkNow] on resume; scans count as activity via [ScanEventBus].
 * Everything runs on the main thread, where the monitor's Handler lives.
 */
@Singleton
class SessionGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionHolder: OperatorSessionHolder,
    private val settingsRepository: SettingsRepository,
    private val scanEventBus: ScanEventBus,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var minutes = AutoLogout.DEFAULT_MINUTES

    private val monitor = InactivityMonitor(
        now = { SystemClock.elapsedRealtime() },
        schedule = { delay, r -> mainHandler.postDelayed(r, delay) },
        cancel = { r -> mainHandler.removeCallbacks(r) },
        onExpired = ::expire,
    )

    fun install() {
        // Start/stop the timer with the session itself.
        scope.launch {
            sessionHolder.session.collect { session ->
                if (session == null) monitor.stop() else applyTimeout()
            }
        }
        // Scanner broadcasts never pass through onUserInteraction.
        scope.launch { scanEventBus.events.collect { touch() } }
    }

    /** Any operator interaction or scanner read. Safe from any thread. */
    fun touch() {
        mainHandler.post { monitor.touch() }
    }

    /** A deadline that passed while the app was backgrounded is caught on the next resume. */
    fun checkNow() {
        mainHandler.post { monitor.checkNow() }
    }

    /** (Re)reads the configured timeout; called when a session starts and after Settings saves. */
    fun applyTimeout() {
        scope.launch {
            if (sessionHolder.session.value == null) return@launch
            minutes = settingsRepository.current().autoLogoutMinutes
            monitor.start(AutoLogout.timeoutMs(minutes))
        }
    }

    /** Idempotent: a second trigger racing the first finds no session and does nothing. */
    private fun expire() {
        if (sessionHolder.session.value == null) return
        Log.i(TAG, "Operator inactive for $minutes min — signing out")
        sessionHolder.clear(
            context.resources.getQuantityString(R.plurals.signed_out_inactivity, minutes, minutes)
        )
    }

    private companion object {
        const val TAG = "SessionGuard"
    }
}
