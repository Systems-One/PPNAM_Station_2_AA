package com.mitas.ppnam.station2aa.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when system animations are disabled (accessibility "Remove animations" or developer
 * settings — both zero ANIMATOR_DURATION_SCALE, which Compose's own animations don't consult).
 *
 * Reduced motion is not "no feedback": callers swap springs/slides for snaps and cross-fades,
 * and looping ambient motion (the scan-prompt pulse) for a static equivalent, rather than
 * dropping the state change itself. Read once per composition — changing the setting means
 * leaving the app, and every entry point recomposes from scratch on return.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
