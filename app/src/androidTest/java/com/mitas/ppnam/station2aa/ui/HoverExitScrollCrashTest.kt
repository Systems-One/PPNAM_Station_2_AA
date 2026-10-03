package com.mitas.ppnam.station2aa.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the "The ACTION_HOVER_EXIT event was not cleared." crash.
 *
 * Compose UI 1.6.8 stashed an in-bounds ACTION_HOVER_EXIT in `previousMotionEvent` and posted a
 * runnable to replay it, but its ACTION_SCROLL branch overwrote `previousMotionEvent` without
 * clearing `hoverExitReceived`. The next hover event then ran the stale runnable, whose
 * `check(lastEvent.actionMasked == ACTION_HOVER_EXIT)` blew up with an IllegalStateException on the
 * main thread. Android Studio's device mirroring injects exactly this hover-exit-then-scroll pair
 * when the host mouse wheel is turned (androidx b/314269723), which is how the app crashed after
 * login. Fixed upstream in Compose 1.7, where dispatchGenericMotionEvent clears the flag on scroll.
 */
@RunWith(AndroidJUnit4::class)
class HoverExitScrollCrashTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun scrollArrivingBetweenHoverExitAndNextHoverDoesNotCrash() {
        composeRule.setContent { Box(Modifier.fillMaxSize()) }
        composeRule.waitForIdle()

        val composeView = requireNotNull(
            composeRule.activity.window.decorView.findAndroidComposeView()
        ) { "AndroidComposeView not found in the activity view hierarchy" }

        composeRule.runOnUiThread {
            // 1. In-bounds hover exit: stashed, replay runnable posted.
            composeView.dispatchGenericMotionEvent(pointerEvent(MotionEvent.ACTION_HOVER_EXIT))
            // 2. Scroll before the runnable drains: overwrites the stashed event.
            composeView.dispatchGenericMotionEvent(pointerEvent(MotionEvent.ACTION_SCROLL))
            // 3. Any further hover runs the stale runnable -> IllegalStateException on 1.6.8.
            composeView.dispatchGenericMotionEvent(pointerEvent(MotionEvent.ACTION_HOVER_ENTER))
        }

        // Drains the main looper, so a posted-but-not-yet-run replay would also surface here.
        composeRule.waitForIdle()
    }

    private fun pointerEvent(action: Int): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, 100f, 100f, 0).apply {
            source = InputDevice.SOURCE_MOUSE
        }
    }

    private fun View.findAndroidComposeView(): View? {
        if (javaClass.simpleName == "AndroidComposeView") return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findAndroidComposeView()?.let { return it }
        }
        return null
    }
}
