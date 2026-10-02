package com.mitas.ppnam.station2aa.ui.components

import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

/** Beyond this, the device clock is a plausible cause of blanket message_expired rejections. */
const val SKEW_THRESHOLD_MS = 30_000L

enum class ConnectionStatus { Offline, Reconnecting, StationOffline, ClockSkewed, Connected }

/**
 * Resolves what to tell the operator about connectivity, in precedence order.
 *
 * Broker connectivity alone is not "connected": the broker can be up while Station 2 is down, in
 * which case every request times out with no clue why. Station 2's retained presence topic is what
 * makes that visible.
 *
 * Clock skew ranks below station presence because a skew reading is only meaningful once we have
 * had a response to measure it from. It warns rather than blocks: a skewed clock fails every
 * request with message_expired, but the operator cannot fix the clock from here, and blocking would
 * strand them. A specific warning turns "everything is mysteriously broken" into something
 * actionable.
 */
fun resolveConnectionStatus(
    connectionState: MqttConnectionState,
    stationOnline: Boolean,
    clockSkewMillis: Long?,
    skewThresholdMs: Long = SKEW_THRESHOLD_MS,
): ConnectionStatus = when {
    connectionState == MqttConnectionState.DISCONNECTED -> ConnectionStatus.Offline
    connectionState == MqttConnectionState.RECONNECTING -> ConnectionStatus.Reconnecting
    !stationOnline -> ConnectionStatus.StationOffline
    clockSkewMillis != null && abs(clockSkewMillis) > skewThresholdMs -> ConnectionStatus.ClockSkewed
    else -> ConnectionStatus.Connected
}

/** How long a resolved status must persist before the UI shows it. See [connectionStatusFlow]. */
const val CONNECTION_STATUS_DEBOUNCE_MS = 1_500L

/**
 * The one place every screen derives its [ConnectionStatus] from — [resolveConnectionStatus] is a
 * pure, memoryless function of the latest sample, so without debouncing here a single stale/late
 * response (clock skew measured off one delayed reply, or a retained-presence value that hasn't
 * propagated yet after a resubscribe) flips the badge for exactly one emission before the next,
 * correct sample flips it straight back — a confusing flash with no real connectivity change
 * behind it. Debouncing the resolved status (not the raw inputs, which stay instantaneous for
 * anything else that needs them) means a change only reaches the UI once it holds for a beat.
 */
@OptIn(FlowPreview::class)
fun connectionStatusFlow(
    connectionState: Flow<MqttConnectionState>,
    stationOnline: Flow<Boolean>,
    clockSkewMillis: Flow<Long?>,
): Flow<ConnectionStatus> = combine(connectionState, stationOnline, clockSkewMillis) { state, online, skew ->
    resolveConnectionStatus(state, online, skew)
}.debounce(CONNECTION_STATUS_DEBOUNCE_MS)

/**
 * The status as of this instant. [connectionStatusFlow] debounces, so its first emission is
 * 1.5 s away; a placeholder initial value of Offline painted a red pill on every screen entry
 * while the Diagnostics card on the same screen said Connected (audit S2-09).
 */
fun MqttRepository.currentConnectionStatus(): ConnectionStatus =
    resolveConnectionStatus(connectionState.value, stationOnline.value, clockSkewMillis.value)

/** The one way every ViewModel exposes the top-bar pill: debounced updates, seeded with the truth. */
fun MqttRepository.connectionStatusIn(scope: CoroutineScope): StateFlow<ConnectionStatus> =
    connectionStatusFlow(connectionState, stationOnline, clockSkewMillis)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), currentConnectionStatus())
