# MQTT Contract Foundation & Operator Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the RFID MQTT Contract's device identity, typed envelope/topic transport, and full operator login/logout flow to the Android app, without touching any existing mixing/rajoo/rfid/dashboard business logic.

**Architecture:** The app's existing kebab-case action/generic-envelope transport (`{station}/request`, `{station}/response/{deviceId}`) is left running unchanged for now. A new, parallel typed transport is added alongside it (`PPNAM/{deviceId}/{requestType}` publish, `PPNAM/{deviceId}/+` subscribe, per-message flat DTOs) via one new `MqttRepository.sendTyped()` method. Login/logout are built entirely on the new transport. `LoginScreen` becomes the nav graph's start destination; every other screen is unreachable until login succeeds.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, HiveMQ MQTT5 client, Gson, Room (offline queue), JUnit + Mockito-Kotlin + kotlinx-coroutines-test.

## Global Constraints

- Package root: `com.mitas.ppnam.station2aa`.
- Contract envelope defaults: `schemaVersion = "1.0"`, `operatorSessionId = ""` when absent.
- Default configured device identity: `deviceId = "handheld_1"` (`AppSettings.deviceId`).
- Login and logout are never offline-queued (`allowOfflineQueue = false` always) — see spec §1.5.
- Operator session lives in memory only (`OperatorSessionHolder`); nothing is persisted to disk. A fresh app process always starts at `LoginScreen`.
- Correlate a typed response to its request by response topic suffix only (next message whose topic ends in the expected `responseType` wins) — login/logout are single-in-flight per screen, so this is sufficient for this phase. Stricter `messageId` matching is deferred (spec §8).
- Test command: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.<FullyQualifiedClassName>"` (run the whole suite with `./gradlew testDebugUnitTest` before the final commit of the plan).
- Source spec: `docs/superpowers/specs/2026-07-01-mqtt-contract-foundation-login-design.md`. Two deviations from that spec, discovered during planning:
  1. `MqttTopics`' existing `request(stationName)` / `response(stationName, deviceId)` functions are kept as-is (still used by the old kebab-case `send()` path) and the new contract topic functions are added under distinct names (`contractRequest`, `contractResponse`, `contractResponseWildcard`, `deviceStatus`, `stationStatus`) rather than reusing the old names — reusing them would have created a duplicate-signature compile error between the old `response(String, String)` and a same-shaped new one.
  2. Spec §4.3's app-wide "session-loss observer" (a `SessionViewModel` that redirects to `Login` if the session unexpectedly clears) is **not** built in this plan. Nothing in this phase — or any use case that exists yet — can trigger that condition (no later-phase message can invalidate a session server-side), so the observer would be unreachable dead code. Build it in the phase that first adds a use case capable of clearing the session unexpectedly.

---

## Task 1: `AppSettings.deviceId` + persistence

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/AppSettings.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/settings/SettingsRepository.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/model/AppSettingsTest.kt`

**Interfaces:**
- Produces: `AppSettings.deviceId: String` (default `"handheld_1"`), used by every later task that needs the configured device identity.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/com/ppnam/station2aa/domain/model/AppSettingsTest.kt` (append inside the existing class, after `default scannerId is 1`):

```kotlin
    @Test
    fun `default deviceId is handheld_1`() {
        assertEquals("handheld_1", AppSettings().deviceId)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.model.AppSettingsTest"`
Expected: FAIL — `deviceId` is not a member of `AppSettings`.

- [ ] **Step 3: Add the field to `AppSettings`**

`app/src/main/java/com/ppnam/station2aa/domain/model/AppSettings.kt` — full file:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

data class AppSettings(
    val stationName: String = "Station 2",
    val deviceId: String = "handheld_1",
    val scannerId: Int = 1,
    val mqttHost: String = "mqtt.sysone.co.za",
    val mqttPort: Int = 8884,
    val mqttUseWebSocket: Boolean = true,
    val mqttUseTls: Boolean = true,
    val mqttUsername: String = "admin",
    val mqttPassword: String = "admin",
    val requestTimeoutMs: Long = 10_000L,
    val queueDrainIntervalMin: Int = 15
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.model.AppSettingsTest"`
Expected: PASS

- [ ] **Step 5: Persist the field in `SettingsRepository`**

`app/src/main/java/com/ppnam/station2aa/data/settings/SettingsRepository.kt` — full file:

```kotlin
package com.mitas.ppnam.station2aa.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "app_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val STATION_NAME            = stringPreferencesKey("station_name")
        val DEVICE_ID               = stringPreferencesKey("device_id")
        val SCANNER_ID              = intPreferencesKey("scanner_id")
        val MQTT_HOST               = stringPreferencesKey("mqtt_host")
        val MQTT_PORT               = intPreferencesKey("mqtt_port")
        val MQTT_USE_WEBSOCKET      = booleanPreferencesKey("mqtt_use_websocket")
        val MQTT_USE_TLS            = booleanPreferencesKey("mqtt_use_tls")
        val MQTT_USERNAME           = stringPreferencesKey("mqtt_username")
        val MQTT_PASSWORD           = stringPreferencesKey("mqtt_password")
        val REQUEST_TIMEOUT_MS      = longPreferencesKey("request_timeout_ms")
        val QUEUE_DRAIN_INTERVAL    = intPreferencesKey("queue_drain_interval_min")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            stationName          = prefs[Keys.STATION_NAME]         ?: "Station 2",
            deviceId              = prefs[Keys.DEVICE_ID]            ?: "handheld_1",
            scannerId            = prefs[Keys.SCANNER_ID]           ?: 1,
            mqttHost             = prefs[Keys.MQTT_HOST]            ?: "mqtt.sysone.co.za",
            mqttPort             = prefs[Keys.MQTT_PORT]            ?: 8884,
            mqttUseWebSocket     = prefs[Keys.MQTT_USE_WEBSOCKET]   ?: true,
            mqttUseTls           = prefs[Keys.MQTT_USE_TLS]         ?: true,
            mqttUsername         = prefs[Keys.MQTT_USERNAME]        ?: "admin",
            mqttPassword         = prefs[Keys.MQTT_PASSWORD]        ?: "admin",
            requestTimeoutMs     = prefs[Keys.REQUEST_TIMEOUT_MS]   ?: 10_000L,
            queueDrainIntervalMin = prefs[Keys.QUEUE_DRAIN_INTERVAL] ?: 15
        )
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STATION_NAME]        = settings.stationName
            prefs[Keys.DEVICE_ID]           = settings.deviceId
            prefs[Keys.SCANNER_ID]          = settings.scannerId
            prefs[Keys.MQTT_HOST]           = settings.mqttHost
            prefs[Keys.MQTT_PORT]           = settings.mqttPort
            prefs[Keys.MQTT_USE_WEBSOCKET]  = settings.mqttUseWebSocket
            prefs[Keys.MQTT_USE_TLS]        = settings.mqttUseTls
            prefs[Keys.MQTT_USERNAME]       = settings.mqttUsername
            prefs[Keys.MQTT_PASSWORD]       = settings.mqttPassword
            prefs[Keys.REQUEST_TIMEOUT_MS]  = settings.requestTimeoutMs
            prefs[Keys.QUEUE_DRAIN_INTERVAL] = settings.queueDrainIntervalMin
        }
    }
}
```

`SettingsRepository` has no existing unit test (DataStore requires an Android context) — this matches the existing lack of coverage for `stationName` etc., so no new test is added here.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/model/AppSettings.kt app/src/main/java/com/ppnam/station2aa/data/settings/SettingsRepository.kt app/src/test/java/com/ppnam/station2aa/domain/model/AppSettingsTest.kt
git commit -m "feat(settings): add configurable deviceId for the RFID MQTT contract"
```

---

## Task 2: `MqttTopics` — contract topic functions

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTopics.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttTopicsTest.kt`

**Interfaces:**
- Consumes: nothing (pure functions).
- Produces: `MqttTopics.contractRequest(deviceId, requestType)`, `contractResponse(deviceId, responseType)`, `contractResponseWildcard(deviceId)`, `deviceStatus(deviceId)`, `stationStatus(stationName)`, `responseTypeOf(topic)` — used by Task 4 (`MqttRepositoryImpl`).

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttTopicsTest.kt` (inside the existing class, after `topics trim leading and trailing spaces`):

```kotlin
    @Test
    fun `contractRequest combines device id and request type`() {
        assertEquals(
            "PPNAM/handheld_1/reader_login_requested",
            MqttTopics.contractRequest("handheld_1", "reader_login_requested")
        )
    }

    @Test
    fun `contractResponse combines device id and response type`() {
        assertEquals(
            "PPNAM/handheld_1/operator_context",
            MqttTopics.contractResponse("handheld_1", "operator_context")
        )
    }

    @Test
    fun `contractResponseWildcard subscribes to every response type for a device`() {
        assertEquals("PPNAM/handheld_1/+", MqttTopics.contractResponseWildcard("handheld_1"))
    }

    @Test
    fun `deviceStatus topic for a device`() {
        assertEquals("PPNAM/handheld_1/status", MqttTopics.deviceStatus("handheld_1"))
    }

    @Test
    fun `stationStatus normalizes station name to snake case`() {
        assertEquals("PPNAM/station_2/status", MqttTopics.stationStatus("Station 2"))
    }

    @Test
    fun `stationStatus trims and lowercases`() {
        assertEquals("PPNAM/station_2/status", MqttTopics.stationStatus("  Station 2  "))
    }

    @Test
    fun `responseTypeOf extracts the last topic segment`() {
        assertEquals("operator_context", MqttTopics.responseTypeOf("PPNAM/handheld_1/operator_context"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttTopicsTest"`
Expected: FAIL — `contractRequest`, `contractResponse`, `contractResponseWildcard`, `deviceStatus`, `stationStatus`, `responseTypeOf` are unresolved references.

- [ ] **Step 3: Implement the new functions**

`app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTopics.kt` — full file:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

object MqttTopics {
    fun request(stationName: String): String =
        "${stationName.trim().lowercase().replace(" ", "")}/request"

    fun response(stationName: String, deviceId: String): String =
        "${stationName.trim().lowercase().replace(" ", "")}/response/$deviceId"

    fun hopperStatus(stationName: String): String =
        "${stationName.trim().lowercase().replace(" ", "")}/hopper/status"

    fun contractRequest(deviceId: String, requestType: String): String =
        "PPNAM/$deviceId/$requestType"

    fun contractResponse(deviceId: String, responseType: String): String =
        "PPNAM/$deviceId/$responseType"

    fun contractResponseWildcard(deviceId: String): String =
        "PPNAM/$deviceId/+"

    fun deviceStatus(deviceId: String): String =
        "PPNAM/$deviceId/status"

    fun stationStatus(stationName: String): String =
        "PPNAM/${stationName.trim().lowercase().replace(" ", "_")}/status"

    fun responseTypeOf(topic: String): String =
        topic.substringAfterLast('/')
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttTopicsTest"`
Expected: PASS (11 tests total, including the 4 pre-existing ones, all green)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTopics.kt app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttTopicsTest.kt
git commit -m "feat(mqtt): add RFID contract topic functions to MqttTopics"
```

---

## Task 3: Contract envelope DTOs

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessagesTest.kt`

**Interfaces:**
- Produces: `ReaderLoginRequest`, `LoginTagScannedRequest`, `ReaderLogoutRequest`, `OperatorContextResponse` — used by Task 6 (`AuthUseCase`).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessagesTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AuthMessagesTest {

    private val gson = Gson()

    @Test
    fun `ReaderLoginRequest serializes contract field names`() {
        val request = ReaderLoginRequest(
            messageId = "login-0001",
            deviceId = "handheld_1",
            timestampUtc = "2026-06-30T10:30:00Z",
            correlationKey = "login-0001",
            username = "operator1",
            password = "1234"
        )
        val json = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertEquals("login-0001", json.get("messageId").asString)
        assertEquals("1.0", json.get("schemaVersion").asString)
        assertEquals("handheld_1", json.get("deviceId").asString)
        assertEquals("", json.get("operatorSessionId").asString)
        assertEquals("2026-06-30T10:30:00Z", json.get("timestampUtc").asString)
        assertEquals("login-0001", json.get("correlationKey").asString)
        assertEquals("operator1", json.get("username").asString)
        assertEquals("1234", json.get("password").asString)
    }

    @Test
    fun `LoginTagScannedRequest serializes badgeTag field`() {
        val request = LoginTagScannedRequest(
            messageId = "login-0002",
            deviceId = "handheld_1",
            timestampUtc = "2026-06-30T10:30:00Z",
            correlationKey = "login-0002",
            badgeTag = "TAG-JSMITH"
        )
        val json = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertEquals("TAG-JSMITH", json.get("badgeTag").asString)
    }

    @Test
    fun `ReaderLogoutRequest serializes operatorSessionId`() {
        val request = ReaderLogoutRequest(
            messageId = "logout-0001",
            deviceId = "handheld_1",
            operatorSessionId = "session-id",
            timestampUtc = "2026-06-30T10:30:30Z",
            correlationKey = "logout-0001"
        )
        val json = JsonParser.parseString(gson.toJson(request)).asJsonObject
        assertEquals("session-id", json.get("operatorSessionId").asString)
    }

    @Test
    fun `OperatorContextResponse deserializes a successful login response`() {
        val raw = """
            {
              "messageId": "login-0001",
              "schemaVersion": "1.0",
              "deviceId": "handheld_1",
              "operatorSessionId": "sess-123",
              "timestampUtc": "2026-06-30T10:30:01Z",
              "correlationKey": "login-0001",
              "success": true,
              "errorMessage": null,
              "operatorId": "OP-1",
              "operatorName": "Jane Smith",
              "role": "Operator",
              "allowedActions": ["job_card_submitted", "ingredient_scanned"],
              "allowedTabs": ["Mixing", "Rajoo"]
            }
        """.trimIndent()
        val response = gson.fromJson(raw, OperatorContextResponse::class.java)
        assertTrue(response.success)
        assertEquals("sess-123", response.operatorSessionId)
        assertEquals("Jane Smith", response.operatorName)
        assertEquals(2, response.allowedActions.size)
    }

    @Test
    fun `OperatorContextResponse deserializes a failed login response`() {
        val raw = """
            {
              "messageId": "login-0001",
              "schemaVersion": "1.0",
              "deviceId": "handheld_1",
              "operatorSessionId": null,
              "timestampUtc": "2026-06-30T10:30:01Z",
              "correlationKey": "login-0001",
              "success": false,
              "errorMessage": "Invalid credentials"
            }
        """.trimIndent()
        val response = gson.fromJson(raw, OperatorContextResponse::class.java)
        assertFalse(response.success)
        assertNull(response.operatorSessionId)
        assertEquals("Invalid credentials", response.errorMessage)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.dto.AuthMessagesTest"`
Expected: FAIL — the `com.mitas.ppnam.station2aa.data.mqtt.dto` package and its classes don't exist yet.

- [ ] **Step 3: Implement the DTOs**

`app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

data class ReaderLoginRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val username: String,
    val password: String
)

data class LoginTagScannedRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String = "",
    val timestampUtc: String,
    val correlationKey: String,
    val badgeTag: String
)

data class ReaderLogoutRequest(
    val messageId: String,
    val schemaVersion: String = "1.0",
    val deviceId: String,
    val operatorSessionId: String,
    val timestampUtc: String,
    val correlationKey: String
)

data class OperatorContextResponse(
    val messageId: String = "",
    val schemaVersion: String = "1.0",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String = "",
    val success: Boolean = false,
    val errorMessage: String? = null,
    val operatorId: String? = null,
    val operatorName: String? = null,
    val role: String? = null,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList()
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.dto.AuthMessagesTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessagesTest.kt
git commit -m "feat(mqtt): add typed request/response DTOs for reader login and logout"
```

---

## Task 4: `MqttRepository` — typed send path (`sendTyped`)

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTypedResult.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `MqttTopics.contractRequest/contractResponseWildcard/responseTypeOf` (Task 2), `AppSettings.deviceId` (Task 1).
- Produces: `MqttTypedResult<T>` sealed class (`Success<T>`, `Error`, `Disconnected`, `Queued`); `MqttRepository.sendTyped(requestType, responseType, requestJson, responseClass, allowOfflineQueue): MqttTypedResult<T>` — used by Task 6 (`AuthUseCase`).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt` — full file (constructor call updated to 3 args; two new `sendTyped` tests added):

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.local.OfflineQueueDao
import com.mitas.ppnam.station2aa.data.local.OfflineQueueEntity
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.HopperAvailability
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class MqttRepositoryImplTest {

    private lateinit var mockClientFactory: MqttClientFactory
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockQueueDao: OfflineQueueDao
    private lateinit var repo: MqttRepositoryImpl

    @Before
    fun setup() {
        mockClientFactory = mock()
        mockSettingsRepository = mock()
        mockQueueDao = mock()
        repo = MqttRepositoryImpl(mockClientFactory, mockSettingsRepository, mockQueueDao)
    }

    @Test
    fun `initial connection state is DISCONNECTED`() = runTest {
        assertEquals(MqttConnectionState.DISCONNECTED, repo.connectionState.first())
    }

    @Test
    fun `send queues message when disconnected`() = runTest {
        whenever(mockQueueDao.insert(any())).thenReturn(Unit)
        val result = repo.sendWithTimeout("complete-premix", "{}", timeoutMs = 100L)
        assertTrue(result is MqttResult.Queued)
        verify(mockQueueDao).insert(any())
    }

    @Test
    fun `send queues message on timeout`() = runTest {
        whenever(mockQueueDao.insert(any())).thenReturn(Unit)
        val result = repo.sendWithTimeout("lookup-job", "{}", timeoutMs = 100L)
        assertTrue(result is MqttResult.Queued)
    }

    @Test
    fun `hopperStatusUpdates emits parsed HopperStatus on hopper topic message`() = runTest {
        val json = """{"hopperCode":"H-01","status":"AVAILABLE","assignedTo":null}"""
        val method = MqttRepositoryImpl::class.java.getDeclaredMethod("handleHopperStatus", ByteArray::class.java)
        method.isAccessible = true
        method.invoke(repo, json.toByteArray())

        val emitted = repo.hopperStatusUpdates.replayCache.firstOrNull()
        assertNotNull(emitted)
        assertEquals("H-01", emitted!!.hopperCode)
        assertEquals(HopperAvailability.AVAILABLE, emitted.status)
    }

    @Test
    fun `hopperStatusUpdates does not crash on malformed payload`() = runTest {
        val method = MqttRepositoryImpl::class.java.getDeclaredMethod("handleHopperStatus", ByteArray::class.java)
        method.isAccessible = true
        method.invoke(repo, "not-json".toByteArray())
        assertTrue(repo.hopperStatusUpdates.replayCache.isEmpty())
    }

    @Test
    fun `sendTyped returns Disconnected when offline queue not allowed`() = runTest {
        val result = repo.sendTyped(
            requestType = "reader_login_requested",
            responseType = "operator_context",
            requestJson = "{}",
            responseClass = OperatorContextResponse::class.java,
            allowOfflineQueue = false
        )
        assertTrue(result is MqttTypedResult.Disconnected)
        verify(mockQueueDao, never()).insert(any())
    }

    @Test
    fun `sendTyped queues when disconnected and offline queue allowed`() = runTest {
        whenever(mockQueueDao.insert(any())).thenReturn(Unit)
        val result = repo.sendTyped(
            requestType = "ingredient_scanned",
            responseType = "ingredient_scan_result",
            requestJson = "{\"qty\":5}",
            responseClass = OperatorContextResponse::class.java,
            allowOfflineQueue = true
        )
        assertTrue(result is MqttTypedResult.Queued)
        verify(mockQueueDao).insert(
            argThat<OfflineQueueEntity> { action == "ingredient_scanned" && payload == "{\"qty\":5}" }
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: FAIL — `MqttRepositoryImpl` still has a 4-arg constructor, `sendTyped`/`MqttTypedResult` don't exist.

- [ ] **Step 3: Create `MqttTypedResult`**

`app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTypedResult.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

sealed class MqttTypedResult<out T> {
    data class Success<T>(val response: T) : MqttTypedResult<T>()
    data class Error(val message: String) : MqttTypedResult<Nothing>()
    object Disconnected : MqttTypedResult<Nothing>()
    object Queued : MqttTypedResult<Nothing>()
}
```

- [ ] **Step 4: Add `sendTyped` to the `MqttRepository` interface**

`app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt` — full file:

```kotlin
package com.mitas.ppnam.station2aa.domain.repository

import com.mitas.ppnam.station2aa.data.mqtt.MqttResult
import com.mitas.ppnam.station2aa.data.mqtt.MqttTypedResult
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.model.HopperStatus
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class MqttConnectionState { CONNECTED, RECONNECTING, DISCONNECTED }

interface MqttRepository {
    val connectionState: StateFlow<MqttConnectionState>
    val hopperStatusUpdates: SharedFlow<HopperStatus>
    suspend fun send(action: String, dataJson: String): MqttResult
    suspend fun <T> sendTyped(
        requestType: String,
        responseType: String,
        requestJson: String,
        responseClass: Class<T>,
        allowOfflineQueue: Boolean
    ): MqttTypedResult<T>
    suspend fun connect()
    fun disconnect()
    suspend fun reconnectWith(settings: AppSettings): Result<Unit>
}
```

- [ ] **Step 5: Implement device identity from settings + `sendTyped` in `MqttRepositoryImpl`**

`app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt` — full file:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.Gson
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.mitas.ppnam.station2aa.data.local.OfflineQueueDao
import com.mitas.ppnam.station2aa.data.local.OfflineQueueEntity
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.model.HopperStatus
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MqttRepositoryImpl @Inject constructor(
    private val clientFactory: MqttClientFactory,
    private val settingsRepository: SettingsRepository,
    private val offlineQueueDao: OfflineQueueDao
) : MqttRepository {

    private val gson = Gson()

    private val _connectionState = MutableStateFlow(MqttConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _incomingResponses = MutableSharedFlow<MqttResponseMessage>(extraBufferCapacity = 64)
    private val _incomingTyped = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)

    private val _hopperStatusUpdates = MutableSharedFlow<HopperStatus>(replay = 1, extraBufferCapacity = 16)
    override val hopperStatusUpdates: SharedFlow<HopperStatus> = _hopperStatusUpdates.asSharedFlow()

    private var mqttClient: Mqtt5AsyncClient? = null
    private var currentStationName: String = AppSettings().stationName
    private var currentDeviceId: String = AppSettings().deviceId
    private var requestTimeoutMs: Long = AppSettings().requestTimeoutMs

    override suspend fun connect() {
        _connectionState.value = MqttConnectionState.RECONNECTING
        try {
            val settings = settingsRepository.current()
            currentStationName = settings.stationName
            currentDeviceId = settings.deviceId
            requestTimeoutMs = settings.requestTimeoutMs
            if (mqttClient == null) {
                mqttClient = clientFactory.build(settings)
            }
            val client = mqttClient!!
            client.connectWith()
                .cleanStart(false)
                .keepAlive(30)
                .send()
                .await()
            client.subscribeWith()
                .topicFilter(MqttTopics.response(currentStationName, currentDeviceId))
                .callback { publish -> handleIncoming(publish.payloadAsBytes) }
                .send()
                .await()
            client.subscribeWith()
                .topicFilter(MqttTopics.hopperStatus(currentStationName))
                .callback { publish -> handleHopperStatus(publish.payloadAsBytes) }
                .send()
                .await()
            client.subscribeWith()
                .topicFilter(MqttTopics.contractResponseWildcard(currentDeviceId))
                .callback { publish -> handleIncomingTyped(publish.topic.toString(), publish.payloadAsBytes) }
                .send()
                .await()
            _connectionState.value = MqttConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = MqttConnectionState.DISCONNECTED
        }
    }

    override fun disconnect() {
        mqttClient?.disconnect()
        _connectionState.value = MqttConnectionState.DISCONNECTED
    }

    override suspend fun send(action: String, dataJson: String): MqttResult =
        sendWithTimeout(action, dataJson, requestTimeoutMs)

    override suspend fun reconnectWith(settings: AppSettings): Result<Unit> {
        val candidate = clientFactory.build(settings)
        return try {
            withTimeout(15_000L) {
                candidate.connectWith()
                    .cleanStart(false)
                    .keepAlive(30)
                    .send()
                    .await()
                candidate.subscribeWith()
                    .topicFilter(MqttTopics.response(settings.stationName, settings.deviceId))
                    .callback { publish -> handleIncoming(publish.payloadAsBytes) }
                    .send()
                    .await()
                candidate.subscribeWith()
                    .topicFilter(MqttTopics.hopperStatus(settings.stationName))
                    .callback { publish -> handleHopperStatus(publish.payloadAsBytes) }
                    .send()
                    .await()
                candidate.subscribeWith()
                    .topicFilter(MqttTopics.contractResponseWildcard(settings.deviceId))
                    .callback { publish -> handleIncomingTyped(publish.topic.toString(), publish.payloadAsBytes) }
                    .send()
                    .await()
            }
            val old = mqttClient
            mqttClient = candidate
            currentStationName = settings.stationName
            currentDeviceId = settings.deviceId
            requestTimeoutMs = settings.requestTimeoutMs
            _connectionState.value = MqttConnectionState.CONNECTED
            try { old?.disconnect() } catch (_: Exception) { }
            Result.success(Unit)
        } catch (e: Exception) {
            try { candidate.disconnect() } catch (_: Exception) { }
            Result.failure(e)
        }
    }

    internal suspend fun sendWithTimeout(action: String, dataJson: String, timeoutMs: Long): MqttResult {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return queue(action, dataJson)
        }

        val correlationId = UUID.randomUUID().toString()
        val request = MqttRequest(correlationId, currentDeviceId, action, dataJson)
        val payload = gson.toJson(request).toByteArray()

        return try {
            withTimeout(timeoutMs) {
                val responseDeferred = async {
                    _incomingResponses
                        .filter { it.correlationId == correlationId }
                        .first()
                }
                mqttClient!!.publishWith()
                    .topic(MqttTopics.request(currentStationName))
                    .payload(payload)
                    .send()
                    .await()
                val response = responseDeferred.await()
                if (response.success) {
                    MqttResult.Success(response.data ?: "{}")
                } else {
                    MqttResult.Error(response.error ?: "Unknown error")
                }
            }
        } catch (e: TimeoutCancellationException) {
            queue(action, dataJson)
        } catch (e: Exception) {
            queue(action, dataJson)
        }
    }

    override suspend fun <T> sendTyped(
        requestType: String,
        responseType: String,
        requestJson: String,
        responseClass: Class<T>,
        allowOfflineQueue: Boolean
    ): MqttTypedResult<T> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return if (allowOfflineQueue) {
                enqueue(requestType, requestJson)
                MqttTypedResult.Queued
            } else {
                MqttTypedResult.Disconnected
            }
        }

        return try {
            withTimeout(requestTimeoutMs) {
                val responseDeferred = async {
                    _incomingTyped.filter { it.first == responseType }.first()
                }
                mqttClient!!.publishWith()
                    .topic(MqttTopics.contractRequest(currentDeviceId, requestType))
                    .payload(requestJson.toByteArray())
                    .send()
                    .await()
                val (_, rawJson) = responseDeferred.await()
                MqttTypedResult.Success(gson.fromJson(rawJson, responseClass))
            }
        } catch (e: TimeoutCancellationException) {
            if (allowOfflineQueue) {
                enqueue(requestType, requestJson)
                MqttTypedResult.Queued
            } else {
                MqttTypedResult.Error("Request timed out")
            }
        } catch (e: Exception) {
            if (allowOfflineQueue) {
                enqueue(requestType, requestJson)
                MqttTypedResult.Queued
            } else {
                MqttTypedResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun enqueue(action: String, payload: String): String {
        val id = UUID.randomUUID().toString()
        offlineQueueDao.insert(
            OfflineQueueEntity(
                id = id,
                action = action,
                payload = payload,
                createdAt = Instant.now().toEpochMilli()
            )
        )
        return id
    }

    private suspend fun queue(action: String, dataJson: String): MqttResult.Queued =
        MqttResult.Queued(enqueue(action, dataJson))

    private fun handleIncoming(bytes: ByteArray) {
        try {
            val msg = gson.fromJson(String(bytes), MqttResponseMessage::class.java)
            _incomingResponses.tryEmit(msg)
        } catch (_: Exception) { }
    }

    private fun handleIncomingTyped(topic: String, bytes: ByteArray) {
        _incomingTyped.tryEmit(MqttTopics.responseTypeOf(topic) to String(bytes))
    }

    private fun handleHopperStatus(bytes: ByteArray) {
        try {
            val status = gson.fromJson(String(bytes), HopperStatus::class.java)
            _hopperStatusUpdates.tryEmit(status)
        } catch (_: Exception) { }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImplTest"`
Expected: PASS (8 tests)

- [ ] **Step 7: Run the full suite to confirm no other test broke**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS — the `MqttRepository` interface gained a method, but every existing `mock<MqttRepository>()` in other ViewModel tests auto-satisfies it (unused, never stubbed), so no other test file needs changes.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTypedResult.kt app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt
git commit -m "feat(mqtt): add typed sendTyped transport and source device id from settings"
```

---

## Task 5: `OperatorSessionHolder`

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/session/OperatorSessionHolder.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/session/OperatorSessionHolderTest.kt`

**Interfaces:**
- Produces: `OperatorSession` data class, `OperatorSessionHolder` (`session: StateFlow<OperatorSession?>`, `set()`, `clear()`, `currentSessionIdOrEmpty()`) — used by Task 6 (`AuthUseCase`), Task 9 (`HomeViewModel`).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ppnam/station2aa/data/session/OperatorSessionHolderTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OperatorSessionHolderTest {

    @Test
    fun `initial session is null`() = runTest {
        val holder = OperatorSessionHolder()
        assertNull(holder.session.first())
    }

    @Test
    fun `set stores the session`() = runTest {
        val holder = OperatorSessionHolder()
        val session = OperatorSession(
            operatorSessionId = "sess-1",
            operatorId = "OP-1",
            operatorName = "Jane Smith",
            role = "Operator"
        )
        holder.set(session)
        assertEquals(session, holder.session.first())
    }

    @Test
    fun `clear removes the session`() = runTest {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        holder.clear()
        assertNull(holder.session.first())
    }

    @Test
    fun `currentSessionIdOrEmpty returns empty string when no session`() {
        val holder = OperatorSessionHolder()
        assertEquals("", holder.currentSessionIdOrEmpty())
    }

    @Test
    fun `currentSessionIdOrEmpty returns session id when set`() {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        assertEquals("sess-1", holder.currentSessionIdOrEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.session.OperatorSessionHolderTest"`
Expected: FAIL — the `com.mitas.ppnam.station2aa.data.session` package doesn't exist yet.

- [ ] **Step 3: Implement**

`app/src/main/java/com/ppnam/station2aa/data/session/OperatorSessionHolder.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class OperatorSession(
    val operatorSessionId: String,
    val operatorId: String,
    val operatorName: String,
    val role: String,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList()
)

@Singleton
class OperatorSessionHolder @Inject constructor() {
    private val _session = MutableStateFlow<OperatorSession?>(null)
    val session: StateFlow<OperatorSession?> = _session.asStateFlow()

    fun set(session: OperatorSession) {
        _session.value = session
    }

    fun clear() {
        _session.value = null
    }

    fun currentSessionIdOrEmpty(): String = _session.value?.operatorSessionId ?: ""
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.session.OperatorSessionHolderTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/session/OperatorSessionHolder.kt app/src/test/java/com/ppnam/station2aa/data/session/OperatorSessionHolderTest.kt
git commit -m "feat(session): add in-memory OperatorSessionHolder"
```

---

## Task 6: `AuthUseCase` (login/logout)

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/domain/usecase/AuthUseCase.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt`

**Interfaces:**
- Consumes: `MqttRepository.sendTyped` (Task 4), `OperatorSessionHolder` (Task 5), `SettingsRepository.current().deviceId` (Task 1), `ReaderLoginRequest`/`LoginTagScannedRequest`/`ReaderLogoutRequest`/`OperatorContextResponse` (Task 3).
- Produces: `LoginMethod` sealed class (`Credentials`, `Badge`), `AuthUseCase.login(method): Result<OperatorSession>`, `AuthUseCase.logout(): Result<Unit>` — used by Task 7 (`LoginViewModel`), Task 9 (`HomeViewModel`).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.MqttTypedResult
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class AuthUseCaseTest {

    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var useCase: AuthUseCase

    @Before
    fun setup() = runTest {
        mockMqttRepository = mock()
        mockSessionHolder = mock()
        mockSettingsRepository = mock()
        whenever(mockSettingsRepository.current()).thenReturn(AppSettings(deviceId = "handheld_1"))
        useCase = AuthUseCase(mockMqttRepository, mockSessionHolder, mockSettingsRepository)
    }

    @Test
    fun `login with credentials success sets session and returns it`() = runTest {
        val response = OperatorContextResponse(
            operatorSessionId = "sess-1",
            success = true,
            operatorId = "OP-1",
            operatorName = "Jane Smith",
            role = "Operator",
            allowedActions = listOf("job_card_submitted"),
            allowedTabs = listOf("Mixing")
        )
        whenever(
            mockMqttRepository.sendTyped(
                eq("reader_login_requested"), eq("operator_context"), any(),
                eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.login(LoginMethod.Credentials("operator1", "1234"))

        assertTrue(result.isSuccess)
        assertEquals("sess-1", result.getOrNull()?.operatorSessionId)
        verify(mockSessionHolder).set(
            argThat { it.operatorSessionId == "sess-1" && it.operatorName == "Jane Smith" }
        )
    }

    @Test
    fun `login with credentials failure returns failure and does not set session`() = runTest {
        val response = OperatorContextResponse(success = false, errorMessage = "Invalid credentials")
        whenever(
            mockMqttRepository.sendTyped(
                eq("reader_login_requested"), eq("operator_context"), any(),
                eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.login(LoginMethod.Credentials("operator1", "wrong"))

        assertTrue(result.isFailure)
        assertEquals("Invalid credentials", result.exceptionOrNull()?.message)
        verify(mockSessionHolder, never()).set(any())
    }

    @Test
    fun `login with badge uses login_tag_scanned request type`() = runTest {
        val response = OperatorContextResponse(operatorSessionId = "sess-2", success = true, operatorName = "Bob")
        whenever(
            mockMqttRepository.sendTyped(
                eq("login_tag_scanned"), eq("operator_context"), any(),
                eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Success(response))

        val result = useCase.login(LoginMethod.Badge("TAG-JSMITH"))

        assertTrue(result.isSuccess)
        verify(mockSessionHolder).set(argThat { it.operatorSessionId == "sess-2" })
    }

    @Test
    fun `login when disconnected returns failure without setting session`() = runTest {
        whenever(
            mockMqttRepository.sendTyped(
                any(), any(), any(), eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Disconnected)

        val result = useCase.login(LoginMethod.Credentials("operator1", "1234"))

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
        verify(mockSessionHolder, never()).set(any())
    }

    @Test
    fun `logout always clears session locally`() = runTest {
        whenever(mockSessionHolder.currentSessionIdOrEmpty()).thenReturn("sess-1")
        whenever(
            mockMqttRepository.sendTyped(
                eq("reader_logout_requested"), eq("operator_context"), any(),
                eq(OperatorContextResponse::class.java), eq(false)
            )
        ).thenReturn(MqttTypedResult.Error("timeout"))

        val result = useCase.logout()

        assertTrue(result.isSuccess)
        verify(mockSessionHolder).clear()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.AuthUseCaseTest"`
Expected: FAIL — `AuthUseCase` and `LoginMethod` don't exist yet.

- [ ] **Step 3: Implement**

`app/src/main/java/com/ppnam/station2aa/domain/usecase/AuthUseCase.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.Gson
import com.mitas.ppnam.station2aa.data.mqtt.MqttTypedResult
import com.mitas.ppnam.station2aa.data.mqtt.dto.LoginTagScannedRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.mitas.ppnam.station2aa.data.mqtt.dto.ReaderLoginRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.ReaderLogoutRequest
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

sealed class LoginMethod {
    data class Credentials(val username: String, val password: String) : LoginMethod()
    data class Badge(val badgeTag: String) : LoginMethod()
}

class AuthUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val sessionHolder: OperatorSessionHolder,
    private val settingsRepository: SettingsRepository
) {
    private val gson = Gson()

    suspend fun login(method: LoginMethod): Result<OperatorSession> {
        val deviceId = settingsRepository.current().deviceId
        val messageId = UUID.randomUUID().toString()
        val timestampUtc = Instant.now().toString()

        val requestType: String
        val requestJson: String
        when (method) {
            is LoginMethod.Credentials -> {
                requestType = "reader_login_requested"
                requestJson = gson.toJson(
                    ReaderLoginRequest(
                        messageId = messageId,
                        deviceId = deviceId,
                        timestampUtc = timestampUtc,
                        correlationKey = messageId,
                        username = method.username,
                        password = method.password
                    )
                )
            }
            is LoginMethod.Badge -> {
                requestType = "login_tag_scanned"
                requestJson = gson.toJson(
                    LoginTagScannedRequest(
                        messageId = messageId,
                        deviceId = deviceId,
                        timestampUtc = timestampUtc,
                        correlationKey = messageId,
                        badgeTag = method.badgeTag
                    )
                )
            }
        }

        val result = mqttRepository.sendTyped(
            requestType = requestType,
            responseType = "operator_context",
            requestJson = requestJson,
            responseClass = OperatorContextResponse::class.java,
            allowOfflineQueue = false
        )

        return when (result) {
            is MqttTypedResult.Success -> {
                val response = result.response
                val sessionId = response.operatorSessionId
                if (response.success && !sessionId.isNullOrBlank()) {
                    val session = OperatorSession(
                        operatorSessionId = sessionId,
                        operatorId = response.operatorId ?: "",
                        operatorName = response.operatorName ?: "",
                        role = response.role ?: "",
                        allowedActions = response.allowedActions,
                        allowedTabs = response.allowedTabs
                    )
                    sessionHolder.set(session)
                    Result.success(session)
                } else {
                    Result.failure(Exception(response.errorMessage ?: "Login failed"))
                }
            }
            is MqttTypedResult.Error -> Result.failure(Exception(result.message))
            MqttTypedResult.Disconnected -> Result.failure(Exception("Not connected to Station 2"))
            MqttTypedResult.Queued -> Result.failure(Exception("Not connected to Station 2"))
        }
    }

    suspend fun logout(): Result<Unit> {
        val deviceId = settingsRepository.current().deviceId
        val messageId = UUID.randomUUID().toString()
        val requestJson = gson.toJson(
            ReaderLogoutRequest(
                messageId = messageId,
                deviceId = deviceId,
                operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
                timestampUtc = Instant.now().toString(),
                correlationKey = messageId
            )
        )
        mqttRepository.sendTyped(
            requestType = "reader_logout_requested",
            responseType = "operator_context",
            requestJson = requestJson,
            responseClass = OperatorContextResponse::class.java,
            allowOfflineQueue = false
        )
        sessionHolder.clear()
        return Result.success(Unit)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.AuthUseCaseTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/usecase/AuthUseCase.kt app/src/test/java/com/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt
git commit -m "feat(auth): add AuthUseCase for reader login and logout"
```

---

## Task 7: `LoginViewModel`

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/login/LoginViewModel.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/login/LoginViewModelTest.kt`

**Interfaces:**
- Consumes: `AuthUseCase.login/logout` (Task 6), `ScanEventBus.events` + `ScanEvent.RfidTag` (existing), `MqttRepository.connectionState/connect()` (existing), `OfflineQueueRepository.pendingCount()` (existing).
- Produces: `LoginUiState` sealed class (`Idle`, `LoggingIn`, `Error`, `LoggedIn`), `LoginViewModel` (`uiState`, `connectionState`, `pendingCount`, `navigationEvent`, `submitCredentials()`, `retry()`) — used by Task 8 (`LoginScreen`).

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/ppnam/station2aa/ui/login/LoginViewModelTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.login

import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.LoginMethod
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class LoginViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockOfflineQueueRepository: OfflineQueueRepository
    private lateinit var scanEvents: MutableSharedFlow<ScanEvent>
    private lateinit var viewModel: LoginViewModel

    private val sampleSession = OperatorSession(
        operatorSessionId = "sess-1",
        operatorId = "OP-1",
        operatorName = "Jane Smith",
        role = "Operator"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockAuthUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockOfflineQueueRepository = mock()
        scanEvents = MutableSharedFlow(extraBufferCapacity = 16)

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockOfflineQueueRepository.pendingCount()).thenReturn(flowOf(0))
        whenever(mockScanEventBus.events).thenReturn(scanEvents)

        viewModel = LoginViewModel(mockAuthUseCase, mockScanEventBus, mockMqttRepository, mockOfflineQueueRepository)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `initial state is Idle`() = runTest {
        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    @Test
    fun `submitCredentials success sets LoggedIn and fires navigation event`() = runTest {
        whenever(mockAuthUseCase.login(LoginMethod.Credentials("operator1", "1234")))
            .thenReturn(Result.success(sampleSession))

        val navEvents = mutableListOf<String>()
        val job = launch(testDispatcher) { viewModel.navigationEvent.collect { navEvents.add(it) } }

        viewModel.submitCredentials("operator1", "1234")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.LoggedIn)
        assertTrue(navEvents.contains("home"))
        job.cancel()
    }

    @Test
    fun `submitCredentials failure sets Error state`() = runTest {
        whenever(mockAuthUseCase.login(any()))
            .thenReturn(Result.failure(Exception("Invalid credentials")))

        viewModel.submitCredentials("operator1", "wrong")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertEquals("Invalid credentials", (state as LoginUiState.Error).message)
    }

    @Test
    fun `retry after error resets state to Idle`() = runTest {
        whenever(mockAuthUseCase.login(any()))
            .thenReturn(Result.failure(Exception("Invalid credentials")))
        viewModel.submitCredentials("operator1", "wrong")
        advanceUntilIdle()

        viewModel.retry()

        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    @Test
    fun `badge scan while showing an error still attempts login`() = runTest {
        whenever(mockAuthUseCase.login(any()))
            .thenReturn(Result.failure(Exception("Invalid credentials")))
        viewModel.submitCredentials("operator1", "wrong")
        advanceUntilIdle()

        whenever(mockAuthUseCase.login(LoginMethod.Badge("TAG-JSMITH")))
            .thenReturn(Result.success(sampleSession))
        scanEvents.tryEmit(ScanEvent.RfidTag("TAG-JSMITH", Instant.now()))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.LoggedIn)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.login.LoginViewModelTest"`
Expected: FAIL — `LoginViewModel`, `LoginUiState` don't exist yet.

- [ ] **Step 3: Implement**

`app/src/main/java/com/ppnam/station2aa/ui/login/LoginViewModel.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.LoginMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class LoginUiState {
    object Idle : LoginUiState()
    object LoggingIn : LoginUiState()
    data class Error(val message: String) : LoginUiState()
    object LoggedIn : LoginUiState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authUseCase: AuthUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var badgeScanJob: Job? = null

    init {
        viewModelScope.launch { mqttRepository.connect() }
        startListeningForBadgeScans()
    }

    private fun startListeningForBadgeScans() {
        badgeScanJob?.cancel()
        badgeScanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                attemptLogin(LoginMethod.Badge(event.tagId))
            }
        }
    }

    fun submitCredentials(username: String, password: String) {
        attemptLogin(LoginMethod.Credentials(username, password))
    }

    private fun attemptLogin(method: LoginMethod) {
        if (_uiState.value == LoginUiState.LoggingIn) return
        viewModelScope.launch {
            _uiState.value = LoginUiState.LoggingIn
            authUseCase.login(method)
                .onSuccess {
                    _uiState.value = LoginUiState.LoggedIn
                    _navigationEvent.send("home")
                }
                .onFailure { e ->
                    _uiState.value = LoginUiState.Error(e.message ?: "Login failed")
                }
        }
    }

    fun retry() {
        _uiState.value = LoginUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        badgeScanJob?.cancel()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.login.LoginViewModelTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/login/LoginViewModel.kt app/src/test/java/com/ppnam/station2aa/ui/login/LoginViewModelTest.kt
git commit -m "feat(login): add LoginViewModel with credentials and badge-scan login"
```

---

## Task 8: `LoginScreen`

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/ui/login/LoginScreen.kt`

**Interfaces:**
- Consumes: `LoginViewModel` (Task 7), `AppScaffold` (existing, unchanged signature at this point).
- Produces: `LoginScreen(onLoggedIn: () -> Unit)` composable — used by Task 9 (`AppNavGraph`).

- [ ] **Step 1: Implement**

`app/src/main/java/com/ppnam/station2aa/ui/login/LoginScreen.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.login

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == "home") onLoggedIn()
        }
    }

    AppScaffold(
        title = "Log In",
        connectionState = connectionState,
        pendingCount = pendingCount
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    Text(
                        "Or scan your badge",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )

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
                }
            }
        }
    }
}
```

No dedicated test — this codebase has no Compose UI test files for any screen (only ViewModels are unit tested); `LoginScreen` follows that existing convention. It is exercised manually in Task 9's verification step.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/login/LoginScreen.kt
git commit -m "feat(login): add LoginScreen with credentials form and badge-scan hint"
```

---

## Task 9: Navigation — `LoginScreen` as start destination

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`

**Interfaces:**
- Consumes: `LoginScreen` (Task 8), `NavRoutes.HOME` (existing).
- Produces: `NavRoutes.LOGIN` — used by Task 10 (logout redirect).

- [ ] **Step 1: Add the route**

`app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt` — full file:

```kotlin
package com.mitas.ppnam.station2aa.navigation

object NavRoutes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val JOB_LOOKUP = "mixing/job_lookup"
    const val INGREDIENT_SCAN = "mixing/ingredient_scan/{orderNo}"
    const val HOPPER_SCAN = "mixing/hopper_scan/{orderNo}"
    const val PREMIX_COMPLETE = "mixing/premix_complete/{orderNo}"
    const val MACHINE_SELECT = "rajoo/machine_select"
    const val PALLET_ALLOC = "rajoo/pallet_alloc/{machineCode}"
    const val RFID_RECOVERY = "rfid/recovery"
    const val DASHBOARD = "dashboard"

    fun ingredientScan(orderNo: String) = "mixing/ingredient_scan/$orderNo"
    fun hopperScan(orderNo: String) = "mixing/hopper_scan/$orderNo"
    fun premixComplete(orderNo: String) = "mixing/premix_complete/$orderNo"
    fun palletAlloc(machineCode: String) = "rajoo/pallet_alloc/$machineCode"
}
```

- [ ] **Step 2: Make `login` the start destination and wire the login→home transition**

`app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt` — modify the top of the file (imports and the `NavHost` opening):

```kotlin
package com.mitas.ppnam.station2aa.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.mitas.ppnam.station2aa.ui.dashboard.DashboardScreen
import com.mitas.ppnam.station2aa.ui.home.HomeScreen
import com.mitas.ppnam.station2aa.ui.login.LoginScreen
import com.mitas.ppnam.station2aa.ui.mixing.HopperScanScreen
import com.mitas.ppnam.station2aa.ui.mixing.IngredientScanScreen
import com.mitas.ppnam.station2aa.ui.mixing.JobLookupScreen
import com.mitas.ppnam.station2aa.ui.mixing.MixingViewModel
import com.mitas.ppnam.station2aa.ui.mixing.PreMixCompleteScreen
import com.mitas.ppnam.station2aa.ui.rajoo.MachineSelectScreen
import com.mitas.ppnam.station2aa.ui.rajoo.PalletAllocScreen
import com.mitas.ppnam.station2aa.ui.rfid.RfidRecoveryScreen
import com.mitas.ppnam.station2aa.ui.settings.SettingsScreen

@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = NavRoutes.LOGIN) {
        composable(NavRoutes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
        composable(NavRoutes.HOME) {
            HomeScreen(
                onNavigateMixing = { navController.navigate(NavRoutes.JOB_LOOKUP) },
                onNavigateRajoo = { navController.navigate(NavRoutes.MACHINE_SELECT) },
                onNavigateRfidRecovery = { navController.navigate(NavRoutes.RFID_RECOVERY) },
                onNavigateDashboard = { navController.navigate(NavRoutes.DASHBOARD) },
                onNavigateSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onLogout = {
                    navController.navigate(NavRoutes.LOGIN) {
                        popUpTo(0)
                    }
                }
            )
        }
```

The rest of `AppNavGraph.kt` (from `composable(NavRoutes.SETTINGS) { ... }` through the closing `}` of the `navigation` block and the file) is unchanged — leave it exactly as it is today.

Note: this leaves `HomeScreen(...)` calling with an `onLogout` parameter that doesn't exist on `HomeScreen` yet — that's added in Task 10, which must follow immediately (the project will not compile between Task 9 Step 2 and Task 10 Step 1). Do not run the full test suite in between; there is no separate commit boundary here — Tasks 9 and 10 land as one uninterrupted sequence.

- [ ] **Step 3: Commit (staged together with Task 10 — see Task 10's commit step)**

Do not commit yet. Proceed directly to Task 10.

---

## Task 10: Operator identity + logout (`AppScaffold`, `HomeViewModel`, `HomeScreen`)

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: `OperatorSessionHolder.session` (Task 5), `AuthUseCase.logout()` (Task 6), `AppNavGraph`'s `onLogout` wiring from Task 9.
- Produces: `AppScaffold(operatorName, onLogout, ...)` optional params (default `null`, so every other existing screen is unaffected); `HomeScreen(onLogout: () -> Unit)`.

- [ ] **Step 1: Write the failing test for `HomeViewModel`**

`app/src/test/java/com/ppnam/station2aa/ui/home/HomeViewModelTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockOfflineQueueRepository: OfflineQueueRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockMqttRepository = mock()
        mockOfflineQueueRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockOfflineQueueRepository.pendingCount()).thenReturn(flowOf(0))
        whenever(mockSessionHolder.session).thenReturn(
            MutableStateFlow(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        )

        viewModel = HomeViewModel(mockMqttRepository, mockOfflineQueueRepository, mockAuthUseCase, mockSessionHolder)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `session reflects the operator session holder`() = runTest {
        assertEquals("Jane Smith", viewModel.session.value?.operatorName)
    }

    @Test
    fun `logout calls AuthUseCase and fires logoutEvent`() = runTest {
        whenever(mockAuthUseCase.logout()).thenReturn(Result.success(Unit))

        val events = mutableListOf<Unit>()
        val job = launch(testDispatcher) { viewModel.logoutEvent.collect { events.add(it) } }

        viewModel.logout()
        advanceUntilIdle()

        verify(mockAuthUseCase).logout()
        assertEquals(1, events.size)
        job.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.home.HomeViewModelTest"`
Expected: FAIL — `HomeViewModel` doesn't take `AuthUseCase`/`OperatorSessionHolder`, has no `session`/`logout()`/`logoutEvent`.

- [ ] **Step 3: Add optional operator identity + logout to `AppScaffold`**

`app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt` — full file:

```kotlin
package com.mitas.ppnam.station2aa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    connectionState: MqttConnectionState,
    pendingCount: Int,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    operatorName: String? = null,
    onLogout: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val (dotColor, statusLabel) = when (connectionState) {
        MqttConnectionState.CONNECTED    -> SuccessGreen to "Connected"
        MqttConnectionState.RECONNECTING -> WarningOrange to "Reconnecting"
        MqttConnectionState.DISCONNECTED ->
            DangerRed to if (pendingCount > 0) "Offline — $pendingCount queued" else "Offline"
    }

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
                }) { Text("Log out", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            },
            containerColor = GraphiteSurface
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = AmberPrimary
                            )
                        }
                    }
                },
                actions = {
                    if (operatorName != null) {
                        TextButton(onClick = { showLogoutDialog = true }) {
                            Text(operatorName, color = TextPrimary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (onSettings != null) {
                        IconButton(onClick = onSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = TextMuted
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(dotColor.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(modifier = Modifier.size(6.dp)) {
                                drawCircle(color = dotColor)
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = dotColor
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GraphiteSurface
                )
            )
        },
        containerColor = GraphiteBackground,
        content = content
    )
}
```

- [ ] **Step 4: Wire session + logout into `HomeViewModel`**

`app/src/main/java/com/ppnam/station2aa/ui/home/HomeViewModel.kt` — full file:

```kotlin
package com.mitas.ppnam.station2aa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder
) : ViewModel() {

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private val _logoutEvent = Channel<Unit>(Channel.BUFFERED)
    val logoutEvent: Flow<Unit> = _logoutEvent.receiveAsFlow()

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }
}
```

Note `mqttRepository.connect()` is no longer called here — `LoginViewModel.init` (Task 7) now owns connecting, since `LoginScreen` is the app's true entry point.

- [ ] **Step 5: Run `HomeViewModelTest` to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.home.HomeViewModelTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: Wire operator identity + logout into `HomeScreen`**

`app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt` — modify only the `HomeScreen` function signature and body (the `HomeTile` private composable below it is unchanged):

```kotlin
@Composable
fun HomeScreen(
    onNavigateMixing: () -> Unit,
    onNavigateRajoo: () -> Unit,
    onNavigateRfidRecovery: () -> Unit,
    onNavigateDashboard: () -> Unit,
    onNavigateSettings: () -> Unit,
    onLogout: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val session by viewModel.session.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.logoutEvent.collect { onLogout() }
    }

    AppScaffold(
        title = "PPNAM Station 2",
        connectionState = connectionState,
        pendingCount = pendingCount,
        onBack = null,
        onSettings = onNavigateSettings,
        operatorName = session?.operatorName,
        onLogout = viewModel::logout
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeTile(
                    title = "Mixing",
                    subtitle = "Pre-Mix Flow",
                    icon = Icons.Filled.Science,
                    accentColor = AmberPrimary,
                    height = 220.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateMixing
                )
                HomeTile(
                    title = "Rajoo",
                    subtitle = "Allocation",
                    icon = Icons.Filled.Factory,
                    accentColor = SuccessGreen,
                    height = 220.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateRajoo
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeTile(
                    title = "RFID Recovery",
                    subtitle = null,
                    icon = Icons.Filled.WifiTethering,
                    accentColor = InfoBlue,
                    height = 110.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateRfidRecovery
                )
                HomeTile(
                    title = "Dashboard",
                    subtitle = null,
                    icon = Icons.Filled.BarChart,
                    accentColor = IndigoAccent,
                    height = 110.dp,
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateDashboard
                )
            }
        }
    }
}
```

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS.

- [ ] **Step 8: Build the debug APK to confirm the whole module compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Manual verification**

Install and run the app on a device/emulator (or `./gradlew installDebug`):
1. Cold-launch the app → `LoginScreen` is shown (not Home).
2. Enter any username/password and submit (no real backend yet, so this will time out/show a connection error — confirm the error is shown inline and the form stays enabled for retry, with no lockout).
3. Confirm the back button on `LoginScreen` exits the app rather than navigating anywhere.

Full login → Home → logout round-trip against a real Station 2 backend is out of scope for manual verification until Station 2 exists to respond — this phase's manual check is limited to confirming the screen, form, and error/retry behavior render and behave correctly client-side.

- [ ] **Step 10: Commit Tasks 9 and 10 together**

```bash
git add app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt app/src/main/java/com/ppnam/station2aa/ui/home/HomeViewModel.kt app/src/main/java/com/ppnam/station2aa/ui/home/HomeScreen.kt app/src/test/java/com/ppnam/station2aa/ui/home/HomeViewModelTest.kt
git commit -m "feat(nav): make LoginScreen the start destination; add operator identity and logout to Home"
```

---

## Task 11: `SettingsScreen` — Device ID field

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `AppSettings.deviceId` (Task 1), existing `SettingsTextField` helper, existing `SettingsViewModel.updateDraft`.

- [ ] **Step 1: Add the field**

In `app/src/main/java/com/ppnam/station2aa/ui/settings/SettingsScreen.kt`, inside `ConfigSection(title = "Station") { ... }`, add a `Device ID` field between the existing `Station Name` and `Scanner ID` fields:

```kotlin
                    ConfigSection(title = "Station") {
                        SettingsTextField(
                            value = draft.stationName,
                            label = "Station Name",
                            onValueChange = { viewModel.updateDraft(draft.copy(stationName = it)) }
                        )
                        SettingsTextField(
                            value = draft.deviceId,
                            label = "Device ID",
                            onValueChange = { viewModel.updateDraft(draft.copy(deviceId = it)) }
                        )
                        SettingsTextField(
                            value = draft.scannerId.toString(),
                            label = "Scanner ID",
                            keyboardType = KeyboardType.Number,
                            onValueChange = {
                                viewModel.updateDraft(draft.copy(scannerId = it.toIntOrNull() ?: draft.scannerId))
                            }
                        )
                    }
```

No `SettingsViewModel` change is needed — `draftSettings` is already a plain `AppSettings`, so `draft.copy(deviceId = it)` works as soon as `AppSettings.deviceId` exists (Task 1).

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: All tests PASS (no `SettingsViewModelTest` changes needed — its assertions don't touch `deviceId`).

- [ ] **Step 3: Manual verification**

Run the app, open Settings, enter the supervisor PIN (`079545`), confirm a "Device ID" field appears between "Station Name" and "Scanner ID" pre-filled with `handheld_1`, and that editing + "Test & Apply" persists it (re-open Settings to confirm the edited value survives).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/settings/SettingsScreen.kt
git commit -m "feat(settings): expose Device ID configuration field"
```

---

## Final check

- [ ] Run the entire suite one more time: `./gradlew testDebugUnitTest` — expect all tests green (existing ~59 plus this plan's new ones).
- [ ] Run `./gradlew assembleDebug` — expect BUILD SUCCESSFUL.
- [ ] Confirm no file outside this plan's list was touched (`git status`), and that no mixing/rajoo/rfid/dashboard screen's business logic changed — only `AppScaffold`'s optional new params and `HomeScreen`/`HomeViewModel` were touched among existing screens.
