package com.mitas.ppnam.station2aa.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.DangerRed
import com.mitas.ppnam.station2aa.ui.theme.GraphiteBorder
import com.mitas.ppnam.station2aa.ui.theme.GraphiteSurface
import com.mitas.ppnam.station2aa.ui.theme.TextMuted
import com.mitas.ppnam.station2aa.ui.theme.TextPrimary
import java.time.LocalTime

@Composable
fun HomeScreen(
    onOpenJobCards: () -> Unit,
    onOpenMixingBoard: () -> Unit,
    onFixATag: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onExitApp: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    var showExitDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.logoutEvent.collect { onLogout() }
    }

    // Home is the actual post-login root now (Login lands here via
    // popUpTo(LOGIN){inclusive=true}), so Back has nothing left to pop. Same accidental-exit
    // hazard Login guards against, and the same fix: intercept Back and ask before leaving,
    // rather than silently dropping to the Android launcher on a shared, gloved handheld.
    BackHandler {
        showExitDialog = true
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Close the app?", color = TextPrimary) },
            text = { Text("You'll leave PPNAM Station 2 and return to the home screen.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    onExitApp()
                }) { Text("Close", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Stay") }
            },
            containerColor = GraphiteSurface
        )
    }

    AppScaffold(
        title = "Station 2",
        status = connectionStatus,
        operatorName = session?.operatorName,
        operatorRole = session?.role,
        onSettings = onSettings,
        onLogout = viewModel::logout,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val greeting = greetingForHour(LocalTime.now().hour)
            Text(
                text = session?.operatorName?.let { "$greeting, $it" } ?: greeting,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
            )
            HomeTile(
                title = "Job Cards",
                subtitle = "Start or resume a job",
                icon = Icons.Filled.Assignment,
                onClick = onOpenJobCards,
            )
            HomeTile(
                title = "Mixing Board",
                subtitle = "Check or run machines",
                icon = Icons.Filled.Science,
                onClick = onOpenMixingBoard,
            )
            HomeTile(
                title = "Fix a Tag",
                subtitle = "RFID recovery",
                icon = Icons.Filled.WifiTethering,
                onClick = onFixATag,
            )
        }
    }
}

/** Shift-appropriate greeting for the current hour — a fixed "Good morning" read wrong for most of a floor shift. */
private fun greetingForHour(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 18 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun HomeTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GraphiteBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}
