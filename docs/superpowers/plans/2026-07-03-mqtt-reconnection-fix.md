# MQTT Reconnection Reliability Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the defect where a mid-session MQTT drop can permanently wedge reconnection, leaving the app stuck showing `DISCONNECTED` forever.

**Architecture:** Track transport-connected state independently of the UI-facing `MqttConnectionState`; make `connect()` a no-op against an already-transport-connected client; replace the buggy re-`connect()`-on-subscribe-failure path with a bounded, timeout-guarded retry of just the subscribe step; add the timeout `connect()` was missing.

**Tech Stack:** Kotlin, Coroutines (kotlinx-coroutines), HiveMQ MQTT Client (`com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient`), JUnit4 + Mockito-Kotlin for tests.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-03-mqtt-reconnection-fix-design.md` — every requirement below traces to a section there.
- No MQTT contract changes, no UI changes in this plan.
- Follow existing code style: no comments except where a subtle invariant needs explaining (this file already does this well — match it).
- Test command: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"` (run from `C:\Dev\PPNAM_Station_2_AA`, Git Bash).
- Only file touched (production code): `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`.
- Only file touched (tests): `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt`.
- `internal` (not `private`) visibility on new testable helpers, matching the existing `internal suspend fun sendWithTimeout(...)` precedent — the test class is in the same Gradle module and already calls that member directly without reflection.

---

### Task 1: Transport-connected flag guards `connect()` against a live client

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt`

**Interfaces:**
- Produces: `private val isTransportConnected: AtomicBoolean` field on `MqttRepositoryImpl`, initial value `false`. Later tasks read/write it.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test**

Add to `MqttRepositoryImplTest.kt`, inside the `MqttRepositoryImplTest` class (after the existing `initial connection state is DISCONNECTED` test):

```kotlin
@Test
fun `connect is a no-op when transport is already connected`() = runTest {
    val field = MqttRepositoryImpl::class.java.getDeclaredField("isTransportConnected")
    field.isAccessible = true
    (field.get(repo) as java.util.concurrent.atomic.AtomicBoolean).set(true)

    repo.connect()

    verify(mockClientFactory, never()).build(any(), any(), any())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: FAIL — `NoSuchFieldException: isTransportConnected` (the field doesn't exist yet).

- [ ] **Step 3: Add the field and the `connect()` guard**

In `MqttRepositoryImpl.kt`, add the import (alongside the existing `java.util.UUID` / `java.util.concurrent.TimeUnit` imports):

```kotlin
import java.util.concurrent.atomic.AtomicBoolean
```

Add the field next to the existing `private var mqttClient: Mqtt5AsyncClient? = null` declaration:

```kotlin
private val isTransportConnected = AtomicBoolean(false)
```

Change the start of `connect()` from:

```kotlin
override suspend fun connect() {
    if (_connectionState.value == MqttConnectionState.CONNECTED) return
    retryJob?.cancel()
```

to:

```kotlin
override suspend fun connect() {
    if (isTransportConnected.get()) return
    retryJob?.cancel()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: PASS. Also re-run the full existing suite to confirm nothing else broke: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest*"` — all prior tests (`initial connection state is DISCONNECTED`, `send fails fast when disconnected...`, etc.) still pass unchanged, since none of them exercise `connect()`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt
git commit -m "fix(mqtt): guard connect() against an already-transport-connected client"
```

---

### Task 2: Generic bounded-retry helper

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt`

**Interfaces:**
- Produces: `internal suspend fun <T> retryBounded(maxAttempts: Int, delayMs: Long, block: suspend () -> T): T` on `MqttRepositoryImpl`. Retries `block` up to `maxAttempts` times with `delayMs` between attempts; returns the first successful result; rethrows the last exception if every attempt fails. Task 4 wires this into the subscribe-retry path.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing tests**

Add to `MqttRepositoryImplTest.kt`:

```kotlin
@Test
fun `retryBounded returns immediately on first success`() = runTest {
    var callCount = 0
    val result = repo.retryBounded(maxAttempts = 3, delayMs = 1_000L) {
        callCount++
        "ok"
    }
    assertEquals("ok", result)
    assertEquals(1, callCount)
}

@Test
fun `retryBounded succeeds on a later attempt without exhausting retries`() = runTest {
    var callCount = 0
    val result = repo.retryBounded(maxAttempts = 3, delayMs = 1_000L) {
        callCount++
        if (callCount < 2) throw IllegalStateException("not yet")
        "recovered"
    }
    assertEquals("recovered", result)
    assertEquals(2, callCount)
}

@Test
fun `retryBounded retries up to maxAttempts then rethrows the last error`() = runTest {
    var callCount = 0
    val thrown = try {
        repo.retryBounded(maxAttempts = 3, delayMs = 1_000L) {
            callCount++
            throw IllegalStateException("attempt $callCount failed")
        }
        null
    } catch (e: IllegalStateException) {
        e
    }

    assertEquals(3, callCount)
    assertEquals("attempt 3 failed", thrown?.message)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: FAIL — `Unresolved reference: retryBounded` (compile error, since the method doesn't exist yet).

- [ ] **Step 3: Implement `retryBounded`**

Add this method to `MqttRepositoryImpl` (near `scheduleReconnectRetry`, since it's the same category of retry logic):

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: PASS. These tests run instantly despite the `delay(1_000L)` calls — `runTest`'s virtual time auto-advances through `delay`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt
git commit -m "feat(mqtt): add generic bounded-retry helper"
```

---

### Task 3: Extract `handleTransportDisconnected`, set `RECONNECTING` not `DISCONNECTED`

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt`

**Interfaces:**
- Produces: `private fun handleTransportDisconnected(client: Mqtt5AsyncClient)` on `MqttRepositoryImpl` — sets `isTransportConnected` to `false`; sets `_connectionState` to `RECONNECTING` only if `client` is still the active `mqttClient` (ignores stale/superseded clients, matching the existing `onDisconnected` behavior this replaces).
- Consumes: `isTransportConnected` (Task 1).

- [ ] **Step 1: Write the failing tests**

Add to `MqttRepositoryImplTest.kt` (needs `import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient` — check the top of the file and add it if not already present):

```kotlin
@Test
fun `handleTransportDisconnected sets RECONNECTING for the active client`() = runTest {
    val mockClient: Mqtt5AsyncClient = mock()
    val mqttClientField = MqttRepositoryImpl::class.java.getDeclaredField("mqttClient")
    mqttClientField.isAccessible = true
    mqttClientField.set(repo, mockClient)

    val method = MqttRepositoryImpl::class.java.getDeclaredMethod(
        "handleTransportDisconnected", Mqtt5AsyncClient::class.java
    )
    method.isAccessible = true
    method.invoke(repo, mockClient)

    assertEquals(MqttConnectionState.RECONNECTING, repo.connectionState.value)
    val transportField = MqttRepositoryImpl::class.java.getDeclaredField("isTransportConnected")
    transportField.isAccessible = true
    assertFalse((transportField.get(repo) as java.util.concurrent.atomic.AtomicBoolean).get())
}

@Test
fun `handleTransportDisconnected ignores a stale superseded client`() = runTest {
    val activeClient: Mqtt5AsyncClient = mock()
    val staleClient: Mqtt5AsyncClient = mock()
    val mqttClientField = MqttRepositoryImpl::class.java.getDeclaredField("mqttClient")
    mqttClientField.isAccessible = true
    mqttClientField.set(repo, activeClient)

    val method = MqttRepositoryImpl::class.java.getDeclaredMethod(
        "handleTransportDisconnected", Mqtt5AsyncClient::class.java
    )
    method.isAccessible = true
    method.invoke(repo, staleClient)

    assertEquals(MqttConnectionState.DISCONNECTED, repo.connectionState.value)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: FAIL — `NoSuchMethodException: handleTransportDisconnected` (method doesn't exist yet).

- [ ] **Step 3: Extract the method and wire it into `buildClient()`**

In `MqttRepositoryImpl.kt`, add this new private method (near `buildClient`):

```kotlin
private fun handleTransportDisconnected(client: Mqtt5AsyncClient) {
    isTransportConnected.set(false)
    if (mqttClient === client) {
        _connectionState.value = MqttConnectionState.RECONNECTING
    }
}
```

Change `buildClient()`'s `onDisconnected` callback from:

```kotlin
            onDisconnected = {
                if (mqttClient === client) {
                    _connectionState.value = MqttConnectionState.DISCONNECTED
                }
            }
```

to:

```kotlin
            onDisconnected = { handleTransportDisconnected(client) }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt
git commit -m "fix(mqtt): transport disconnect sets RECONNECTING instead of DISCONNECTED"
```

---

### Task 4: Bounded subscribe-retry replaces the buggy re-`connect()` path

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`

**Interfaces:**
- Consumes: `retryBounded` (Task 2), `isTransportConnected` guard on `connect()` (Task 1).
- Produces: new companion constants `SUBSCRIBE_RETRY_ATTEMPTS = 3`, `SUBSCRIBE_RETRY_DELAY_MS = 2_000L`, `SUBSCRIBE_TIMEOUT_MS = 10_000L`.

This is the core fix from the spec's root-cause trace: today, when `subscribeAndAnnounce()` fails after an automatic-reconnect-triggered `onConnected`, the code calls `scheduleReconnectRetry()` → `connect()` on a client that (per Task 1) is now correctly recognized as already transport-connected — meaning that path would just no-op uselessly instead of fixing anything. Replace it with a bounded retry of the subscribe step itself.

- [ ] **Step 1: Add the retry constants**

In `MqttRepositoryImpl.kt`'s `companion object`, alongside the existing `RECONNECT_RETRY_DELAY_MS`:

```kotlin
private const val SUBSCRIBE_RETRY_ATTEMPTS = 3
private const val SUBSCRIBE_RETRY_DELAY_MS = 2_000L
private const val SUBSCRIBE_TIMEOUT_MS = 10_000L
```

- [ ] **Step 2: Replace the onConnected subscribe-failure handling**

In `buildClient()`, change the `onConnected` callback from:

```kotlin
            onConnected = {
                scope.launch {
                    if (mqttClient !== client) return@launch
                    if (!initialConnectHandled) {
                        // First CONNACK for this client is handled synchronously by the
                        // caller (connect()/reconnectWith()) so it can report success/failure.
                        initialConnectHandled = true
                        return@launch
                    }
                    try {
                        subscribeAndAnnounce(client, settings.stationName, settings.deviceId)
                        _connectionState.value = MqttConnectionState.CONNECTED
                    } catch (e: Exception) {
                        _connectionState.value = MqttConnectionState.DISCONNECTED
                        scheduleReconnectRetry()
                    }
                }
            },
```

to:

```kotlin
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
                                subscribeAndAnnounce(client, settings.stationName, settings.deviceId)
                            }
                        }
                        _connectionState.value = MqttConnectionState.CONNECTED
                    } catch (e: Exception) {
                        _connectionState.value = MqttConnectionState.DISCONNECTED
                    }
                }
            },
```

- [ ] **Step 3: Compile and run the full existing suite**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: PASS — this task doesn't add new tests (the retry counting/backoff logic is already covered by Task 2's `retryBounded` tests, and this step is pure wiring). Confirm the build compiles and no existing test regresses.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt
git commit -m "fix(mqtt): retry only the subscribe step after an automatic reconnect, not a full connect()"
```

---

### Task 5: Timeout guard on `connect()`'s connect attempt

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: new companion constant `CONNECT_TIMEOUT_MS = 15_000L` (matches the existing timeout in `reconnectWith()`).

`connect()`'s `client.connectWith()...send().await()` currently has no timeout, unlike `reconnectWith()`. This is the second half of the root-cause fix — without it, a hung connect attempt (initial-connect-failure path) can leave `retryJob` permanently active, exactly the "no reconnection" symptom.

This step is not independently unit-testable without deep-mocking HiveMQ's fluent connect builder chain, which no test in this suite does today (verified: no existing test exercises `connect()`'s or `reconnectWith()`'s success path — only the pre-connection guard branches). It's a one-line wrap of already-existing, already-tested-by-other-means code (`reconnectWith()` has carried the identical pattern since it was written). Verify by code review here; the spec's Verification Caveat already calls for an on-device reproduction pass covering this exact path before merging to main.

- [ ] **Step 1: Add the constant**

In `MqttRepositoryImpl.kt`'s `companion object`:

```kotlin
private const val CONNECT_TIMEOUT_MS = 15_000L
```

- [ ] **Step 2: Wrap the connect attempt in `connect()`**

Change:

```kotlin
        withContext(Dispatchers.IO) {
            if (mqttClient == null) {
                mqttClient = buildClient(settings)
            }
            try {
                val client = mqttClient!!
                client.connectWith()
                    .cleanStart(false)
                    .keepAlive(30)
                    .willPublish()
                        .topic(MqttTopics.deviceStatus(currentDeviceId))
                        .payload(STATUS_OFFLINE)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .applyWillPublish()
                    .send()
                    .await()
                subscribeAndAnnounce(client, currentStationName, currentDeviceId)
                _connectionState.value = MqttConnectionState.CONNECTED
            } catch (e: Exception) {
                _connectionState.value = MqttConnectionState.DISCONNECTED
                scheduleReconnectRetry()
            }
        }
```

to:

```kotlin
        withContext(Dispatchers.IO) {
            if (mqttClient == null) {
                mqttClient = buildClient(settings)
            }
            try {
                val client = mqttClient!!
                withTimeout(CONNECT_TIMEOUT_MS) {
                    client.connectWith()
                        .cleanStart(false)
                        .keepAlive(30)
                        .willPublish()
                            .topic(MqttTopics.deviceStatus(currentDeviceId))
                            .payload(STATUS_OFFLINE)
                            .qos(MqttQos.AT_LEAST_ONCE)
                            .retain(true)
                            .applyWillPublish()
                        .send()
                        .await()
                    subscribeAndAnnounce(client, currentStationName, currentDeviceId)
                }
                _connectionState.value = MqttConnectionState.CONNECTED
            } catch (e: Exception) {
                _connectionState.value = MqttConnectionState.DISCONNECTED
                scheduleReconnectRetry()
            }
        }
```

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: PASS, all tests from Tasks 1–5.

Also run the full app test suite once to make sure nothing else in the codebase referenced the old `onDisconnected`/`onConnected` behavior:

Run: `./gradlew testDebugUnitTest`
Expected: PASS (BUILD SUCCESSFUL).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt
git commit -m "fix(mqtt): add missing timeout to connect(), matching reconnectWith()"
```

---

## Manual Verification (required before this ships, per the spec's Verification Caveat)

The root cause was established via code tracing, not a live reproduction. Before merging to `master`:

1. Install a debug build on a real device/emulator, log in (establishes the initial connection).
2. Force a transport drop that HiveMQ's `automaticReconnect()` will detect: toggle airplane mode for ~10s, or stop/restart the MQTT broker if you control it.
3. Confirm the app's connection indicator (`AppScaffold`) shows `RECONNECTING` during the drop (not `DISCONNECTED`), then returns to `CONNECTED` within HiveMQ's backoff window (up to 30s) once connectivity returns.
4. Repeat 2–3 at least twice in the same session to confirm recovery isn't a one-shot fluke (the original bug manifested specifically as "recovers once, then never again").
5. If any step fails, do not merge — return to Phase 1 of the systematic-debugging process with the new evidence.
