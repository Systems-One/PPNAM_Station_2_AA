package com.mitas.ppnam.station2aa

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.window.layout.WindowMetricsCalculator
import com.mitas.ppnam.station2aa.navigation.AppNavGraph
import com.mitas.ppnam.station2aa.ui.theme.PPNAMStation2AATheme
import dagger.hilt.android.AndroidEntryPoint

val LocalWindowSize = compositionLocalOf { DpSize.Unspecified }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicitly dark, not the auto() default: auto() chooses the bar icon colour from the
        // DEVICE's light/dark setting, and this app is dark unconditionally. On a handheld left in
        // light mode that produced dark status-bar icons on the app's near-black bar — the clock
        // and battery were barely legible. SystemBarStyle.dark means "the background behind this
        // bar is dark", i.e. draw light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            // Recomputed only when the configuration actually changes (rotation, multi-window
            // resize, font scale) instead of on every recomposition of this root scope, which
            // was issuing a WindowManager/Binder round trip on each pass.
            val windowSizeDp = remember(configuration) {
                val metrics = WindowMetricsCalculator.getOrCreate()
                    .computeCurrentWindowMetrics(this)
                with(density) {
                    DpSize(metrics.bounds.width().toDp(), metrics.bounds.height().toDp())
                }
            }

            PPNAMStation2AATheme {
                CompositionLocalProvider(LocalWindowSize provides windowSizeDp) {
                    AppNavGraph()
                }
            }
        }
    }
}
