package com.mitas.ppnam.station2aa.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onNavigateSettings: () -> Unit,
    onExitApp: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == "home") onLoggedIn()
        }
    }

    // Login is the start destination, so Back here used to drop straight to the Android launcher
    // — without even dismissing the IME first. On a shared handheld that is easy to hit by
    // accident. Back now behaves in two stages, the way Back does everywhere else on Android:
    // with the keyboard up it just closes the keyboard, and only from a settled screen does it
    // ask whether to leave the app.
    val imeVisible = WindowInsets.isImeVisible
    BackHandler {
        if (imeVisible) {
            keyboard?.hide()
            focusManager.clearFocus()
        } else {
            showExitDialog = true
        }
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
        title = "Log In",
        status = connectionStatus,
        onSettings = onNavigateSettings
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // padding(padding) carries the IME inset (AppScaffold sets safeDrawing), so the
                // form's own space shrinks when the keyboard opens; verticalScroll then keeps the
                // Log In button reachable instead of stranded below the keys. Previously the
                // focused field scrolled into view but the button never did.
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                border = BorderStroke(1.dp, GraphiteBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        enabled = uiState !is LoginUiState.LoggingIn,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = uiState !is LoginUiState.LoggingIn,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { viewModel.submitCredentials(username, password) }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AmberPrimary,
                            focusedLabelColor = AmberPrimary,
                            cursorColor = AmberPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState is LoginUiState.Error) {
                        Text(
                            text = (uiState as LoginUiState.Error).message,
                            color = DangerRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Button(
                        onClick = { viewModel.submitCredentials(username, password) },
                        enabled = uiState !is LoginUiState.LoggingIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        if (uiState is LoginUiState.LoggingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = GraphiteBackground,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Log In")
                        }
                    }

                    // Below the form, not above it. "Or scan your badge" sat above the username
                    // field, so the first thing the operator read was the second option. As a
                    // divider between the two methods it reads as the alternative it is.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f), color = GraphiteBorder)
                        Text(
                            "  or scan your badge  ",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                        HorizontalDivider(Modifier.weight(1f), color = GraphiteBorder)
                    }
                }
            }
        }
    }
}
