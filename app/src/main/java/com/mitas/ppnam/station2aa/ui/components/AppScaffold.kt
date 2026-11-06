package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    status: ConnectionStatus,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    /** A screen-specific top-bar action (e.g. an overflow menu) rendered next to Settings. */
    actions: (@Composable RowScope.() -> Unit)? = null,
    operatorName: String? = null,
    operatorRole: String? = null,
    onLogout: (() -> Unit)? = null,
    loading: Boolean = false,
    /**
     * Home only (suite standard C2): replace the top bar with the [HomeHeaderCard] (logo, gear,
     * pill, logout + "name · role"). [title] is not drawn; the logo takes its place.
     */
    homeHeader: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    var showLogoutDialog by remember { mutableStateOf(false) }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?", color = TextPrimary) },
            text = { Text("You'll need to log in again to continue.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    onLogout?.invoke()
                }) { Text("Log out", color = DangerText) }
            },
            dismissButton = {
                TextButton(colors = neutralTextButtonColors(), onClick = { showLogoutDialog = false }) { Text("Cancel") }
            },
            containerColor = GraphiteSurface
        )
    }

    val statusPill: @Composable () -> Unit = {
        ConnectionPill(status, Modifier.padding(end = 12.dp))
    }

    Scaffold(
        topBar = {
            if (homeHeader) {
                // Floats on the window background with Station 5's 16dp margin; the content's
                // own 16dp padding sets it off from the card below.
                HomeHeaderCard(
                    status = status,
                    operatorName = operatorName,
                    operatorRole = operatorRole,
                    onSettings = onSettings,
                    onLogoutClick = { showLogoutDialog = true },
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                return@Scaffold
            }
            // statusBarsPadding(), applied INSIDE background(), is what stops the top bar
            // drawing under the system status bar: MainActivity calls enableEdgeToEdge(), so
            // without this the title overlapped the clock. The surface colour still paints the
            // whole node — status-bar strip included — so the bar reads as one solid block
            // rather than a floating row with a transparent gap above it.
            Column(
                modifier = Modifier
                    .background(GraphiteSurface)
                    .statusBarsPadding()
            ) {
                if (operatorName != null) {
                    // Operator info anchors the left edge of this row (after an optional back
                    // arrow) so it lines up with the title row directly beneath it.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onBack != null) {
                            // Toolbar icons are tap targets, not keyboard stops: after an Enter-submit
                            // the IME closed and focus landed on the gear, so a second Enter (a
                            // scanner-wedge suffix, the C72 keypad) opened Settings (audit S2-08).
                            IconButton(onClick = onBack, modifier = Modifier.focusProperties { canFocus = false }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = BrandTint
                                )
                            }
                        }
                        // The Box's weight(1f) claims all space left over after the icons/pill
                        // (fixed-size, never squeezed — the connection pill in particular is
                        // safety-relevant) are measured, exactly like the Spacer this replaces.
                        // CenterStart keeps the button's content left-aligned instead of the
                        // TextButton's own default centering, so it still lines up with the
                        // title row directly beneath it; maxLines/ellipsis on the Text means a
                        // long "name · role" truncates instead of forcing the pill to wrap.
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            // The logout icon is the affordance: without it this read as a plain
                            // status label, and it is the app's ONLY route to switching operator
                            // (Settings offers a second one now). Amber tint + icon make it
                            // unmistakably a control rather than a caption.
                            TextButton(onClick = { showLogoutDialog = true }, modifier = Modifier.focusProperties { canFocus = false }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = "Log out",
                                    tint = BrandTint,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    // With a back arrow, logout icon, gear and pill on one row only
                                    // ~330 px are left for this label and "Operator One · Operator"
                                    // ellipsised mid-role (audit S2-12). The role is informational;
                                    // the name is what matters, so the role goes on the Home bar only.
                                    text = if (!operatorRole.isNullOrBlank() && onBack == null) "$operatorName · $operatorRole" else operatorName,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (onSettings != null) {
                            IconButton(onClick = onSettings, modifier = Modifier.focusProperties { canFocus = false }) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Settings",
                                    tint = TextMuted
                                )
                            }
                        }
                        actions?.invoke(this)
                        statusPill()
                    }
                    Text(
                        text = title,
                        // titleLarge, as in the single-row bar: at labelLarge the screen title was
                        // no bigger than the operator label above it (skill critique 2026-11-06, P2).
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                    )
                } else {
                    // No operator to anchor a second row on - a single standard bar with the
                    // title in its usual slot has plenty of room and needs no extra row.
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            if (onBack != null) {
                                IconButton(onClick = onBack, modifier = Modifier.focusProperties { canFocus = false }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = BrandTint
                                    )
                                }
                            }
                        },
                        actions = {
                            if (onSettings != null) {
                                IconButton(onClick = onSettings, modifier = Modifier.focusProperties { canFocus = false }) {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = "Settings",
                                        tint = TextMuted
                                    )
                                }
                            }
                            actions?.invoke(this)
                            statusPill()
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = GraphiteSurface
                        )
                    )
                }

                // The one loading affordance in the app. A centred spinner blanks the page it
                // sits on, so whatever the operator was reading disappears for the duration and
                // reappears somewhere else; a bar under the title leaves the content in place and
                // still reads at a glance from arm's length on the handheld.
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = BrandTint,
                        trackColor = GraphiteBorder
                    )
                }
            }
        },
        containerColor = GraphiteBackground,
        // safeDrawing, not the default systemBars: it also covers the display cutout and — the
        // reason it matters here — the IME. Every screen applies this PaddingValues, so when the
        // keyboard opens the content area shrinks above it instead of the window panning and
        // shoving the top bar (and with it the only logout control) off-screen entirely.
        // Insets already consumed by the top bar above are not double-counted.
        contentWindowInsets = WindowInsets.safeDrawing,
        content = content
    )
}
