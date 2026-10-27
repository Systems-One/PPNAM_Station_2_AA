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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.mitas.ppnam.station2aa.ui.login.LoginScreen
import com.mitas.ppnam.station2aa.ui.components.UpgradeRequiredGate
import com.mitas.ppnam.station2aa.ui.home.HomeScreen
import com.mitas.ppnam.station2aa.ui.joblookup.JobDetailScreen
import com.mitas.ppnam.station2aa.ui.joblookup.JobLookupScreen
import com.mitas.ppnam.station2aa.ui.joblookup.JobLookupViewModel
import com.mitas.ppnam.station2aa.ui.preparation.PreparationScreen
import com.mitas.ppnam.station2aa.ui.preparation.PreparationViewModel
import com.mitas.ppnam.station2aa.ui.preparation.PrepareScreen
import com.mitas.ppnam.station2aa.ui.preparation.PrepareViewModel
import com.mitas.ppnam.station2aa.ui.rajoo.RajooJobScreen
import com.mitas.ppnam.station2aa.ui.rajoo.RajooJobViewModel
import com.mitas.ppnam.station2aa.ui.rajoo.RajooLookupScreen
import com.mitas.ppnam.station2aa.ui.rajoo.RajooLookupViewModel
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
    // LocalActivity only exists from activity-compose 1.10; this project is on 1.9.0.
    val hostActivity = LocalContext.current.findActivity()
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
                onOpenJobCards = { navController.navigate(NavRoutes.JOBS) },
                // Both routes live inside the jobs graph; navigating into it from Home is allowed.
                onOpenPrep = { navController.navigate(NavRoutes.prep(it)) },
                onOpenPrepare = { navController.navigate(NavRoutes.prepare(it)) },
                onOpenRajoo = { navController.navigate(NavRoutes.RAJOO) },
                onOpenRajooJob = { navController.navigate(NavRoutes.rajooJob(it)) },
                onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                // Navigation on logout is SessionWatcher's job alone (it reacts to
                // the session going null, which authUseCase.logout() causes before this event fires).
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
        composable(NavRoutes.RAJOO) {
            RajooLookupScreen(
                onOpenJob = { navController.navigate(NavRoutes.rajooJob(it)) },
                onBack = { navController.popBackStack() },
                viewModel = hiltViewModel<RajooLookupViewModel>(),
            )
        }
        composable(NavRoutes.RAJOO_JOB) { backStackEntry ->
            val jobCard = backStackEntry.arguments?.getString("jobCard") ?: return@composable
            // Its own RajooJobViewModel (scoped to this entry); it reads jobCard from SavedStateHandle.
            RajooJobScreen(
                jobCard = jobCard,
                onBack = { navController.popBackStack() },
                viewModel = hiltViewModel<RajooJobViewModel>(),
            )
        }
        navigation(startDestination = NavRoutes.JOB_LOOKUP, route = NavRoutes.JOBS) {
            composable(NavRoutes.JOB_LOOKUP) { backStackEntry ->
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(NavRoutes.JOBS) }
                JobLookupScreen(
                    onJobFound = { jobCard -> navController.navigate(NavRoutes.jobDetail(jobCard)) },
                    onOpenPrep = { prepId -> navController.navigate(NavRoutes.prep(prepId)) },
                    onSettings = { navController.navigate(NavRoutes.SETTINGS) },
                    onBack = { navController.popBackStack() },
                    viewModel = hiltViewModel<JobLookupViewModel>(parentEntry),
                )
            }
            composable(NavRoutes.JOB_DETAIL) { backStackEntry ->
                val jobCard = backStackEntry.arguments?.getString("jobCard") ?: return@composable
                val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(NavRoutes.JOBS) }
                JobDetailScreen(
                    jobCard = jobCard,
                    onBack = { navController.popBackStack() },
                    onPrepare = { navController.navigate(NavRoutes.prepare(it)) },
                    onOpenPrep = { navController.navigate(NavRoutes.prep(it)) },
                    viewModel = hiltViewModel<JobLookupViewModel>(parentEntry),
                )
            }
            composable(NavRoutes.PREPARE) {
                // Its own PrepareViewModel (scoped to this entry), not the shared JobLookupViewModel.
                PrepareScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPrep = { navController.navigate(NavRoutes.prep(it)) },
                    onPrepared = { prepId ->
                        navController.navigate(NavRoutes.prep(prepId)) {
                            popUpTo(NavRoutes.PREPARE) { inclusive = true }
                        }
                    },
                    viewModel = hiltViewModel<PrepareViewModel>(),
                )
            }
            composable(NavRoutes.PREP) { backStackEntry ->
                val prepId = backStackEntry.arguments?.getString("prepId") ?: return@composable
                // Its own PreparationViewModel (scoped to this entry); it reads prepId from SavedStateHandle.
                PreparationScreen(
                    prepId = prepId,
                    onBack = { navController.popBackStack() },
                    viewModel = hiltViewModel<PreparationViewModel>(),
                )
            }
        }
    }
    UpgradeRequiredGate(onCloseApp = { hostActivity?.finishAffinity() })
}
