package com.mitas.ppnam.station2aa.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the supervisor-PIN gate keeps its failed-attempt count and lockout deadline.
 *
 * It used to live in `SettingsViewModel`, which is scoped to the Settings route — so Back and
 * reopen, or a process restart, reset the counter and made the 5-attempt / 30 s lockout a no-op
 * (audit group c). An interface so unit tests can use an in-memory store.
 */
interface PinLockoutStore {
    var failedAttempts: Int
    /** Wall-clock millis (System.currentTimeMillis) until which Unlock is disabled; 0 = not locked. */
    var lockedOutUntilMs: Long
}

@Singleton
class PrefsPinLockoutStore @Inject constructor(
    @ApplicationContext context: Context,
) : PinLockoutStore {
    private val prefs = context.getSharedPreferences("settings_pin_gate", Context.MODE_PRIVATE)

    override var failedAttempts: Int
        get() = prefs.getInt(KEY_ATTEMPTS, 0)
        set(value) { prefs.edit().putInt(KEY_ATTEMPTS, value).apply() }

    override var lockedOutUntilMs: Long
        get() = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_LOCKED_UNTIL, value).apply() }

    private companion object {
        const val KEY_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_UNTIL = "locked_out_until_ms"
    }
}
