package com.mitas.ppnam.station2aa.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.navigation.NavRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SessionWatcherViewModel @Inject constructor(
    sessionHolder: OperatorSessionHolder,
) : ViewModel() {
    val session: StateFlow<OperatorSession?> = sessionHolder.session
}

/**
 * Sends the operator back to login whenever the session disappears.
 *
 * The transport clears the session holder when Station 2 answers `operator_session_invalid` to a
 * request sent with the active session — every subsequent request would be rejected too, so any
 * screen still on display is lying. This makes that a single global rule rather than something each screen must remember.
 *
 * Note: `lifecycle-runtime-compose` (for `collectAsStateWithLifecycle`) is not a dependency in
 * this project, so this uses `collectAsState()` from `androidx.compose.runtime` instead.
 */
@Composable
fun SessionWatcher(
    navController: NavHostController,
    viewModel: SessionWatcherViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    // Only a non-null -> null TRANSITION sends the operator to Login. The effect used to fire on
    // every Activity recreation while session was simply null (nobody logged in yet) and popped a
    // supervisor out of Settings, draft and all (audit S2-02). Saveable so the "had a session"
    // fact itself survives recreation.
    var hadSession by rememberSaveable { mutableStateOf(session != null) }

    LaunchedEffect(session) {
        if (session != null) {
            hadSession = true
            return@LaunchedEffect
        }
        if (!hadSession) return@LaunchedEffect
        hadSession = false
        val current = navController.currentDestination?.route ?: return@LaunchedEffect
        if (current == NavRoutes.LOGIN) return@LaunchedEffect
        navController.navigate(NavRoutes.LOGIN) {
            // Nothing behind us is usable without a session.
            popUpTo(0)
        }
    }
}
