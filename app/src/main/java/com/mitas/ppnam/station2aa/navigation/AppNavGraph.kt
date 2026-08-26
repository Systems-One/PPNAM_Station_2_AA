package com.mitas.ppnam.station2aa.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.ui.login.LoginScreen
import com.mitas.ppnam.station2aa.ui.mixing.IngredientScanScreen
import com.mitas.ppnam.station2aa.ui.mixing.JobLookupScreen
import com.mitas.ppnam.station2aa.ui.mixing.MixingViewModel
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingAreaPickerScreen
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardScreen
import com.mitas.ppnam.station2aa.ui.mixing.board.MixingBoardViewModel
import com.mitas.ppnam.station2aa.ui.components.UpgradeRequiredGate
import com.mitas.ppnam.station2aa.ui.home.HomeScreen
import com.mitas.ppnam.station2aa.ui.rfid.RfidRecoveryScreen
import com.mitas.ppnam.station2aa.ui.session.SessionWatcher
import com.mitas.ppnam.station2aa.ui.settings.SettingsScreen
import com.mitas.ppnam.station2aa.ui.theme.rememberReducedMotion

/** Unwraps the Compose LocalContext to the hosting Activity, or null if it isn't one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    SessionWatcher(navController)
    // Drill-in transitions with symmetric paths: a screen that enters from the right leaves to
    // the right on pop, and the screen underneath parallaxes a third of the way out and back
    // along the same track — so "where did that come from / where does back go" is answered by
    // the motion itself. Critically damped springs (no overshoot; navigation carries no gesture
    // momentum). Reduced motion swaps every slide for a short cross-fade, never a hard cut.
    val reducedMotion = rememberReducedMotion()
    val slideSpec = spring(
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )
    val enter = if (reducedMotion) fadeIn(tween(200)) else
        slideInHorizontally(slideSpec) { it } + fadeIn(tween(150))
    val exit = if (reducedMotion) fadeOut(tween(200)) else
        slideOutHorizontally(slideSpec) { -it / 3 } + fadeOut(tween(250))
    val popEnter = if (reducedMotion) fadeIn(tween(200)) else
        slideInHorizontally(slideSpec) { -it / 3 } + fadeIn(tween(150))
    val popExit = if (reducedMotion) fadeOut(tween(200)) else
        slideOutHorizontally(slideSpec) { it } + fadeOut(tween(250))
    NavHost(
        navController = navController,
        startDestination = NavRoutes.LOGIN,
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { popEnter },
        popExitTransition = { popExit },
    ) {
        composable(NavRoutes.LOGIN) {
            // LocalActivity only exists from activity-compose 1.10; this project is on 1.9.0.
            val activity = LocalContext.current.findActivity()
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) },
                // Login is the start destination — there is no back stack to pop, so leaving
                // means finishing the Activity. Only reached via the explicit confirm dialog.
                onExitApp = { activity?.finish() },
            )
        }
        composable(NavRoutes.HOME) {
            // LocalActivity only exists from activity-compose 1.10; this project is on 1.9.0.
            val activity = LocalContext.current.findActivity()
            HomeScreen(
                onOpenJobCards = { navController.navigate(NavRoutes.MIXING) },
                onOpenMixingBoard = { navController.navigate(NavRoutes.mixingAreas()) },
                onFixATag = { navController.navigate(NavRoutes.RFID_RECOVERY) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                // Navigation on logout is SessionWatcher's job alone — see the comment on
                // MixingAreaPickerScreen's onLogout further down in this graph.
                onLogout = {},
                // Home is the start of the post-login graph now — there is no back stack to
                // pop, so leaving means finishing the Activity. Only reached via the explicit
                // confirm dialog.
                onExitApp = { activity?.finish() },
            )
        }
        composable(NavRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        navigation(startDestination = NavRoutes.JOB_LOOKUP, route = NavRoutes.MIXING) {
            composable(NavRoutes.JOB_LOOKUP) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                JobLookupScreen(
                    onJobFound = { orderNo -> navController.navigate(NavRoutes.ingredientScan(orderNo)) },
                    onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                    // Navigation on logout is SessionWatcher's job alone — see the comment on
                    // MixingAreaPickerScreen's onLogout below.
                    onLogout = {},
                    onRfidLookup = {
                        viewModel.pauseScanning()
                        navController.navigate(NavRoutes.RFID_RECOVERY)
                    },
                    onOpenMixing = { navController.navigate(NavRoutes.mixingAreas()) },
                    // Job Lookup now sits below Home on the back stack — plain pop takes the
                    // operator back to Home. The "close the app?" guard lives on Home now.
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(NavRoutes.INGREDIENT_SCAN) { backStackEntry ->
                val orderNo = backStackEntry.arguments?.getString("orderNo") ?: return@composable
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING)
                }
                val viewModel: MixingViewModel = hiltViewModel(parentEntry)
                IngredientScanScreen(
                    orderNo = orderNo,
                    onStartMixing = { collectionId ->
                        navController.navigate(NavRoutes.mixingAreas(collectionId))
                    },
                    onRfidLookup = {
                        viewModel.pauseScanning()
                        navController.navigate(NavRoutes.RFID_RECOVERY)
                    },
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
        }
        navigation(startDestination = NavRoutes.MIXING_AREAS, route = NavRoutes.MIXING_BOARD) {
            composable(
                NavRoutes.MIXING_AREAS,
                arguments = listOf(navArgument("pendingCollectionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }),
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING_BOARD)
                }
                val viewModel: MixingBoardViewModel = hiltViewModel(parentEntry)
                MixingAreaPickerScreen(
                    pendingCollectionId = backStackEntry.arguments?.getString("pendingCollectionId"),
                    onAreaChosen = { area -> navController.navigate(NavRoutes.mixingAreaBoard(area.wire)) },
                    onBack = { navController.popBackStack() },
                    // Navigation on logout is SessionWatcher's job alone (it reacts to the
                    // session going null, which authUseCase.logout() causes before this event
                    // even fires) — a second navigate(LOGIN){popUpTo(0)} here raced it non-
                    // deterministically, occasionally double-tearing-down/recreating Login.
                    onLogout = {},
                    viewModel = viewModel,
                )
            }
            composable(NavRoutes.MIXING_AREA_BOARD) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(NavRoutes.MIXING_BOARD)
                }
                val viewModel: MixingBoardViewModel = hiltViewModel(parentEntry)
                val area = MixingArea.fromWire(backStackEntry.arguments?.getString("area"))
                if (area == null) {
                    // Only our own navigate() calls mint this route; a bad value is a bug.
                    // Navigation must run as a side effect, not directly in the composable body —
                    // calling popBackStack() here unconditionally on every recomposition of this
                    // branch is exactly the unsafe pattern Navigation-Compose warns against.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    MixingBoardScreen(
                        area = area,
                        onBack = { navController.popBackStack() },
                        // Navigation on logout is SessionWatcher's job alone — see the comment on
                        // MixingAreaPickerScreen's onLogout above.
                        onLogout = {},
                        viewModel = viewModel,
                    )
                }
            }
        }
        composable(NavRoutes.RFID_RECOVERY) {
            RfidRecoveryScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
    UpgradeRequiredGate()
}
