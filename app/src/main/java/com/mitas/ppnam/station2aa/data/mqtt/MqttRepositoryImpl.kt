package com.mitas.ppnam.station2aa.data.mqtt

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MqttRepositoryImpl @Inject constructor(
    private val clientFactory: MqttClientFactory,
    private val settingsRepository: SettingsRepository,
    private val sessionHolder: OperatorSessionHolder,
    private val deviceIdentity: DeviceIdentity,
) : MqttRepository {

    companion object {
        private const val TAG = "MqttRepositoryImpl"
        // Raw text, not JSON — the device base node (PPNAM/station_2/{deviceId}) is
        // presence-only, so payloads are plain "online"/"offline" bytes.
        private val STATUS_ONLINE = "online".toByteArray()
        private val STATUS_OFFLINE = "offline".toByteArray()
        private const val RECONNECT_RETRY_DELAY_MS = 5_000L
        private const val SUBSCRIBE_RETRY_ATTEMPTS = 3
        private const val SUBSCRIBE_RETRY_DELAY_MS = 2_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L

        // Bounded retry. Every attempt republishes byte-for-byte the same request (same messageId,
        // same body), and the contract's replay design returns the stored response for the same
        // replay identity (deviceId + requestType + messageId) and body without repeating the
        // workflow action.
        internal const val REQUEST_MAX_ATTEMPTS = 3

        // Beyond this the device clock is far enough off to make timestamps hard to reconcile.
        internal const val CLOCK_SKEW_WARN_MS = 30_000L

        // How many server-push messageIds to remember for QoS-1 duplicate suppression. Bounded so a
        // long shift cannot grow it without limit; generous enough that a duplicate can never
        // realistically arrive after its original has aged out (broker redelivery is seconds, and
        // a busy shift is nowhere near 512 responses inside that window).
        internal const val SEEN_RESPONSE_CAPACITY = 512
    }

    // WireJson, never a bare Gson instance: it is what stops a JSON null landing in a non-null field.
    private val gson = WireJson.gson
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _stationOnline = MutableStateFlow(false)
    override val stationOnline: StateFlow<Boolean> = _stationOnline.asStateFlow()

    private val _clockSkewMillis = MutableStateFlow<Long?>(null)
    override val clockSkewMillis: StateFlow<Long?> = _clockSkewMillis.asStateFlow()

    private val _upgradeRequired = MutableStateFlow(false)
    override val upgradeRequired: StateFlow<Boolean> = _upgradeRequired.asStateFlow()

    /** Test seam for the device clock. */
    @VisibleForTesting
    internal var nowFn: () -> Instant = { Instant.now() }

    // Correlation registry: messageId -> the caller awaiting that exact response, plus the session
    // the request was SENT with. rev2.1 replies do not echo the session, so this is the only way to
    // tell whether an operator_session_invalid reply is about the session that is active now.
    private class PendingRequest(val waiter: CompletableDeferred<String>, val sessionId: String)

    private val pending = ConcurrentHashMap<String, PendingRequest>()

    // Duplicate suppression for server PUSHES only. rev2.1 direct replies carry no messageId of
    // their own — a duplicate direct reply is handled by the pending map instead (the first one
    // removes the waiter; later ones match nothing and are dropped). A push has no waiter, so a
    // QoS-1 redelivery would otherwise trigger a second read.
    //
    // Access is guarded by the map itself: HiveMQ delivers callbacks on its own event-loop threads.
    private val seenResponseIds = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
                size > SEEN_RESPONSE_CAPACITY
        }
    )

    /** True the first time this response messageId is seen, false for every duplicate after it. */
    private fun claimResponseId(messageId: String): Boolean =
        seenResponseIds.put(messageId, true) == null

    // rev2.1 server pushes (active_job_cards_invalidated). Set by the data layer that
    // owns the displayed list; the transport does not know what a job list is.
    @Volatile
    private var serverPushHandler: ((String, ResponseEnvelope, String) -> Unit)? = null

    override fun setServerPushHandler(handler: (topic: String, envelope: ResponseEnvelope, raw: String) -> Unit) {
        serverPushHandler = handler
    }

    // Test seam. Production path publishes workflow messages at QoS 1, not retained, per contract.
    @VisibleForTesting
    internal var publishFn: suspend (String, ByteArray) -> Unit = { topic, bytes ->
        mqttClient!!.publishWith()
            .topic(topic)
            .payload(bytes)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(false)
            .send()
            .await()
    }

    private var mqttClient: Mqtt5AsyncClient? = null
    private val isTransportConnected = AtomicBoolean(false)

    // Guards the graceful-shutdown race in the presence self-heal below: disconnect() publishes
    // retained "offline" while still connected, and the broker echoes it back before the
    // DISCONNECT completes — that echo must not resurrect "online".
    private val wantsConnection = AtomicBoolean(false)

    // Derived from hardware (base standard §2), never configured. Lazy because deriving it can
    // touch SharedPreferences/NetworkInterface; first access is on Dispatchers.IO in connect().
    private val deviceId: String by lazy { deviceIdentity.deviceId() }
    private var requestTimeoutMs: Long = AppSettings().requestTimeoutMs
    private var retryJob: Job? = null

    // The HiveMQ client's automaticReconnect() only takes over once a connection has
    // succeeded at least once — it does not retry a failed *initial* connect. Each built
    // client also gets connected/disconnected listeners so that later drops/restores
    // (whether from automaticReconnect or a network blip) keep _connectionState honest
    // and re-run the subscribe step, since MQTT5 sessions aren't configured to persist
    // subscriptions across a disconnect (no sessionExpiryInterval set).
    private fun buildClient(settings: AppSettings): Mqtt5AsyncClient {
        lateinit var client: Mqtt5AsyncClient
        var initialConnectHandled = false
        client = clientFactory.build(
            settings,
            onConnected = {
                isTransportConnected.set(true)
                scope.launch {
                    if (mqttClient !== client) return@launch
                    if (!initialConnectHandled) {
                        // First CONNACK for this client is handled synchronously by the
                        // caller (connect()/reconnectWith()) so it can report success/failure.
                        initialConnectHandled = true
                        return@launch
                    }
                    // This branch only runs when HiveMQ's automaticReconnect() has just
                    // silently re-established the transport after a mid-session drop.
                    // connect() is deliberately NOT called here — per Task 1's guard it
                    // would just no-op against the now-live transport, which is exactly
                    // the re-entrancy bug this replaces. Only the subscribe step (which is
                    // what actually needs redoing, since MQTT5 sessions here don't persist
                    // subscriptions across a disconnect) is retried.
                    try {
                        retryBounded(SUBSCRIBE_RETRY_ATTEMPTS, SUBSCRIBE_RETRY_DELAY_MS) {
                            withTimeout(SUBSCRIBE_TIMEOUT_MS) {
                                subscribeAndAnnounce(client, deviceId)
                            }
                        }
                        _connectionState.value = MqttConnectionState.CONNECTED
                    } catch (e: Exception) {
                        _connectionState.value = MqttConnectionState.DISCONNECTED
                        scheduleSubscribeRetry(client, deviceId)
                    }
                }
            },
            onDisconnected = { handleTransportDisconnected(client) }
        )
        return client
    }

    // The transport genuinely is connected at this point (isTransportConnected stays true);
    // only the subscribe step failed. connect() must NOT be reused to retry it — its
    // isTransportConnected guard would make that an immediate no-op, permanently stranding
    // the app in DISCONNECTED with a live-but-unsubscribed client. Keep retrying just the
    // subscribe step on its own timer until it succeeds or this client is superseded.
    private var subscribeRetryJob: Job? = null

    private fun scheduleSubscribeRetry(client: Mqtt5AsyncClient, deviceId: String) {
        if (subscribeRetryJob?.isActive == true) return
        subscribeRetryJob = scope.launch {
            while (isActive) {
                delay(RECONNECT_RETRY_DELAY_MS)
                if (mqttClient !== client || !isTransportConnected.get()) return@launch
                if (_connectionState.value == MqttConnectionState.CONNECTED) return@launch
                try {
                    retryBounded(SUBSCRIBE_RETRY_ATTEMPTS, SUBSCRIBE_RETRY_DELAY_MS) {
                        withTimeout(SUBSCRIBE_TIMEOUT_MS) {
                            subscribeAndAnnounce(client, deviceId)
                        }
                    }
                    _connectionState.value = MqttConnectionState.CONNECTED
                    return@launch
                } catch (e: Exception) {
                    // keep looping
                }
            }
        }
    }

    private fun handleTransportDisconnected(client: Mqtt5AsyncClient) {
        if (mqttClient === client) {
            subscribeRetryJob?.cancel()
            isTransportConnected.set(false)
            _connectionState.value = MqttConnectionState.RECONNECTING
            // The retained presence we last saw is no longer evidence of anything — we are not
            // subscribed any more. Re-subscribing replays the retained value.
            _stationOnline.value = false
        }
    }

    private fun scheduleReconnectRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(RECONNECT_RETRY_DELAY_MS)
            if (_connectionState.value != MqttConnectionState.CONNECTED) {
                connect()
            }
        }
    }

    internal suspend fun <T> retryBounded(maxAttempts: Int, delayMs: Long, block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) delay(delayMs)
            }
        }
        throw lastError ?: IllegalStateException("retryBounded exhausted with no recorded error")
    }

    // Presence is raw text, not JSON — the contract defines only the literal payloads
    // "online" and "offline". Anything else means we cannot claim Station 2 is up.
    @VisibleForTesting
    internal fun handleStationPresence(bytes: ByteArray) {
        val payload = String(bytes).trim().lowercase()
        _stationOnline.value = payload == "online"
        if (payload != "online" && payload != "offline") {
            Log.w(TAG, "Unrecognised station presence payload: '$payload' — treating as offline")
        }
    }

    private suspend fun subscribeAndAnnounce(client: Mqtt5AsyncClient, deviceId: String) {
        client.subscribeWith()
            .topicFilter(MqttTopics.responseWildcard(deviceId))
            .callback { publish -> handleIncomingResponse(publish.topic.toString(), publish.payloadAsBytes) }
            .send()
            .await()
        client.subscribeWith()
            .topicFilter(MqttTopics.STATION_PRESENCE)
            .callback { publish -> handleStationPresence(publish.payloadAsBytes) }
            .send()
            .await()
        // Self-heal (base standard §3): a restart quicker than the broker's dead-connection
        // detection lets the PREVIOUS connection's Last Will — retained "offline" — land after
        // this connection's retained "online", sticking presence at "offline" while genuinely
        // connected. Watching our own presence node lets us put it right.
        client.subscribeWith()
            .topicFilter(MqttTopics.devicePresence(deviceId))
            .callback { publish -> handleOwnPresence(client, publish.payloadAsBytes) }
            .send()
            .await()
        client.publishWith()
            .topic(MqttTopics.devicePresence(deviceId))
            .payload(STATUS_ONLINE)
            .qos(MqttQos.EXACTLY_ONCE)
            .retain(true)
            .send()
            .await()
        logPresence("online")
    }

    private fun logPresence(result: String) {
        MqttLog.message(Direction.OUT, MqttTopics.devicePresence(deviceId), 2, true, deviceId, "presence", null, result)
    }

    // Republishes retained "online" whenever our own presence node reads "offline" mid-connection.
    // Loop-guarded three ways: only the live client may heal (a superseded client's echoes are
    // ignored), only while the transport is actually connected, and only while a connection is
    // wanted — disconnect() publishes retained "offline" while still connected, and the broker
    // echoes it back before the DISCONNECT completes; that echo must not resurrect "online".
    // The republish itself echoes back as "online", which this handler ignores, so it can never
    // feed itself.
    private fun handleOwnPresence(client: Mqtt5AsyncClient, bytes: ByteArray) {
        val payload = String(bytes).trim().lowercase()
        if (payload != "offline") return
        if (mqttClient !== client || !isTransportConnected.get() || !wantsConnection.get()) return
        Log.w(TAG, "Own presence node reads offline while connected — republishing retained online")
        scope.launch {
            try {
                client.publishWith()
                    .topic(MqttTopics.devicePresence(deviceId))
                    .payload(STATUS_ONLINE)
                    .qos(MqttQos.EXACTLY_ONCE)
                    .retain(true)
                    .send()
                    .await()
                logPresence("online")
            } catch (e: Exception) {
                Log.w(TAG, "Presence self-heal republish failed", e)
            }
        }
    }

    override suspend fun connect() {
        if (isTransportConnected.get()) return
        retryJob?.cancel()
        wantsConnection.set(true)
        _connectionState.value = MqttConnectionState.RECONNECTING
        val settings = settingsRepository.current()
        requestTimeoutMs = settings.requestTimeoutMs
        // buildClient()/connectWith() do synchronous SSLContext/Netty setup (disk I/O +
        // crypto init) before the first suspension point — running that on the caller's
        // dispatcher (Main, via viewModelScope) blocks the UI thread long enough to trip
        // the input-dispatch ANR watchdog. Dispatchers.IO keeps it off Main.
        withContext(Dispatchers.IO) {
            if (mqttClient == null) {
                mqttClient = buildClient(settings)
            }
            val client = mqttClient!!
            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    client.connectWith()
                        .cleanStart(false)
                        .keepAlive(30)
                        .willPublish()
                            // Presence QoS is 2 per the base standard — retained raw "offline"
                            // on the device's own base node, same topic as the online announce.
                            .topic(MqttTopics.devicePresence(deviceId))
                            .payload(STATUS_OFFLINE)
                            .qos(MqttQos.EXACTLY_ONCE)
                            .retain(true)
                            .applyWillPublish()
                        .send()
                        .await()
                }
            } catch (e: Exception) {
                _connectionState.value = MqttConnectionState.DISCONNECTED
                scheduleReconnectRetry()
                return@withContext
            }
            // The transport is now genuinely connected (onConnected already flipped
            // isTransportConnected to true). A subscribe failure here must NOT fall through
            // to scheduleReconnectRetry(): that would re-invoke connect(), which immediately
            // no-ops against the now-live transport (see the isTransportConnected guard
            // above), permanently stranding the app in DISCONNECTED with a live-but-
            // unsubscribed client. Retry the subscribe step directly instead, mirroring the
            // automatic-reconnect path in buildClient()'s onConnected callback.
            try {
                retryBounded(SUBSCRIBE_RETRY_ATTEMPTS, SUBSCRIBE_RETRY_DELAY_MS) {
                    withTimeout(SUBSCRIBE_TIMEOUT_MS) {
                        subscribeAndAnnounce(client, deviceId)
                    }
                }
                _connectionState.value = MqttConnectionState.CONNECTED
            } catch (e: Exception) {
                _connectionState.value = MqttConnectionState.DISCONNECTED
                scheduleSubscribeRetry(client, deviceId)
            }
        }
    }

    override fun disconnect() {
        retryJob?.cancel()
        subscribeRetryJob?.cancel()
        // Cleared BEFORE the offline publish: the broker echoes our own retained "offline" back
        // on the presence subscription while the transport is still up, and the self-heal must
        // not read that echo as a stuck LWT and resurrect "online".
        wantsConnection.set(false)
        publishOfflineBestEffort(mqttClient)
        mqttClient?.disconnect()
        _connectionState.value = MqttConnectionState.DISCONNECTED
    }

    override suspend fun reconnectWith(settings: AppSettings): Result<Unit> {
        retryJob?.cancel()
        wantsConnection.set(true)
        // See connect() — buildClient()/connectWith() block synchronously before their
        // first suspension point, so this must run off the caller's (Main) dispatcher.
        return withContext(Dispatchers.IO) {
            val candidate = buildClient(settings)
            try {
                withTimeout(15_000L) {
                    candidate.connectWith()
                        .cleanStart(false)
                        .keepAlive(30)
                        .willPublish()
                            .topic(MqttTopics.devicePresence(deviceId))
                            .payload(STATUS_OFFLINE)
                            .qos(MqttQos.EXACTLY_ONCE)
                            .retain(true)
                            .applyWillPublish()
                        .send()
                        .await()
                    subscribeAndAnnounce(candidate, deviceId)
                }
                val old = mqttClient
                mqttClient = candidate
                requestTimeoutMs = settings.requestTimeoutMs
                _connectionState.value = MqttConnectionState.CONNECTED
                // The old client's retained "offline" lands on the SAME node (the id is derived,
                // not per-connection) after the candidate's "online" — the presence self-heal
                // then reads it and restores "online" for the live connection.
                publishOfflineBestEffort(old)
                try { old?.disconnect() } catch (_: Exception) { }
                Result.success(Unit)
            } catch (e: Exception) {
                try { candidate.disconnect() } catch (_: Exception) { }
                Result.failure(e)
            }
        }
    }

    override suspend fun <T : Any> request(
        requestType: String,
        responseType: String,
        payload: Any,
        responseClass: Class<T>,
    ): MqttOutcome<T> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return MqttOutcome.NoResponse(FailureKind.NotConnected)
        }

        val messageId = UUID.randomUUID().toString()
        // SCRAM runs before a session exists and must never carry a stale one.
        val sessionId = if (requestType.startsWith("scram_")) "" else sessionHolder.currentSessionIdOrEmpty()
        val json = RequestEnvelope.build(
            gson = gson,
            payload = payload,
            messageId = messageId,
            deviceId = deviceId,
            sessionId = sessionId,
            timestampUtc = MqttSchema.formatTimestamp(nowFn()),
        )
        val topic = MqttTopics.request(deviceId, requestType)

        val action = (gson.toJsonTree(payload) as? com.google.gson.JsonObject)
            ?.get("action")?.takeIf { it.isJsonPrimitive }?.asString
        val startedAt = System.currentTimeMillis()
        val bytes = json.toByteArray()  // frozen: every attempt republishes these exact bytes
        val waiter = CompletableDeferred<String>()
        pending[messageId] = PendingRequest(waiter, sessionId)
        try {
            repeat(REQUEST_MAX_ATTEMPTS) { attempt ->
                val publishOk = try {
                    publishFn(topic, bytes)
                    MqttLog.message(Direction.OUT, topic, 1, false, deviceId, requestType, action, "published", payload = json)
                    true
                } catch (e: CancellationException) {
                    // CancellationException is an Exception in Kotlin, so the generic catch below
                    // would swallow it and report a normal failure — breaking structured
                    // concurrency when a caller's scope is torn down mid-publish. Rethrow first.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "publish attempt ${attempt + 1} failed for $requestType", e)
                    false
                }
                if (publishOk) {
                    val raw = withTimeoutOrNull(requestTimeoutMs) { waiter.await() }
                    if (raw != null) {
                        val outcome = parseOutcome(raw, responseClass, responseType)
                        MqttLog.message(
                            Direction.RESULT, topic, 1, false, deviceId, requestType, action,
                            outcomeResult(outcome), durationMs = System.currentTimeMillis() - startedAt,
                        )
                        return outcome
                    }
                }
                if (attempt < REQUEST_MAX_ATTEMPTS - 1) {
                    Log.w(TAG, "retrying $requestType (messageId=$messageId, attempt ${attempt + 2})")
                }
            }
            MqttLog.message(
                Direction.RESULT, topic, 1, false, deviceId, requestType, action,
                "timeout", durationMs = System.currentTimeMillis() - startedAt,
            )
            return MqttOutcome.NoResponse(FailureKind.Timeout)
        } finally {
            pending.remove(messageId)
        }
    }

    private fun outcomeResult(outcome: MqttOutcome<*>): String = when (outcome) {
        is MqttOutcome.Accepted -> "success"
        is MqttOutcome.Rejected -> outcome.error?.raw ?: "rejected"
        is MqttOutcome.NoResponse -> "malformed"
    }

    private fun <T : Any> parseOutcome(
        raw: String,
        responseClass: Class<T>,
        expectedResponseType: String,
    ): MqttOutcome<T> = try {
        val envelope = gson.fromJson(raw, ResponseEnvelope::class.java)
            ?: throw IllegalStateException("Empty $expectedResponseType response")
        // rev2.1 puts the body under `data`. Absent or null on envelope-level rejections.
        val data = StrictJson.parse(raw).asJsonObject.get("data")?.takeIf { it.isJsonObject }
        val body: T? = data?.let { gson.fromJson(it, responseClass) }
        if (envelope.success) {
            if (body == null) {
                Log.e(TAG, "$expectedResponseType reported success with no data")
                MqttOutcome.NoResponse(FailureKind.MalformedResponse)
            } else {
                MqttOutcome.Accepted(body)
            }
        } else {
            MqttOutcome.Rejected(body = body, error = envelope.errorCode, operatorMessage = envelope.displayMessage)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not parse $expectedResponseType response", e)
        MqttOutcome.NoResponse(FailureKind.MalformedResponse)
    }

    // Measured from every response we can parse a timestamp out of, including ones that match no
    // pending request: a late or duplicate response is still evidence about our own clock.
    private fun recordClockSkew(serverTimestampUtc: String) {
        if (serverTimestampUtc.isBlank()) return
        val serverTime = try {
            Instant.parse(serverTimestampUtc)
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable response timestampUtc: '$serverTimestampUtc'")
            return
        }
        val skew = serverTime.toEpochMilli() - nowFn().toEpochMilli()
        _clockSkewMillis.value = skew
        if (kotlin.math.abs(skew) > CLOCK_SKEW_WARN_MS) {
            Log.w(
                TAG,
                "Device clock is out of sync with Station 2 by ${skew}ms " +
                    "(threshold ${CLOCK_SKEW_WARN_MS}ms). Timestamps will be hard to reconcile with Station 2's logs."
            )
        }
    }

    @VisibleForTesting
    internal fun handleIncomingResponse(topic: String, bytes: ByteArray) {
        val raw = String(bytes)
        val tree = try {
            StrictJson.parse(raw).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: DuplicatePropertyException) {
            Log.w(TAG, "Dropping message on $topic with a duplicate property: ${e.path}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unparseable message on $topic", e)
            return
        } ?: return
        val envelope = try {
            gson.fromJson(tree, ResponseEnvelope::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping message on $topic with an unreadable envelope", e)
            return
        } ?: return

        MqttLog.message(
            Direction.IN, topic, 1, false, deviceId, MqttTopics.responseTypeOf(topic), null,
            result = if (envelope.success) "success"
            else envelope.error.ifBlank { if (envelope.inResponseToMessageId.isBlank()) "push" else "rejected" },
        )

        // Measured from every message with a parseable timestamp, matched or not: a late or
        // duplicate reply is still evidence about our own clock.
        recordClockSkew(envelope.sentAtUtc?.takeIf { it.isNotBlank() } ?: envelope.timestampUtc)
        if (envelope.errorCode == ErrorCode.CLIENT_UPGRADE_REQUIRED) {
            _upgradeRequired.value = true
        }

        val id = envelope.inResponseToMessageId
        if (id.isBlank()) {
            // Only an invalidation push is uncorrelated by design. The server also sends some
            // early rejections (client_upgrade_required for an unknown suffix, invalid_envelope
            // for an oversized or duplicate-field request) with no correlation id; those are not
            // pushes and must not trigger a read. The upgrade latch above has already run.
            if (MqttTopics.responseTypeOf(topic) != "active_job_cards_invalidated") {
                Log.w(TAG, "Dropping uncorrelated message on $topic (error='${envelope.error}')")
                return
            }
            // rev2.1 pushes carry their own messageId; a redelivery must not
            // trigger a second read. One with no messageId can't be deduplicated, so it is
            // handled rather than silently dropped.
            if (envelope.messageId.isNotBlank() && !claimResponseId(envelope.messageId)) {
                Log.i(TAG, "Suppressing duplicate push on $topic (messageId=${envelope.messageId})")
                return
            }
            val handler = serverPushHandler
            if (handler != null) handler(topic, envelope, raw)
            else Log.w(TAG, "Dropping push on $topic — no handler registered")
            return
        }

        val entry = pending.remove(id)
        if (entry == null) {
            // A duplicate, or a reply to a request that already timed out. Neither may have a
            // side effect — including the session clear below, which cannot be attributed to the
            // current session without the request it answers.
            Log.w(TAG, "Dropping unmatched response on $topic for messageId=$id")
            return
        }
        // Only a rejection of a request sent WITH the currently active session may end it. A reply
        // to a request from before a re-login, or from before any login, is about a session that
        // is already gone — acting on it would log the operator out of a perfectly good one.
        if (envelope.errorCode == ErrorCode.OPERATOR_SESSION_INVALID &&
            entry.sessionId.isNotBlank() &&
            sessionHolder.clearIf(entry.sessionId)
        ) {
            Log.w(TAG, "Station 2 reports the session is invalid ($topic) — cleared local session")
        }
        entry.waiter.complete(raw)
    }

    // A graceful disconnect/reconnect doesn't trigger the connection's LWT (that only
    // fires on an ungraceful drop), so the "offline" status has to be published by hand
    // here. Best-effort: bounded blocking wait since disconnect() isn't suspend.
    private fun publishOfflineBestEffort(client: Mqtt5AsyncClient?) {
        try {
            client?.publishWith()
                ?.topic(MqttTopics.devicePresence(deviceId))
                ?.payload(STATUS_OFFLINE)
                ?.qos(MqttQos.EXACTLY_ONCE)
                ?.retain(true)
                ?.send()
                ?.get(2, TimeUnit.SECONDS)
            if (client != null) logPresence("offline")
        } catch (_: Exception) { }
    }

}
