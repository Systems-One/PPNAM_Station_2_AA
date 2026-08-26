package com.mitas.ppnam.station2aa.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
    val pinState = viewModel.pinState.value
    val pinInput = viewModel.pinInput.value
    val pinError = viewModel.pinError.value
    val pinErrorMessage = viewModel.pinErrorMessage.value
    val pinLockoutMessage = viewModel.pinLockoutMessage.value
    val applyState = viewModel.applyState.value
    val draft = viewModel.draftSettings.value
    val session by viewModel.session.collectAsState()
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }

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
                    // Diagnostics shows both so the operator can tell which half is down.
                    val (brokerColor, brokerLabel) = when (connectionState) {
                        MqttConnectionState.CONNECTED    -> SuccessGreen to "Connected"
                        MqttConnectionState.RECONNECTING -> AmberPrimary to "Reconnecting"
                        MqttConnectionState.DISCONNECTED -> DangerRed to "Disconnected"
                    }
                    DiagnosticRow("MQTT BROKER", brokerColor, brokerLabel)

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 10.dp))

                    // With the broker down, the retained presence value is stale rather than
                    // false — saying "offline" there would blame Station 2 for the broker's fault.
                    val (stationColor, stationLabel) = when {
                        connectionState != MqttConnectionState.CONNECTED -> TextMuted to "Unknown"
                        stationOnline -> SuccessGreen to "Online"
                        else -> AmberPrimary to "Offline"
                    }
                    DiagnosticRow("STATION 2", stationColor, stationLabel)

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "VERSION",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                            color = TextMuted
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                            color = TextPrimary
                        )
                    }
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
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = pinInput,
                                    onValueChange = viewModel::onPinChange,
                                    label = { Text("PIN") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.NumberPassword,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { viewModel.submitPin() }),
                                    isError = pinError,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AmberPrimary,
                                        focusedLabelColor = AmberPrimary,
                                        cursorColor = AmberPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = viewModel::submitPin,
                                    modifier = Modifier.height(56.dp)
                                ) { Text("Unlock") }
                            }
                            // Was a red border and nothing else — the operator had no idea
                            // whether the PIN was wrong or the field had simply mis-registered.
                            pinErrorMessage?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = DangerRed)
                            }
                            pinLockoutMessage?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = DangerRed)
                            }
                        }
                    }
                }

                PinState.Unlocked -> {
                    ConfigSection(title = "Station") {
                        SettingsTextField(
                            value = draft.deviceId,
                            label = "Device ID",
                            onValueChange = { viewModel.updateDraft(draft.copy(deviceId = it)) }
                        )
                    }

                    ConfigSection(title = "Connection") {
                        SettingsTextField(
                            value = draft.mqttHost,
                            label = "Host",
                            onValueChange = { viewModel.updateDraft(draft.copy(mqttHost = it)) }
                        )
                        SettingsTextField(
                            value = draft.mqttPort.toString(),
                            label = "Port",
                            keyboardType = KeyboardType.Number,
                            onValueChange = {
                                viewModel.updateDraft(draft.copy(mqttPort = it.toIntOrNull() ?: draft.mqttPort))
                            }
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
                            value = draft.mqttPassword,
                            label = "Password",
                            keyboardType = KeyboardType.Password,
                            visualTransformation = PasswordVisualTransformation(),
                            onValueChange = { viewModel.updateDraft(draft.copy(mqttPassword = it)) }
                        )
                    }

                    ConfigSection(title = "Advanced") {
                        SettingsTextField(
                            value = draft.requestTimeoutMs.toString(),
                            label = "Request Timeout (ms)",
                            keyboardType = KeyboardType.Number,
                            onValueChange = {
                                viewModel.updateDraft(draft.copy(requestTimeoutMs = it.toLongOrNull() ?: draft.requestTimeoutMs))
                            }
                        )
                    }

                    when (val state = applyState) {
                        ApplyState.Testing -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = AmberPrimary,
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

                    Button(
                        onClick = viewModel::testAndApply,
                        enabled = applyState !is ApplyState.Testing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("Test & Apply")
                    }
                }
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
                        ) { Text("Log Out") }
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
                color = AmberPrimary
            )
            content()
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AmberPrimary,
            focusedLabelColor = AmberPrimary,
            cursorColor = AmberPrimary
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
                checkedThumbColor = AmberPrimary,
                checkedTrackColor = AmberPrimary.copy(alpha = 0.4f)
            )
        )
    }
}
