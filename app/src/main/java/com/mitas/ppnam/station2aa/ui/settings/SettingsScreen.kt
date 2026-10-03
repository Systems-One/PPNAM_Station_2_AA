package com.mitas.ppnam.station2aa.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.BuildConfig
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val stationOnline by viewModel.stationOnline.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val pinState = viewModel.pinState.value
    val pinInput = viewModel.pinInput.value
    val pinError = viewModel.pinError.value
    val pinErrorMessage = viewModel.pinErrorMessage.value
    val pinLockoutMessage = viewModel.pinLockoutMessage.value
    val pinLockedOut = viewModel.pinLockedOut.value
    val applyState = viewModel.applyState.value
    val draft = viewModel.draftSettings.value
    val session by viewModel.session.collectAsState()
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val submitPin = {
        // Clearing focus closes the IME so a hardware Enter cannot hop to the toolbar (S2-08).
        focusManager.clearFocus()
        viewModel.submitPin()
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?", color = TextPrimary) },
            text = { Text("You'll need to log in again to continue.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout()
                }) { Text("Log out", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            },
            containerColor = GraphiteSurface
        )
    }

    AppScaffold(
        title = "Settings",
        status = connectionStatus,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionLabel("Diagnostics")

            Card(
                colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                border = BorderStroke(1.dp, GraphiteBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Broker link and Station 2 presence are separate failures with separate
                    // remedies, and the composite status can only name one of them at a time.
                    // Diagnostics shows both. Same three words and colours as the top-bar pill:
                    // the card used to say "Reconnecting" in blue while the pill said "Offline"
                    // in red at the same moment (audit S2-09, static-17).
                    val (brokerColor, brokerLabel) = when (connectionState) {
                        MqttConnectionState.CONNECTED    -> SuccessGreen to "Connected"
                        MqttConnectionState.RECONNECTING -> WarningOrange to "Reconnecting"
                        MqttConnectionState.DISCONNECTED -> DangerRed to "Offline"
                    }
                    DiagnosticRow("MQTT BROKER", brokerColor, brokerLabel)

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 10.dp))

                    // With the broker down, the retained presence value is stale rather than
                    // false — saying "offline" there would blame Station 2 for the broker's fault.
                    val (stationColor, stationLabel) = when {
                        connectionState != MqttConnectionState.CONNECTED -> TextMuted to "Unknown"
                        stationOnline -> SuccessGreen to "Online"
                        else -> WarningOrange to "Offline"
                    }
                    DiagnosticRow("STATION 2", stationColor, stationLabel)

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 10.dp))

                    // S1's order: broker, station, version, device id (static-17).
                    DiagnosticValueRow("VERSION", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 10.dp))

                    // Read-only by design (fleet MQTT base standard §2): the id is derived from
                    // hardware once and persisted, never configured. Shown here so it can be
                    // read off the device for enrolment at the station.
                    DiagnosticValueRow("DEVICE ID", deviceId.ifBlank { "…" })
                }
            }

            HorizontalDivider(color = GraphiteBorder)

            SectionLabel("Configuration")

            when (pinState) {
                PinState.Locked -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Enter supervisor PIN to edit settings",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                val message = pinLockoutMessage ?: pinErrorMessage
                                OutlinedTextField(
                                    value = pinInput,
                                    onValueChange = viewModel::onPinChange,
                                    label = { Text("PIN") },
                                    singleLine = true,
                                    enabled = !pinLockedOut,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.NumberPassword,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { submitPin() }),
                                    isError = pinError,
                                    // Supporting text is part of the field's own bounds, so the
                                    // keyboard's bring-into-view scroll includes it. As a separate
                                    // Text below the row it sat exactly under the IME edge and the
                                    // only feedback while typing was a red outline (audit S2-10).
                                    supportingText = message?.let { { Text(it, color = DangerRed) } },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = BrandTint,
                                        focusedLabelColor = BrandTint,
                                        cursorColor = BrandTint
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = submitPin,
                                    colors = brandButtonColors(),
                                    enabled = !pinLockedOut,
                                    modifier = Modifier.height(56.dp)
                                ) { Text("Unlock") }
                            }
                        }
                    }
                }

                PinState.Unlocked -> {
                    ConfigSection(title = "Connection") {
                        SettingsTextField(
                            value = draft.mqttHost,
                            label = "Host",
                            error = viewModel.hostError.value,
                            keyboardType = KeyboardType.Uri,
                            onValueChange = { viewModel.updateDraft(draft.copy(mqttHost = it)) }
                        )
                        SettingsTextField(
                            value = viewModel.portText.value,
                            label = "Port",
                            error = viewModel.portError.value,
                            keyboardType = KeyboardType.Number,
                            onValueChange = viewModel::onPortChange
                        )
                        SettingsToggleRow(
                            label = "WebSocket",
                            checked = draft.mqttUseWebSocket,
                            onCheckedChange = { viewModel.updateDraft(draft.copy(mqttUseWebSocket = it)) }
                        )
                        SettingsToggleRow(
                            label = "TLS",
                            checked = draft.mqttUseTls,
                            onCheckedChange = { viewModel.updateDraft(draft.copy(mqttUseTls = it)) }
                        )
                        SettingsTextField(
                            value = draft.mqttUsername,
                            label = "Username",
                            onValueChange = { viewModel.updateDraft(draft.copy(mqttUsername = it)) }
                        )
                        SettingsTextField(
                            value = viewModel.passwordText.value,
                            label = "Password (blank keeps the current one)",
                            keyboardType = KeyboardType.Password,
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
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
                            onValueChange = viewModel::onPasswordChange
                        )
                    }

                    ConfigSection(title = "Session") {
                        SettingsTextField(
                            value = viewModel.autoLogoutText.value,
                            label = "Auto sign-out after (minutes, 0 = never)",
                            error = viewModel.autoLogoutError.value,
                            keyboardType = KeyboardType.Number,
                            onValueChange = viewModel::onAutoLogoutChange
                        )
                    }

                    ConfigSection(title = "Advanced") {
                        SettingsTextField(
                            value = viewModel.timeoutText.value,
                            label = "Request timeout (ms)",
                            error = viewModel.timeoutError.value,
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                            onValueChange = viewModel::onTimeoutChange
                        )
                    }

                    Button(
                        colors = brandButtonColors(),
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.testAndApply()
                        },
                        enabled = applyState !is ApplyState.Testing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("Test & Apply")
                    }
                }
            }

            // Outside the Locked/Unlocked switch on purpose: the gate re-locks 2 s after a
            // successful apply, and the confirmation used to vanish with the form — "silently",
            // the audit said (static-06). It stays until the next apply or leaving the screen.
            when (val state = applyState) {
                ApplyState.Testing -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = BrandTint,
                            strokeWidth = 2.dp
                        )
                        Text("Testing connection…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
                is ApplyState.Success -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = SuccessGreen)
                    }
                }
                is ApplyState.Failure -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Error, null, tint = DangerRed, modifier = Modifier.size(18.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = DangerRed)
                    }
                }
                ApplyState.Idle -> {}
            }

            // The top bar's operator label was the ONLY way to switch users, and it read as a
            // caption rather than a control. Settings is the obvious second home for it — and the
            // one place still reachable when a keyboard is covering the bar.
            session?.let { operator ->
                HorizontalDivider(color = GraphiteBorder)
                SectionLabel("Session")
                Card(
                    colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                    border = BorderStroke(1.dp, GraphiteBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "SIGNED IN AS",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                                color = TextMuted
                            )
                            Text(
                                if (operator.role.isNotBlank()) "${operator.operatorName} · ${operator.role}"
                                else operator.operatorName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary
                            )
                        }
                        OutlinedButton(
                            onClick = { showLogoutDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) { Text("Log out") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One labelled line of the Diagnostics card, with its own dot-and-text status badge. */
@Composable
private fun DiagnosticRow(label: String, dotColor: Color, statusLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = TextMuted
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(dotColor.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(6.dp)) {
                    drawCircle(dotColor, center = Offset(size.width / 2, size.height / 2))
                }
                Spacer(Modifier.width(5.dp))
                Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = dotColor)
            }
        }
    }
}

/** A plain label/value Diagnostics line (version, device id). Body face with tabular digits (S2-14). */
@Composable
private fun DiagnosticValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = TextMuted
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = TextPrimary
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
        color = TextMuted
    )
}

@Composable
private fun ConfigSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(1.dp, GraphiteBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                color = BrandTint
            )
            content()
        }
    }
}

/**
 * One form field. Next moves down the form; Done (the last field) just closes the keyboard so
 * Test & Apply is reachable without a swipe — the audit's keyboard matrix had "Done no-op" here.
 * A validation [error] is supporting text, inside the field's bounds, for the same reason as the
 * PIN message (S2-10).
 */
@Composable
private fun SettingsTextField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it, color = DangerRed) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
            onDone = { focusManager.clearFocus() },
        ),
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandTint,
            focusedLabelColor = BrandTint,
            cursorColor = BrandTint
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnBrandPrimary,
                checkedTrackColor = BrandPrimary
            )
        )
    }
}
