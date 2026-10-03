package com.mitas.ppnam.station2aa.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onNavigateSettings: () -> Unit,
    onExitApp: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val operators by viewModel.operators.collectAsState()
    // rememberSaveable, not remember: a configuration change (font scale, multi-window — rotation
    // is locked now) used to wipe the field mid-typing (audit S2-02).
    var username by rememberSaveable { mutableStateOf("") }
    var operatorsOpen by remember { mutableStateOf(false) }
    // The password is deliberately NOT saveable: rememberSaveable writes into the Activity's
    // saved-instance Bundle, which the system can persist to disk. Portrait lock already removes
    // the rotation case; a rarer recreation costs a retype, not a leaked secret.
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val buttonIntoView = remember { BringIntoViewRequester() }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == "home") onLoggedIn()
        }
    }

    // Badge scans sign in only while this screen is resumed. Login is the start destination and
    // its view model survives under Home, so without this gate a card scanned on Home would be
    // taken as a login attempt.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setLoginScreenActive(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setLoginScreenActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setLoginScreenActive(false)
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

    // Scroll the Log In button fully into view once the keyboard is up or an error line has grown
    // the form (audit S2-01). A single bringIntoView fired the moment imeVisible flipped ran
    // against the OLD viewport: the IME inset animates in over ~300 ms, so the scroll container
    // was still tall, saw the button as "already visible" and did nothing; the viewport then
    // shrank and left a 38 px sliver. Re-run it whenever the IME inset settles at a new height
    // (debounced past the animation) and whenever the ui state changes the form's height.
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime
    LaunchedEffect(uiState) {
        snapshotFlow { imeBottom.getBottom(density) }
            .debounce(150)
            .collectLatest { bottom -> if (bottom > 0) buttonIntoView.bringIntoView() }
    }

    // Clearing focus closes the IME and, more importantly, stops focus hopping onto the gear
    // icon after an Enter-submit (audit S2-08).
    val submit = {
        focusManager.clearFocus()
        keyboard?.hide()
        viewModel.submitCredentials(username, password)
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
                // Log In button reachable instead of stranded below the keys.
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
                    // Above the fields, not below: an error that appears under the fields grows
                    // the form downwards and pushes the button under the keyboard (audit S2-01).
                    if (uiState is LoginUiState.Error) {
                        Text(
                            text = (uiState as LoginUiState.Error).message,
                            color = DangerRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // The operator directory Station 2 serves (fleet rule, after Station 1): an
                    // editable dropdown. Rows read "username — Display Name"; picking one leaves
                    // only the username, which is what SCRAM authenticates, and moves on to the
                    // password. The arrow shows, and a tap opens the list, only once there is a
                    // list; a typed name that is not listed still signs in. Typing closes the list
                    // so it never sits over the password field.
                    val hasOperators = operators.isNotEmpty()
                    ExposedDropdownMenuBox(
                        expanded = operatorsOpen && hasOperators,
                        onExpandedChange = { operatorsOpen = it && hasOperators },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                username = it
                                operatorsOpen = false
                            },
                            label = { Text("Username") },
                            singleLine = true,
                            enabled = uiState !is LoginUiState.LoggingIn,
                            trailingIcon = if (hasOperators) {
                                { ExposedDropdownMenuDefaults.TrailingIcon(expanded = operatorsOpen) }
                            } else null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandTint,
                                focusedLabelColor = BrandTint,
                                cursorColor = BrandTint
                            ),
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryEditable)
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = operatorsOpen && hasOperators,
                            onDismissRequest = { operatorsOpen = false },
                            containerColor = GraphiteSurface
                        ) {
                            operators.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.label, color = TextPrimary) },
                                    onClick = {
                                        username = entry.username
                                        operatorsOpen = false
                                        focusManager.moveFocus(FocusDirection.Down)
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = uiState !is LoginUiState.LoggingIn,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            // Gloved operators mistype; S1's Settings has a toggle, logins did not.
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.focusProperties { canFocus = false }
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = TextMuted
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandTint,
                            focusedLabelColor = BrandTint,
                            cursorColor = BrandTint
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = submit,
                        colors = brandButtonColors(),
                        enabled = uiState !is LoginUiState.LoggingIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .bringIntoViewRequester(buttonIntoView)
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

                    // The same card that signs in at Station 1: scanning it here needs no typing.
                    Text(
                        text = "Or scan your badge",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
