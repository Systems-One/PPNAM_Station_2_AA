# MQTT Schema 3.0 Protocol Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the schema 2.0 MQTT transport with a schema 3.0 foundation whose envelope is owned by the transport, whose responses correlate on `inResponseToMessageId`, and which proves itself end-to-end via pallet lookup and holding recovery.

**Architecture:** Callers pass only message-specific payload fields; `MqttRepositoryImpl.request()` injects the envelope, publishes at QoS 1 to `PPNAM/{deviceId}/req/{type}`, and resolves the response through a `ConcurrentHashMap` of pending `CompletableDeferred`s keyed by `messageId`. Retries republish byte-identical payloads to preserve replay identity. All legacy `{station}/request` code, the offline queue, and the Dashboard/Rajoo screens are deleted.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, Gson, HiveMQ MQTT5 client, JUnit4 + mockito-kotlin + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-07-16-mqtt-schema-3-protocol-foundation-design.md`
**Contract:** `C:\Dev\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` v3.0 (read-only reference)

## Global Constraints

- `schemaVersion` MUST be exactly `"3.0"`, defined in exactly one place (`MqttSchema.VERSION`).
- Workflow messages use **QoS 1 (AT_LEAST_ONCE)** and are **not retained**.
- Presence messages use **QoS 1** and **ARE retained**.
- Request topics: `PPNAM/{deviceId}/req/{requestType}`. Response subscription: `PPNAM/{deviceId}/res/+`.
- `deviceId`, `requestType`, and `responseType` may not contain `/`, `+`, or `#`.
- `inResponseToMessageId` is the ONLY valid way to match a response to a request. Never match on topic or `correlationKey`.
- A retry MUST republish byte-identical payload: same `messageId`, same `timestampUtc`. Never regenerate either.
- An unused optional request field MUST be omitted, never sent as `null` or `""`. (Gson omits nulls by default — rely on this.)
- `operatorSessionId` uses `""` only for `login_requested`; otherwise the active session id.
- Station 2 presence topic is the literal string `PPNAM/station_2/status`.
- Run tests with: `./gradlew testDebugUnitTest`
- Run a single test class with: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttTopicsTest"`

## Sequencing Rationale

Tasks 1–9 build the new stack alongside the old one, so the build stays green. Tasks 10–13 move consumers onto it. Tasks 14–17 delete the old stack once nothing references it. Do not reorder: deleting before porting breaks compilation.

---

### Task 1: Schema constant and v3 topics

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttSchema.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTopics.kt` (full rewrite)
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttTopicsTest.kt` (full rewrite)

**Interfaces:**
- Consumes: nothing.
- Produces: `MqttSchema.VERSION: String`; `MqttTopics.request(deviceId: String, requestType: String): String`, `MqttTopics.responseWildcard(deviceId: String): String`, `MqttTopics.deviceStatus(deviceId: String): String`, `MqttTopics.STATION_STATUS: String`, `MqttTopics.responseTypeOf(topic: String): String`.

The legacy builders (`request(stationName)`, `response`, `hopperStatus`, `stationStatus`, `contractRequest`, `contractResponse`, `contractResponseWildcard`) are deleted here. Their only production callers are inside `MqttRepositoryImpl.subscribeAndAnnounce`, `sendWithTimeout`, `sendTyped`, and `publishTyped`, which Task 7 and Task 15 rewrite. To keep the build green between tasks, this task also updates those call sites minimally — shown in Step 5.

- [ ] **Step 1: Write the failing test**

Replace the entire contents of `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttTopicsTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MqttTopicsTest {

    @Test
    fun `request topic uses the req segment`() {
        assertEquals(
            "PPNAM/handheld_1/req/login_requested",
            MqttTopics.request("handheld_1", "login_requested")
        )
    }

    @Test
    fun `responseWildcard subscribes to the res segment only`() {
        assertEquals("PPNAM/handheld_1/res/+", MqttTopics.responseWildcard("handheld_1"))
    }

    @Test
    fun `deviceStatus topic for a device`() {
        assertEquals("PPNAM/handheld_1/status", MqttTopics.deviceStatus("handheld_1"))
    }

    @Test
    fun `station status is the literal contract topic`() {
        assertEquals("PPNAM/station_2/status", MqttTopics.STATION_STATUS)
    }

    @Test
    fun `responseTypeOf extracts the last topic segment`() {
        assertEquals(
            "operator_context",
            MqttTopics.responseTypeOf("PPNAM/handheld_1/res/operator_context")
        )
    }

    @Test
    fun `deviceId containing a slash is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("hand/held", "login_requested")
        }
    }

    @Test
    fun `requestType containing a plus wildcard is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("handheld_1", "login+requested")
        }
    }

    @Test
    fun `requestType containing a hash wildcard is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("handheld_1", "login#requested")
        }
    }

    @Test
    fun `blank deviceId is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.request("", "login_requested")
        }
    }

    @Test
    fun `responseWildcard validates its device id`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.responseWildcard("hand#held")
        }
    }

    @Test
    fun `deviceStatus validates its device id`() {
        assertThrows(IllegalArgumentException::class.java) {
            MqttTopics.deviceStatus("hand+held")
        }
    }

    @Test
    fun `schema version is exactly 3 point 0`() {
        assertEquals("3.0", MqttSchema.VERSION)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttTopicsTest"`
Expected: FAIL — compilation error, `Unresolved reference: MqttSchema` and `Too many arguments for request`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttSchema.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * The one place the wire schema version is defined. Contract v3.0 rejects any request whose
 * schemaVersion is not exactly "3.0" with errorCode `unsupported_schema`.
 */
object MqttSchema {
    const val VERSION = "3.0"
}
```

Replace the entire contents of `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTopics.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * Contract v3.0 topic structure:
 *
 *   PPNAM/{deviceId}/status
 *   PPNAM/{deviceId}/req/{requestType}
 *   PPNAM/{deviceId}/res/{responseType}
 *
 * A handheld subscribes to PPNAM/{ownDeviceId}/res/+ and PPNAM/station_2/status.
 */
object MqttTopics {

    /** Station 2's presence topic is a fixed literal in the contract, not a configured name. */
    const val STATION_STATUS = "PPNAM/station_2/status"

    fun request(deviceId: String, requestType: String): String {
        validateSegment(deviceId, "deviceId")
        validateSegment(requestType, "requestType")
        return "PPNAM/$deviceId/req/$requestType"
    }

    fun responseWildcard(deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "PPNAM/$deviceId/res/+"
    }

    fun deviceStatus(deviceId: String): String {
        validateSegment(deviceId, "deviceId")
        return "PPNAM/$deviceId/status"
    }

    fun responseTypeOf(topic: String): String = topic.substringAfterLast('/')

    // The contract forbids '/', '+' and '#' in a topic segment. A segment carrying one of these
    // would silently reshape the topic (or subscribe to a wildcard), so fail loudly instead.
    private fun validateSegment(value: String, name: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value.none { it == '/' || it == '+' || it == '#' }) {
            "$name must not contain '/', '+' or '#': was '$value'"
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttTopicsTest"`
Expected: PASS (12 tests).

- [ ] **Step 5: Keep the build green at the old call sites**

`MqttRepositoryImpl` still references the deleted builders. Apply these exact edits so the module compiles; Tasks 6 and 14 replace this code properly.

In `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`, replace the body of `subscribeAndAnnounce` (currently lines 138–161) with:

```kotlin
    private suspend fun subscribeAndAnnounce(client: Mqtt5AsyncClient, stationName: String, deviceId: String) {
        client.subscribeWith()
            .topicFilter(MqttTopics.responseWildcard(deviceId))
            .callback { publish -> handleIncomingTyped(publish.topic.toString(), publish.payloadAsBytes) }
            .send()
            .await()
        client.publishWith()
            .topic(MqttTopics.deviceStatus(deviceId))
            .payload(STATUS_ONLINE)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .send()
            .await()
    }
```

In `sendWithTimeout`, replace:

```kotlin
                    .topic(MqttTopics.request(currentStationName))
```

with:

```kotlin
                    .topic(MqttTopics.request(currentDeviceId, action))
```

In `sendTyped`, replace:

```kotlin
                    .topic(MqttTopics.contractRequest(currentDeviceId, requestType))
```

with:

```kotlin
                    .topic(MqttTopics.request(currentDeviceId, requestType))
```

In `publishTyped`, replace:

```kotlin
                .topic(MqttTopics.contractRequest(currentDeviceId, requestType))
```

with:

```kotlin
                .topic(MqttTopics.request(currentDeviceId, requestType))
```

Delete the now-unused private method `handleHopperStatus` (lines 421–426) and the `handleIncoming` method (lines 395–400), plus the `_incomingResponses` field (line 50) and the `_hopperStatusUpdates`/`hopperStatusUpdates` fields (lines 53–54). Because `sendWithTimeout` reads `_incomingResponses`, replace its whole body with a fail-fast stub — the legacy path has never worked and Task 14 deletes it entirely:

```kotlin
    internal suspend fun sendWithTimeout(action: String, dataJson: String, timeoutMs: Long): MqttResult =
        MqttResult.Error("Legacy action protocol removed; use request()")
```

Remove the now-unused imports `com.mitas.ppnam.station2aa.domain.model.HopperStatus` and `kotlinx.coroutines.flow.SharedFlow` if the compiler flags them.

In `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`, delete the line:

```kotlin
    val hopperStatusUpdates: SharedFlow<HopperStatus>
```

and its `HopperStatus` / `SharedFlow` imports.

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`, delete line 90:

```kotlin
    val hopperStatusUpdates: SharedFlow<HopperStatus> = mqttRepository.hopperStatusUpdates
```

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/HopperScanScreen.kt`, delete the block starting at line 34 that reads `viewModel.hopperStatusUpdates.collect { update -> ... }`, including its enclosing `LaunchedEffect`.

- [ ] **Step 6: Delete the now-dead tests**

From `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt`, delete these four tests, which cover deleted behaviour:
- `hopperStatusUpdates emits parsed HopperStatus on hopper topic message`
- `hopperStatusUpdates does not crash on malformed payload`
- `send fails fast when disconnected instead of queuing`
- `send fails fast when disconnected regardless of action`

Also delete the now-unused import `com.mitas.ppnam.station2aa.domain.model.HopperAvailability`.

From `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`, delete line 65:

```kotlin
        whenever(mockMqttRepository.hopperStatusUpdates)
```

and the rest of that stubbing statement.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — everything compiles and all remaining tests are green.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttSchema.kt \
        app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTopics.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttTopicsTest.kt \
        app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt \
        app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt \
        app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt \
        app/src/main/java/com/ppnam/station2aa/ui/mixing/HopperScanScreen.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt \
        app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt
git commit -m "feat(mqtt): add v3 req/res topics and schema 3.0 constant"
```

---

### Task 2: Error code and next action vocabulary

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttVocabulary.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttVocabularyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `ErrorCode(raw: String)` value class with companion constants; `NextAction(raw: String)` value class with companion constants.

Value classes rather than enums: the contract calls these "shared across message families", implying message-specific codes may also appear. An unknown value must pass through rather than crash the parse.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttVocabularyTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MqttVocabularyTest {

    @Test
    fun `error code constants carry their contract wire values`() {
        assertEquals("invalid_json", ErrorCode.INVALID_JSON.raw)
        assertEquals("session_required", ErrorCode.SESSION_REQUIRED.raw)
        assertEquals("message_expired", ErrorCode.MESSAGE_EXPIRED.raw)
        assertEquals("message_id_reused", ErrorCode.MESSAGE_ID_REUSED.raw)
        assertEquals("permission_denied", ErrorCode.PERMISSION_DENIED.raw)
        assertEquals("unsupported_schema", ErrorCode.UNSUPPORTED_SCHEMA.raw)
    }

    @Test
    fun `error codes compare by value`() {
        assertEquals(ErrorCode.SESSION_REQUIRED, ErrorCode("session_required"))
        assertNotEquals(ErrorCode.SESSION_REQUIRED, ErrorCode.NOT_FOUND)
    }

    @Test
    fun `an unknown error code is preserved rather than rejected`() {
        val unknown = ErrorCode("some_future_backend_code")
        assertEquals("some_future_backend_code", unknown.raw)
    }

    @Test
    fun `next action constants carry their contract wire values`() {
        assertEquals("", NextAction.NONE.raw)
        assertEquals("login", NextAction.LOGIN.raw)
        assertEquals("recover_holding", NextAction.RECOVER_HOLDING.raw)
        assertEquals("choose_destination", NextAction.CHOOSE_DESTINATION.raw)
        assertEquals("retry_with_manager_approval", NextAction.RETRY_WITH_MANAGER_APPROVAL.raw)
        assertEquals("assign_or_finish_hopper", NextAction.ASSIGN_OR_FINISH_HOPPER.raw)
    }

    @Test
    fun `next actions compare by value`() {
        assertEquals(NextAction.LOGIN, NextAction("login"))
        assertNotEquals(NextAction.LOGIN, NextAction.NONE)
    }

    @Test
    fun `an unknown next action is preserved rather than rejected`() {
        assertEquals("do_a_new_thing", NextAction("do_a_new_thing").raw)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttVocabularyTest"`
Expected: FAIL — `Unresolved reference: ErrorCode`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttVocabulary.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * Contract v3.0 `errorCode`. A value class rather than an enum: the contract defines these as
 * "shared across message families", so message-specific codes may also arrive. An unknown code
 * must pass through intact rather than fail the parse.
 */
@JvmInline
value class ErrorCode(val raw: String) {
    companion object {
        val INVALID_JSON = ErrorCode("invalid_json")
        val INVALID_ENVELOPE = ErrorCode("invalid_envelope")
        val UNSUPPORTED_SCHEMA = ErrorCode("unsupported_schema")
        val DEVICE_MISMATCH = ErrorCode("device_mismatch")
        val DEVICE_NOT_CONFIGURED = ErrorCode("device_not_configured")
        val MESSAGE_EXPIRED = ErrorCode("message_expired")
        val SESSION_REQUIRED = ErrorCode("session_required")
        val PERMISSION_DENIED = ErrorCode("permission_denied")
        val NOT_FOUND = ErrorCode("not_found")
        val STATE_CONFLICT = ErrorCode("state_conflict")
        val MACHINE_UNAVAILABLE = ErrorCode("machine_unavailable")
        val VALIDATION_FAILED = ErrorCode("validation_failed")
        val MESSAGE_ID_REUSED = ErrorCode("message_id_reused")
        val SERVICE_UNAVAILABLE = ErrorCode("service_unavailable")
    }
}

/**
 * Contract v3.0 `nextAction`. Guidance for the scanner UI, never authorization.
 */
@JvmInline
value class NextAction(val raw: String) {
    companion object {
        val NONE = NextAction("")
        val LOGIN = NextAction("login")
        val SCAN_JOB_CARD = NextAction("scan_job_card")
        val ACTIVE_JOB_CARDS = NextAction("active_job_cards")
        val SCAN_INGREDIENT = NextAction("scan_ingredient")
        val RECOVER_HOLDING = NextAction("recover_holding")
        val RETRY_WITH_MANAGER_APPROVAL = NextAction("retry_with_manager_approval")
        val CHOOSE_DESTINATION = NextAction("choose_destination")
        val ASSIGN_OR_FINISH_HOPPER = NextAction("assign_or_finish_hopper")
        val SCAN_SAME_MACHINE_TO_FINISH = NextAction("scan_same_machine_to_finish")
        val ALLOCATE_PREMIX = NextAction("allocate_premix")
        val REVIEW_ALLOCATION = NextAction("review_allocation")
        val COMPLETE_STATION2_WORK = NextAction("complete_station2_work")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttVocabularyTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttVocabulary.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttVocabularyTest.kt
git commit -m "feat(mqtt): add v3 errorCode and nextAction vocabulary"
```

---

### Task 3: Response envelope and MqttOutcome

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/ResponseEnvelope.kt`
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttOutcome.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/ResponseEnvelopeTest.kt`

**Interfaces:**
- Consumes: `ErrorCode`, `NextAction` (Task 2).
- Produces: `ResponseEnvelope` data class; `MqttOutcome<T>` sealed interface with `Accepted<T>(body, nextAction)`, `Rejected<T>(body, errorCode, reason, nextAction)`, `NoResponse(kind)`; `FailureKind` enum.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/ResponseEnvelopeTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseEnvelopeTest {

    private val gson = Gson()

    @Test
    fun `parses a full accepted response envelope`() {
        val json = """
            {
              "messageId": "server-generated",
              "inResponseToMessageId": "machine-start-0001",
              "schemaVersion": "3.0",
              "deviceId": "handheld_1",
              "operatorSessionId": "session-id",
              "timestampUtc": "2026-07-16T10:30:01Z",
              "correlationKey": "COL_000123",
              "accepted": true,
              "reason": null,
              "errorCode": null,
              "nextAction": "scan_same_machine_to_finish"
            }
        """.trimIndent()

        val env = gson.fromJson(json, ResponseEnvelope::class.java)

        assertEquals("machine-start-0001", env.inResponseToMessageId)
        assertEquals("3.0", env.schemaVersion)
        assertEquals("COL_000123", env.correlationKey)
        assertTrue(env.accepted)
        assertNull(env.reason)
        assertNull(env.errorCode)
        assertEquals("scan_same_machine_to_finish", env.nextAction)
    }

    @Test
    fun `parses a rejected response envelope carrying an error code`() {
        val json = """
            {
              "inResponseToMessageId": "ingredient-0001",
              "accepted": false,
              "reason": "Manager approval required.",
              "errorCode": "validation_failed",
              "nextAction": "retry_with_manager_approval"
            }
        """.trimIndent()

        val env = gson.fromJson(json, ResponseEnvelope::class.java)

        assertEquals("ingredient-0001", env.inResponseToMessageId)
        assertEquals(false, env.accepted)
        assertEquals("Manager approval required.", env.reason)
        assertEquals("validation_failed", env.errorCode)
    }

    @Test
    fun `absent fields fall back to safe defaults`() {
        val env = gson.fromJson("{}", ResponseEnvelope::class.java)

        assertEquals("", env.inResponseToMessageId)
        assertEquals(false, env.accepted)
        assertNull(env.correlationKey)
        assertNull(env.nextAction)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelopeTest"`
Expected: FAIL — `Unresolved reference: ResponseEnvelope`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/ResponseEnvelope.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * The envelope every contract v3.0 response carries. Parsed from the same flat JSON object as the
 * message-specific body, so the transport can route and classify a response without knowing its type.
 *
 * `inResponseToMessageId` is the only correct way to match a response to its request. `correlationKey`
 * is a trace-grouping key that several in-flight messages deliberately share.
 */
data class ResponseEnvelope(
    val messageId: String = "",
    val inResponseToMessageId: String = "",
    val schemaVersion: String = "",
    val deviceId: String = "",
    val operatorSessionId: String? = null,
    val timestampUtc: String = "",
    val correlationKey: String? = null,
    val accepted: Boolean = false,
    val reason: String? = null,
    val errorCode: String? = null,
    val nextAction: String? = null,
)
```

Create `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttOutcome.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * The result of one contract v3.0 request/response exchange.
 *
 * `Rejected` deliberately carries the typed body: the contract guarantees every response topic has
 * one stable payload shape, so a rejected ingredient scan still returns the full refreshed
 * ingredients[] and hoppers[], and a rejected machine-cycle start still returns conflicts[].
 * Discarding that would force a redundant refresh.
 *
 * `NoResponse` means Station 2 never answered — distinct from a decision it actually made, and an
 * operator must treat it differently.
 *
 * Note `Accepted` means Station 2 processed the request, not that the answer was favourable: a
 * pallet lookup returning found=false is Accepted.
 */
sealed interface MqttOutcome<out T> {

    data class Accepted<T>(
        val body: T,
        val nextAction: NextAction,
    ) : MqttOutcome<T>

    data class Rejected<T>(
        val body: T,
        val errorCode: ErrorCode?,
        val reason: String?,
        val nextAction: NextAction,
    ) : MqttOutcome<T>

    data class NoResponse(val kind: FailureKind) : MqttOutcome<Nothing>
}

enum class FailureKind {
    /** Published, but no matching response arrived within the timeout and retry budget. */
    Timeout,

    /** Not connected to the broker, or the publish itself failed. */
    NotConnected,

    /** A response arrived but could not be parsed. */
    MalformedResponse,
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelopeTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/ResponseEnvelope.kt \
        app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttOutcome.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/dto/ResponseEnvelopeTest.kt
git commit -m "feat(mqtt): add v3 response envelope and MqttOutcome result type"
```

---

### Task 4: Request envelope injection

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/RequestEnvelope.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/RequestEnvelopeTest.kt`

**Interfaces:**
- Consumes: `MqttSchema.VERSION` (Task 1).
- Produces: `RequestEnvelope.build(gson: Gson, payload: Any, messageId: String, deviceId: String, operatorSessionId: String, timestampUtc: String, correlationKey: String?): String`; `object EmptyPayload`.

This is the heart of "the transport owns the envelope": a pure function, fully testable without a broker.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/data/mqtt/RequestEnvelopeTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestEnvelopeTest {

    private val gson = Gson()

    private data class LoginPayload(val username: String, val password: String)

    private fun build(payload: Any, correlationKey: String? = null): String =
        RequestEnvelope.build(
            gson = gson,
            payload = payload,
            messageId = "login-0001",
            deviceId = "handheld_1",
            operatorSessionId = "",
            timestampUtc = "2026-07-16T08:00:00Z",
            correlationKey = correlationKey,
        )

    @Test
    fun `envelope and payload are merged into one flat object`() {
        val json = JsonParser.parseString(build(LoginPayload("operator1", "secret"))).asJsonObject

        assertEquals("login-0001", json.get("messageId").asString)
        assertEquals("3.0", json.get("schemaVersion").asString)
        assertEquals("handheld_1", json.get("deviceId").asString)
        assertEquals("", json.get("operatorSessionId").asString)
        assertEquals("2026-07-16T08:00:00Z", json.get("timestampUtc").asString)
        assertEquals("operator1", json.get("username").asString)
        assertEquals("secret", json.get("password").asString)
    }

    @Test
    fun `an absent correlationKey is omitted rather than sent as null`() {
        val json = JsonParser.parseString(build(LoginPayload("operator1", "secret"))).asJsonObject
        assertFalse(json.has("correlationKey"))
    }

    @Test
    fun `a supplied correlationKey is included`() {
        val json = JsonParser.parseString(
            build(LoginPayload("operator1", "secret"), correlationKey = "COL_000123")
        ).asJsonObject
        assertEquals("COL_000123", json.get("correlationKey").asString)
    }

    @Test
    fun `an envelope-only request serializes to just the envelope`() {
        val json = JsonParser.parseString(build(EmptyPayload)).asJsonObject

        assertEquals("login-0001", json.get("messageId").asString)
        assertEquals("3.0", json.get("schemaVersion").asString)
        assertEquals(5, json.entrySet().size)
    }

    @Test
    fun `schema version always comes from MqttSchema`() {
        val json = JsonParser.parseString(build(EmptyPayload)).asJsonObject
        assertEquals(MqttSchema.VERSION, json.get("schemaVersion").asString)
    }

    @Test
    fun `a payload field never overwrites an envelope field`() {
        // Guard: a payload accidentally carrying its own deviceId must not win. The transport is
        // authoritative for envelope fields.
        val rogue = mapOf("deviceId" to "attacker_device", "username" to "operator1")
        val json = JsonParser.parseString(build(rogue)).asJsonObject
        assertEquals("handheld_1", json.get("deviceId").asString)
        assertEquals("operator1", json.get("username").asString)
    }

    @Test
    fun `a null payload field is omitted`() {
        data class Optional(val bagSizeOption: String?, val bagCount: Double?)
        val json = JsonParser.parseString(build(Optional(null, null))).asJsonObject
        assertFalse(json.has("bagSizeOption"))
        assertFalse(json.has("bagCount"))
        assertTrue(json.has("messageId"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.RequestEnvelopeTest"`
Expected: FAIL — `Unresolved reference: RequestEnvelope`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ppnam/station2aa/data/mqtt/RequestEnvelope.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.Gson

/** Payload for the contract's envelope-only requests (e.g. reader_logout_requested). */
object EmptyPayload

/**
 * Builds a contract v3.0 request as one flat JSON object: the caller's message-specific payload,
 * with the envelope merged in.
 *
 * Callers never construct envelopes. Only the transport knows the device id, the operator session
 * and the clock, so only the transport writes those fields — which is also why envelope fields are
 * written last and always win over anything of the same name in the payload.
 *
 * Gson omits nulls by default, which is exactly the contract's rule that an unused optional field
 * must be omitted rather than sent as null or "".
 */
object RequestEnvelope {

    fun build(
        gson: Gson,
        payload: Any,
        messageId: String,
        deviceId: String,
        operatorSessionId: String,
        timestampUtc: String,
        correlationKey: String?,
    ): String {
        val obj = gson.toJsonTree(payload).asJsonObject
        obj.addProperty("messageId", messageId)
        obj.addProperty("schemaVersion", MqttSchema.VERSION)
        obj.addProperty("deviceId", deviceId)
        obj.addProperty("operatorSessionId", operatorSessionId)
        obj.addProperty("timestampUtc", timestampUtc)
        correlationKey?.let { obj.addProperty("correlationKey", it) }
        return gson.toJson(obj)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.RequestEnvelopeTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/RequestEnvelope.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/RequestEnvelopeTest.kt
git commit -m "feat(mqtt): transport-owned request envelope injection"
```

---

### Task 5: Transport request() with correlation registry

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRequestCorrelationTest.kt`

**Interfaces:**
- Consumes: `MqttTopics.request` (Task 1), `ErrorCode`/`NextAction` (Task 2), `MqttOutcome`/`FailureKind`/`ResponseEnvelope` (Task 3), `RequestEnvelope.build`/`EmptyPayload` (Task 4).
- Produces: `MqttRepository.request(requestType: String, responseType: String, payload: Any, correlationKey: String?, responseClass: Class<T>): MqttOutcome<T>`; internal test seam `MqttRepositoryImpl.publishFn: suspend (String, ByteArray) -> Unit`; internal `MqttRepositoryImpl.handleIncomingResponse(topic: String, bytes: ByteArray)`.

`responseType` is accepted but used only for logging — it CANNOT discriminate, because `login_requested` and `reader_logout_requested` both answer on `operator_context`. Correlation is by `inResponseToMessageId` alone. Keeping the parameter documents the expected topic and gives us a mismatch warning.

`MqttRepositoryImpl` gains an `OperatorSessionHolder` constructor dependency — this is what lets the transport own `operatorSessionId`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRequestCorrelationTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.data.local.OfflineQueueDao
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

data class TestBody(val value: String = "")

class MqttRequestCorrelationTest {

    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            offlineQueueDao = mock<OfflineQueueDao>(),
            sessionHolder = sessionHolder,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        forceConnected()
    }

    private fun forceConnected() {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun messageIdOf(index: Int): String =
        JsonParser.parseString(String(published[index].second)).asJsonObject.get("messageId").asString

    private fun respond(inResponseTo: String, accepted: Boolean = true, value: String = "ok") {
        val json = """
            {"inResponseToMessageId":"$inResponseTo","schemaVersion":"3.0","accepted":$accepted,
             "nextAction":"scan_ingredient","value":"$value"}
        """.trimIndent()
        repo.handleIncomingResponse("PPNAM/handheld_1/res/test_result", json.toByteArray())
    }

    @Test
    fun `two concurrent requests whose responses arrive out of order each resolve correctly`() = runTest {
        val first = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        val second = async {
            repo.request("b_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }

        // Wait until both requests have actually published before answering either.
        while (published.size < 2) yield()

        val firstId = messageIdOf(0)
        val secondId = messageIdOf(1)

        // Answer in REVERSE order. Topic-based matching would hand the second response to the
        // first caller; inResponseToMessageId must not.
        respond(secondId, value = "second")
        respond(firstId, value = "first")

        val firstOutcome = first.await()
        val secondOutcome = second.await()

        assertTrue(firstOutcome is MqttOutcome.Accepted)
        assertTrue(secondOutcome is MqttOutcome.Accepted)
        assertEquals("first", (firstOutcome as MqttOutcome.Accepted).body.value)
        assertEquals("second", (secondOutcome as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `login and logout in flight together on operator_context resolve correctly`() = runTest {
        // Both request types answer on the SAME response topic. This is why topic matching cannot work.
        val login = async {
            repo.request("login_requested", "operator_context", EmptyPayload, null, TestBody::class.java)
        }
        val logout = async {
            repo.request("reader_logout_requested", "operator_context", EmptyPayload, null, TestBody::class.java)
        }
        while (published.size < 2) yield()

        respond(messageIdOf(1), value = "logout")
        respond(messageIdOf(0), value = "login")

        assertEquals("login", (login.await() as MqttOutcome.Accepted).body.value)
        assertEquals("logout", (logout.await() as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `request publishes to the req topic`() = runTest {
        val call = async {
            repo.request("login_requested", "operator_context", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        assertEquals("PPNAM/handheld_1/req/login_requested", published[0].first)
        respond(messageIdOf(0))
        call.await()
    }

    @Test
    fun `request injects the active operator session id`() = runTest {
        sessionHolder.set(
            OperatorSession(
                operatorSessionId = "session-abc",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        val call = async {
            repo.request("active_job_cards_requested", "active_job_cards_list", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val json = JsonParser.parseString(String(published[0].second)).asJsonObject
        assertEquals("session-abc", json.get("operatorSessionId").asString)
        respond(messageIdOf(0))
        call.await()
    }

    @Test
    fun `a rejected response is classified as Rejected and still carries its body`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        respond(messageIdOf(0), accepted = false, value = "still-here")

        val outcome = call.await()
        assertTrue(outcome is MqttOutcome.Rejected)
        assertEquals("still-here", (outcome as MqttOutcome.Rejected).body.value)
        assertEquals(NextAction("scan_ingredient"), outcome.nextAction)
    }

    @Test
    fun `a rejected response exposes its error code`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":false,"errorCode":"session_required","reason":"No session"}""".toByteArray()
        )

        val outcome = call.await() as MqttOutcome.Rejected
        assertEquals(ErrorCode.SESSION_REQUIRED, outcome.errorCode)
        assertEquals("No session", outcome.reason)
    }

    @Test
    fun `request returns NotConnected without publishing when disconnected`() = runTest {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.DISCONNECTED

        val outcome = repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)

        assertEquals(MqttOutcome.NoResponse(FailureKind.NotConnected), outcome)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `an unmatched response is dropped without side effects`() = runTest {
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"nobody-is-waiting","accepted":true}""".toByteArray()
        )
        // No crash, nothing pending. Reaching here is the assertion.
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a response with no inResponseToMessageId is dropped`() = runTest {
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"accepted":true}""".toByteArray()
        )
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a malformed response body yields MalformedResponse`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()
        val id = messageIdOf(0)
        // Valid envelope so it routes, but `value` is an object where a String is expected.
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":true,"value":{"nested":1}}""".toByteArray()
        )
        assertEquals(MqttOutcome.NoResponse(FailureKind.MalformedResponse), call.await())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRequestCorrelationTest"`
Expected: FAIL — compilation error: `MqttRepositoryImpl` has no `sessionHolder` parameter, no `publishFn`, no `request`, no `handleIncomingResponse`.

- [ ] **Step 3: Add request() to the repository interface**

In `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`, add to the interface (keep `sendTyped` for now — Task 14 removes it):

```kotlin
    suspend fun <T : Any> request(
        requestType: String,
        responseType: String,
        payload: Any,
        correlationKey: String?,
        responseClass: Class<T>,
    ): MqttOutcome<T>
```

Add the import:

```kotlin
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
```

- [ ] **Step 4: Implement in MqttRepositoryImpl**

Add the `sessionHolder` constructor parameter:

```kotlin
@Singleton
class MqttRepositoryImpl @Inject constructor(
    private val clientFactory: MqttClientFactory,
    private val settingsRepository: SettingsRepository,
    private val offlineQueueDao: OfflineQueueDao,
    private val sessionHolder: OperatorSessionHolder
) : MqttRepository {
```

Add these imports:

```kotlin
import androidx.annotation.VisibleForTesting
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import java.util.concurrent.ConcurrentHashMap
```

(`CompletableDeferred` and `withTimeoutOrNull` are already covered by the existing `kotlinx.coroutines.*` wildcard import.)

Add these fields next to the existing `_incomingTyped` declaration:

```kotlin
    // Correlation registry: messageId -> the caller awaiting that exact response. The contract is
    // explicit that inResponseToMessageId is the ONLY correct way to match a response to a request
    // — several in-flight messages deliberately share a correlationKey, and two request types can
    // share one response topic (login_requested and reader_logout_requested both answer on
    // operator_context), so neither key nor topic can discriminate.
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

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
```

Add the request implementation:

```kotlin
    override suspend fun <T : Any> request(
        requestType: String,
        responseType: String,
        payload: Any,
        correlationKey: String?,
        responseClass: Class<T>,
    ): MqttOutcome<T> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return MqttOutcome.NoResponse(FailureKind.NotConnected)
        }

        val messageId = UUID.randomUUID().toString()
        val json = RequestEnvelope.build(
            gson = gson,
            payload = payload,
            messageId = messageId,
            deviceId = currentDeviceId,
            operatorSessionId = sessionHolder.currentSessionIdOrEmpty(),
            timestampUtc = Instant.now().toString(),
            correlationKey = correlationKey,
        )
        val topic = MqttTopics.request(currentDeviceId, requestType)

        val waiter = CompletableDeferred<String>()
        pending[messageId] = waiter
        try {
            try {
                publishFn(topic, json.toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "publish failed for $requestType", e)
                return MqttOutcome.NoResponse(FailureKind.NotConnected)
            }
            val raw = withTimeoutOrNull(requestTimeoutMs) { waiter.await() }
                ?: return MqttOutcome.NoResponse(FailureKind.Timeout)
            return parseOutcome(raw, responseClass, responseType)
        } finally {
            pending.remove(messageId)
        }
    }

    private fun <T : Any> parseOutcome(
        raw: String,
        responseClass: Class<T>,
        expectedResponseType: String,
    ): MqttOutcome<T> = try {
        val envelope = gson.fromJson(raw, ResponseEnvelope::class.java)
        val body = gson.fromJson(raw, responseClass)
            ?: throw IllegalStateException("Response body parsed to null for $expectedResponseType")
        val nextAction = NextAction(envelope.nextAction ?: "")
        if (envelope.accepted) {
            MqttOutcome.Accepted(body, nextAction)
        } else {
            MqttOutcome.Rejected(
                body = body,
                errorCode = envelope.errorCode?.let { ErrorCode(it) },
                reason = envelope.reason,
                nextAction = nextAction,
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not parse $expectedResponseType response", e)
        MqttOutcome.NoResponse(FailureKind.MalformedResponse)
    }

    @VisibleForTesting
    internal fun handleIncomingResponse(topic: String, bytes: ByteArray) {
        val raw = String(bytes)
        val envelope = try {
            gson.fromJson(raw, ResponseEnvelope::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unparseable response on $topic", e)
            return
        }
        val id = envelope?.inResponseToMessageId
        if (id.isNullOrBlank()) {
            Log.w(TAG, "Dropping response on $topic with no inResponseToMessageId")
            return
        }
        val waiter = pending.remove(id)
        if (waiter == null) {
            // Late duplicate, or a response to a request that already timed out. The contract says
            // an unknown message gets no workflow side effect.
            Log.w(TAG, "Dropping unmatched response on $topic for messageId=$id")
            return
        }
        waiter.complete(raw)
    }
```

- [ ] **Step 5: Fix the existing test's constructor call**

In `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt`, update `setup()`:

```kotlin
    @Before
    fun setup() {
        mockClientFactory = mock()
        mockSettingsRepository = mock()
        mockQueueDao = mock()
        repo = MqttRepositoryImpl(
            mockClientFactory,
            mockSettingsRepository,
            mockQueueDao,
            OperatorSessionHolder(),
        )
    }
```

Add the import:

```kotlin
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.*"`
Expected: PASS — 10 new correlation tests plus the existing suite.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt \
        app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRequestCorrelationTest.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt
git commit -m "feat(mqtt): correlate responses on inResponseToMessageId"
```

---

### Task 6: Bounded retry with byte-identical payload

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRequestRetryTest.kt`

**Interfaces:**
- Consumes: `MqttRepositoryImpl.request` (Task 5), `publishFn` test seam (Task 5).
- Produces: no new public API. `request()` gains internal retry; `REQUEST_MAX_ATTEMPTS` and `REQUEST_ATTEMPT_TIMEOUT_MS` companion constants.

The rule that must not be broken: **a retry republishes the identical byte array.** Regenerating `timestampUtc` or `messageId` changes the request body, which under replay identity makes it either `message_id_reused` or an unintended duplicate operation. This is the easiest thing in the sub-project to get wrong, so it is tested directly.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRequestRetryTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.local.OfflineQueueDao
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class MqttRequestRetryTest {

    private lateinit var repo: MqttRepositoryImpl
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            offlineQueueDao = mock<OfflineQueueDao>(),
            sessionHolder = OperatorSessionHolder(),
        )
        published.clear()
        repo.publishFn = { topic, bytes -> published += topic to bytes }
        setTimeout(50L)
        forceConnected()
    }

    private fun forceConnected() {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value = MqttConnectionState.CONNECTED
    }

    private fun setTimeout(ms: Long) {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("requestTimeoutMs")
        field.isAccessible = true
        field.setLong(repo, ms)
    }

    @Test
    fun `an unanswered request is retried up to the attempt limit`() = runTest {
        val outcome = repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)

        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), outcome)
        assertEquals(MqttRepositoryImpl.REQUEST_MAX_ATTEMPTS, published.size)
    }

    @Test
    fun `every retry republishes a byte-identical payload`() = runTest {
        repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)

        assertTrue("expected more than one attempt", published.size > 1)
        val first = published.first().second
        published.forEach { (_, bytes) ->
            // Same messageId AND same timestampUtc. Changing either would break replay identity:
            // the contract rejects a reused messageId with different content as message_id_reused.
            assertArrayEquals(first, bytes)
        }
    }

    @Test
    fun `every retry publishes to the same topic`() = runTest {
        repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        assertTrue(published.all { it.first == "PPNAM/handheld_1/req/a_requested" })
    }

    @Test
    fun `a response to the first attempt stops further retries`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        while (published.isEmpty()) yield()

        val id = com.google.gson.JsonParser
            .parseString(String(published[0].second)).asJsonObject.get("messageId").asString
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":true,"value":"ok"}""".toByteArray()
        )

        val outcome = call.await()
        assertTrue(outcome is MqttOutcome.Accepted)
        assertEquals(1, published.size)
    }

    @Test
    fun `a late response to an earlier attempt still satisfies the request`() = runTest {
        val call = async {
            repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)
        }
        // Let the first attempt time out and the second publish.
        while (published.size < 2) yield()

        // Both attempts carry the SAME messageId, so answering "the first attempt" answers the request.
        val id = com.google.gson.JsonParser
            .parseString(String(published[0].second)).asJsonObject.get("messageId").asString
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"$id","accepted":true,"value":"late"}""".toByteArray()
        )

        val outcome = call.await()
        assertTrue(outcome is MqttOutcome.Accepted)
        assertEquals("late", (outcome as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `a publish failure on the first attempt is retried rather than abandoned`() = runTest {
        var attempts = 0
        repo.publishFn = { topic, bytes ->
            attempts++
            if (attempts == 1) throw IllegalStateException("transient publish failure")
            published += topic to bytes
        }

        repo.request("a_requested", "test_result", EmptyPayload, null, TestBody::class.java)

        assertTrue("expected a retry after the transient failure", attempts > 1)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRequestRetryTest"`
Expected: FAIL — `Unresolved reference: REQUEST_MAX_ATTEMPTS`; and `an unanswered request is retried` fails with `published.size == 1`.

- [ ] **Step 3: Add the retry loop**

In `MqttRepositoryImpl`, add to the `companion object`:

```kotlin
        // Bounded retry. The contract's replay design makes this safe: the same replay identity
        // (deviceId + requestType + messageId) with the same body returns the stored response
        // without repeating the workflow action. Keep the total budget well inside Station 2's
        // timestamp acceptance window — a retry must not outlive its own timestampUtc.
        internal const val REQUEST_MAX_ATTEMPTS = 3
```

Replace the body of `request()` between `pending[messageId] = waiter` and the `finally` block:

```kotlin
        val bytes = json.toByteArray()  // frozen: every attempt republishes these exact bytes
        val waiter = CompletableDeferred<String>()
        pending[messageId] = waiter
        try {
            repeat(REQUEST_MAX_ATTEMPTS) { attempt ->
                val publishOk = try {
                    publishFn(topic, bytes)
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
                    if (raw != null) return parseOutcome(raw, responseClass, responseType)
                }
                if (attempt < REQUEST_MAX_ATTEMPTS - 1) {
                    Log.w(TAG, "retrying $requestType (messageId=$messageId, attempt ${attempt + 2})")
                }
            }
            return MqttOutcome.NoResponse(FailureKind.Timeout)
        } finally {
            pending.remove(messageId)
        }
```

Delete the now-superseded `val json = ...` to-byte-array conversion inside the old single publish call so `bytes` is built exactly once.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRequestRetryTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Run the full MQTT suite for regressions**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRequestRetryTest.kt
git commit -m "feat(mqtt): bounded retry republishing byte-identical payloads"
```

---

### Task 7: Subscribe to res/+ and Station 2 presence

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttStationPresenceTest.kt`

**Interfaces:**
- Consumes: `MqttTopics.STATION_STATUS`/`responseWildcard`/`deviceStatus` (Task 1), `handleIncomingResponse` (Task 5).
- Produces: `MqttRepository.stationOnline: StateFlow<Boolean>`; internal `MqttRepositoryImpl.handleStationPresence(bytes: ByteArray)`.

Today "connected" only means "connected to the broker", which can be true while nothing at all is listening. Subscribing to `PPNAM/station_2/status` lets the UI tell *broker unreachable* from *Station 2 down* from *ready*.

`subscribeAndAnnounce` also loses its `stationName` parameter here — nothing in v3 derives a topic from it.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttStationPresenceTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.local.OfflineQueueDao
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class MqttStationPresenceTest {

    private lateinit var repo: MqttRepositoryImpl

    @Before
    fun setup() {
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            offlineQueueDao = mock<OfflineQueueDao>(),
            sessionHolder = OperatorSessionHolder(),
        )
    }

    @Test
    fun `station is assumed offline until it announces itself`() = runTest {
        assertFalse(repo.stationOnline.value)
    }

    @Test
    fun `an online presence payload marks the station up`() = runTest {
        repo.handleStationPresence("online".toByteArray())
        assertTrue(repo.stationOnline.value)
    }

    @Test
    fun `an offline presence payload marks the station down`() = runTest {
        repo.handleStationPresence("online".toByteArray())
        repo.handleStationPresence("offline".toByteArray())
        assertFalse(repo.stationOnline.value)
    }

    @Test
    fun `presence parsing tolerates surrounding whitespace and case`() = runTest {
        repo.handleStationPresence("  ONLINE\n".toByteArray())
        assertTrue(repo.stationOnline.value)
    }

    @Test
    fun `an unrecognised presence payload is treated as offline rather than crashing`() = runTest {
        repo.handleStationPresence("online".toByteArray())
        repo.handleStationPresence("{\"unexpected\":true}".toByteArray())
        assertFalse(repo.stationOnline.value)
    }

    @Test
    fun `an empty presence payload is treated as offline`() = runTest {
        repo.handleStationPresence("online".toByteArray())
        repo.handleStationPresence(ByteArray(0))
        assertFalse(repo.stationOnline.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttStationPresenceTest"`
Expected: FAIL — `Unresolved reference: stationOnline`.

- [ ] **Step 3: Add stationOnline to the interface**

In `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`, add:

```kotlin
    /**
     * Whether Station 2 itself has announced `online` on its retained presence topic.
     *
     * Distinct from [connectionState], which only reports the broker link. The broker can be up
     * while Station 2 is down, in which case every request will time out.
     */
    val stationOnline: StateFlow<Boolean>
```

- [ ] **Step 4: Implement in MqttRepositoryImpl**

Add the field next to `_connectionState`:

```kotlin
    private val _stationOnline = MutableStateFlow(false)
    override val stationOnline: StateFlow<Boolean> = _stationOnline.asStateFlow()
```

Add the handler:

```kotlin
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
```

Replace `subscribeAndAnnounce` entirely (it loses `stationName`, which no v3 topic uses):

```kotlin
    private suspend fun subscribeAndAnnounce(client: Mqtt5AsyncClient, deviceId: String) {
        client.subscribeWith()
            .topicFilter(MqttTopics.responseWildcard(deviceId))
            .callback { publish -> handleIncomingResponse(publish.topic.toString(), publish.payloadAsBytes) }
            .send()
            .await()
        client.subscribeWith()
            .topicFilter(MqttTopics.STATION_STATUS)
            .callback { publish -> handleStationPresence(publish.payloadAsBytes) }
            .send()
            .await()
        client.publishWith()
            .topic(MqttTopics.deviceStatus(deviceId))
            .payload(STATUS_ONLINE)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .send()
            .await()
    }
```

Update the three call sites to drop the `stationName` argument:

In `buildClient`'s `onConnected` callback, replace:

```kotlin
                                subscribeAndAnnounce(client, settings.stationName, settings.deviceId)
```

with:

```kotlin
                                subscribeAndAnnounce(client, settings.deviceId)
```

In `connect()`, replace:

```kotlin
                        subscribeAndAnnounce(client, currentStationName, currentDeviceId)
```

with:

```kotlin
                        subscribeAndAnnounce(client, currentDeviceId)
```

In `reconnectWith()`, replace:

```kotlin
                    subscribeAndAnnounce(candidate, settings.stationName, settings.deviceId)
```

with:

```kotlin
                    subscribeAndAnnounce(candidate, settings.deviceId)
```

Also, a dropped transport means we can no longer vouch for Station 2's presence. In `handleTransportDisconnected`, add `_stationOnline.value = false` inside the `if (mqttClient === client)` block:

```kotlin
    private fun handleTransportDisconnected(client: Mqtt5AsyncClient) {
        if (mqttClient === client) {
            isTransportConnected.set(false)
            _connectionState.value = MqttConnectionState.RECONNECTING
            // The retained presence we last saw is no longer evidence of anything — we are not
            // subscribed any more. Re-subscribing replays the retained value.
            _stationOnline.value = false
        }
    }
```

Delete the now-unused `handleIncomingTyped` method and the `_incomingTyped` field — `sendTyped` is the only remaining caller and Task 15 deletes it. To keep the build green until then, replace `sendTyped`'s body with a delegation to `request()`:

```kotlin
    @Deprecated("Schema 2.0 path. Use request() instead; removed in Task 15.")
    override suspend fun <T> sendTyped(
        requestType: String,
        responseType: String,
        requestJson: String,
        responseClass: Class<T>,
        allowOfflineQueue: Boolean
    ): MqttTypedResult<T> = MqttTypedResult.Error("sendTyped removed; use request()")
```

And replace `publishTyped`'s body with a no-op that logs, for the same reason:

```kotlin
    @Deprecated("Schema 2.0 path. Removed in Task 15.")
    override suspend fun publishTyped(requestType: String, requestJson: String) {
        Log.w(TAG, "publishTyped($requestType) ignored: schema 2.0 fire-and-forget path removed")
    }
```

- [ ] **Step 5: Delete the tests covering the stubbed-out paths**

From `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt`, delete these three tests (their behaviour no longer exists):
- `sendTyped returns Disconnected when offline queue not allowed`
- `publishTyped is a silent no-op when disconnected`
- `sendTyped queues when disconnected and offline queue allowed`

Delete the now-unused imports `com.mitas.ppnam.station2aa.data.local.OfflineQueueEntity` and `com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse` if the compiler flags them.

- [ ] **Step 6: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.*"`
Expected: PASS — 6 new presence tests plus the existing MQTT suite.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt \
        app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttStationPresenceTest.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImplTest.kt
git commit -m "feat(mqtt): subscribe to res/+ and Station 2 presence"
```

---

### Task 8: Clock skew detection

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttClockSkewTest.kt`

**Interfaces:**
- Consumes: `handleIncomingResponse` (Task 5), `ResponseEnvelope.timestampUtc` (Task 3).
- Produces: `MqttRepository.clockSkewMillis: StateFlow<Long?>` (server time minus device time; `null` until a response with a parseable timestamp arrives); `MqttRepositoryImpl.CLOCK_SKEW_WARN_MS` companion constant; internal test seam `MqttRepositoryImpl.nowFn: () -> Instant`.

Every request carries a device-clock `timestampUtc` that must fall inside Station 2's acceptance window. A drifted handheld clock fails **every** message with `message_expired` — a total outage that presents as generic request failures. We detect and report; we never auto-correct, because silently rewriting timestamps would hide a real device fault.

**Note on scope:** this task exposes the signal at the data layer and logs a warning. The operator-facing "device clock out of sync" banner is consumed by sub-project 2's connection-status work, which owns that UI surface.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttClockSkewTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.local.OfflineQueueDao
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.time.Instant

class MqttClockSkewTest {

    private lateinit var repo: MqttRepositoryImpl
    private val deviceNow = Instant.parse("2026-07-16T10:00:00Z")

    @Before
    fun setup() {
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            offlineQueueDao = mock<OfflineQueueDao>(),
            sessionHolder = OperatorSessionHolder(),
        )
        repo.nowFn = { deviceNow }
    }

    private fun receive(serverTimestamp: String) {
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"x","timestampUtc":"$serverTimestamp","accepted":true}""".toByteArray()
        )
    }

    @Test
    fun `skew is unknown until a response arrives`() = runTest {
        assertNull(repo.clockSkewMillis.value)
    }

    @Test
    fun `a synchronised clock reports zero skew`() = runTest {
        receive("2026-07-16T10:00:00Z")
        assertEquals(0L, repo.clockSkewMillis.value)
    }

    @Test
    fun `a device clock running behind the server reports positive skew`() = runTest {
        // Server is 45s ahead of the device.
        receive("2026-07-16T10:00:45Z")
        assertEquals(45_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `a device clock running ahead of the server reports negative skew`() = runTest {
        receive("2026-07-16T09:59:30Z")
        assertEquals(-30_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `skew is measured even when the response matches no pending request`() = runTest {
        // The response is dropped for correlation purposes, but its timestamp is still evidence
        // about our clock — that signal must not depend on winning a correlation race.
        receive("2026-07-16T10:01:00Z")
        assertEquals(60_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `an unparseable timestamp leaves the last known skew untouched`() = runTest {
        receive("2026-07-16T10:00:10Z")
        receive("not-a-timestamp")
        assertEquals(10_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `an absent timestamp leaves the last known skew untouched`() = runTest {
        receive("2026-07-16T10:00:10Z")
        repo.handleIncomingResponse(
            "PPNAM/handheld_1/res/test_result",
            """{"inResponseToMessageId":"x","accepted":true}""".toByteArray()
        )
        assertEquals(10_000L, repo.clockSkewMillis.value)
    }

    @Test
    fun `the warn threshold is a positive duration`() {
        assertTrue(MqttRepositoryImpl.CLOCK_SKEW_WARN_MS > 0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttClockSkewTest"`
Expected: FAIL — `Unresolved reference: nowFn`, `clockSkewMillis`, `CLOCK_SKEW_WARN_MS`.

- [ ] **Step 3: Add clockSkewMillis to the interface**

In `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`, add:

```kotlin
    /**
     * Station 2's clock minus this device's clock, in milliseconds, as of the last response
     * carrying a parseable timestamp. `null` when no such response has arrived yet.
     *
     * Every request must carry a `timestampUtc` inside Station 2's acceptance window, so a badly
     * drifted device clock fails every message with `message_expired`. This surfaces that as a
     * clock problem rather than a generic request failure. Detection only — never auto-correct.
     */
    val clockSkewMillis: StateFlow<Long?>
```

- [ ] **Step 4: Implement in MqttRepositoryImpl**

Add to the `companion object`:

```kotlin
        // Beyond this the device clock is a plausible cause of blanket message_expired rejections.
        internal const val CLOCK_SKEW_WARN_MS = 30_000L
```

Add the fields:

```kotlin
    private val _clockSkewMillis = MutableStateFlow<Long?>(null)
    override val clockSkewMillis: StateFlow<Long?> = _clockSkewMillis.asStateFlow()

    /** Test seam for the device clock. */
    @VisibleForTesting
    internal var nowFn: () -> Instant = { Instant.now() }
```

Replace the direct `Instant.now()` call inside `request()` with the seam:

```kotlin
            timestampUtc = nowFn().toString(),
```

Add the skew recorder:

```kotlin
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
                    "(threshold ${CLOCK_SKEW_WARN_MS}ms). Requests may be rejected as message_expired."
            )
        }
    }
```

Call it from `handleIncomingResponse`, immediately after the envelope parses and **before** the `inResponseToMessageId` check — so an unmatched response still contributes its timestamp:

```kotlin
        recordClockSkew(envelope?.timestampUtc ?: "")
        val id = envelope?.inResponseToMessageId
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttClockSkewTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: Run the full MQTT suite**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.data.mqtt.*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt \
        app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt \
        app/src/test/java/com/ppnam/station2aa/data/mqtt/MqttClockSkewTest.kt
git commit -m "feat(mqtt): detect device clock skew against Station 2"
```

---

### Task 9: Hopper board shared model

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/domain/model/HopperBoard.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/domain/model/HopperStatus.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/model/HopperBoardTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `HopperState` enum (`Available`, `InUse`, `Inactive`); `HopperBoardEntry` data class.

Defined in the foundation because `hoppers[]` is mandatory in seven responses spanning sub-projects 2–5; defining it later would mean each sub-project re-adding and drifting it.

The old `HopperAvailability` values (`AVAILABLE`, `IN_USE`, `OFFLINE`) do not match the contract's (`Available`, `InUse`, `Inactive`) — `OFFLINE` in particular has no v3 counterpart, and the contract's `Inactive` means "configured but disabled", not "unreachable".

Enum constant names deliberately match the wire values exactly so Gson maps them without a custom adapter.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/domain/model/HopperBoardTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HopperBoardTest {

    private val gson = Gson()

    @Test
    fun `parses an abbreviated board entry`() {
        // Several contract responses carry only these four fields.
        val json = """
            {"displayName":"Hopper 1","machineCode":"MXR-01","status":"Available","isAvailable":true}
        """.trimIndent()

        val entry = gson.fromJson(json, HopperBoardEntry::class.java)

        assertEquals("Hopper 1", entry.displayName)
        assertEquals("MXR-01", entry.machineCode)
        assertEquals(HopperState.Available, entry.status)
        assertTrue(entry.isAvailable)
        assertNull(entry.cycleId)
        assertNull(entry.collectionId)
        assertNull(entry.preMixId)
        assertNull(entry.inactiveReason)
    }

    @Test
    fun `parses a full in-use board entry`() {
        val json = """
            {
              "displayName": "Hopper 1",
              "machineCode": "MXR-01",
              "status": "InUse",
              "isAvailable": false,
              "cycleId": "CYC_000601",
              "collectionId": "COL_000123",
              "preMixId": "PMX_000090",
              "jobCardNumber": "510019068",
              "assignedAtUtc": "2026-07-16T10:00:01Z",
              "assignedByOperatorId": "OP-001",
              "assignedByDisplayName": "Operator One",
              "assignedFromDevice": "handheld_1",
              "inactiveReason": null
            }
        """.trimIndent()

        val entry = gson.fromJson(json, HopperBoardEntry::class.java)

        assertEquals(HopperState.InUse, entry.status)
        assertFalse(entry.isAvailable)
        assertEquals("CYC_000601", entry.cycleId)
        assertEquals("COL_000123", entry.collectionId)
        assertEquals("PMX_000090", entry.preMixId)
        assertEquals("510019068", entry.jobCardNumber)
        assertEquals("2026-07-16T10:00:01Z", entry.assignedAtUtc)
        assertEquals("OP-001", entry.assignedByOperatorId)
        assertEquals("Operator One", entry.assignedByDisplayName)
        assertEquals("handheld_1", entry.assignedFromDevice)
    }

    @Test
    fun `parses an inactive board entry with its reason`() {
        val json = """
            {"displayName":"Hopper 3","machineCode":"MXR-03","status":"Inactive",
             "isAvailable":false,"inactiveReason":"Under maintenance"}
        """.trimIndent()

        val entry = gson.fromJson(json, HopperBoardEntry::class.java)

        assertEquals(HopperState.Inactive, entry.status)
        assertFalse(entry.isAvailable)
        assertEquals("Under maintenance", entry.inactiveReason)
    }

    @Test
    fun `every contract hopper status maps from its wire value`() {
        assertEquals(HopperState.Available, gson.fromJson("\"Available\"", HopperState::class.java))
        assertEquals(HopperState.InUse, gson.fromJson("\"InUse\"", HopperState::class.java))
        assertEquals(HopperState.Inactive, gson.fromJson("\"Inactive\"", HopperState::class.java))
    }

    @Test
    fun `a board list parses`() {
        val json = """
            [{"displayName":"Hopper 1","machineCode":"MXR-01","status":"Available","isAvailable":true},
             {"displayName":"Hopper 2","machineCode":"MXR-02","status":"InUse","isAvailable":false}]
        """.trimIndent()

        val board = gson.fromJson(json, Array<HopperBoardEntry>::class.java).toList()

        assertEquals(2, board.size)
        assertEquals(HopperState.Available, board[0].status)
        assertEquals(HopperState.InUse, board[1].status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.model.HopperBoardTest"`
Expected: FAIL — `Unresolved reference: HopperBoardEntry`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ppnam/station2aa/domain/model/HopperBoard.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

/**
 * Contract v3.0 Hopper status. Enum constant names match the wire values exactly so Gson maps them
 * without a custom adapter — do not rename them to Kotlin casing conventions.
 */
enum class HopperState {
    /** Configured, active, and free for assignment. */
    Available,

    /** Has an active Hopper cycle. */
    InUse,

    /** Configured but disabled or unavailable for operational use. */
    Inactive,
}

/**
 * One Hopper on the contract's common status board.
 *
 * The board is mandatory in seven responses — job-card load, active job cards, every ingredient
 * scan, hopper overview, and Hopper cycle start/finish/force-close — so the operator can see live
 * availability at every decision point without a separate lookup. It always lists every configured
 * Hopper, including inactive equipment.
 *
 * The assignment fields are nullable because the contract's own examples show abbreviated boards in
 * some responses and full boards in others.
 */
data class HopperBoardEntry(
    val displayName: String = "",
    val machineCode: String = "",
    val status: HopperState = HopperState.Inactive,
    val isAvailable: Boolean = false,
    val cycleId: String? = null,
    val collectionId: String? = null,
    val preMixId: String? = null,
    val jobCardNumber: String? = null,
    val assignedAtUtc: String? = null,
    val assignedByOperatorId: String? = null,
    val assignedByDisplayName: String? = null,
    val assignedFromDevice: String? = null,
    val inactiveReason: String? = null,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.model.HopperBoardTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Delete the superseded model**

```bash
git rm app/src/main/java/com/ppnam/station2aa/domain/model/HopperStatus.kt
```

Task 1 already removed its last production reference. If the compiler reports any remaining import of `HopperStatus` or `HopperAvailability`, delete that import.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/model/HopperBoard.kt \
        app/src/test/java/com/ppnam/station2aa/domain/model/HopperBoardTest.kt
git commit -m "feat(mqtt): add v3 Hopper board model, replacing HopperStatus"
```

---

### Task 10: Pallet lookup and holding recovery

**Files:**
- Create: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/PalletMessages.kt`
- Create: `app/src/main/java/com/ppnam/station2aa/domain/model/PalletInfo.kt`
- Create: `app/src/main/java/com/ppnam/station2aa/domain/usecase/PalletUseCase.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/domain/usecase/RfidUseCase.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/domain/model/Pallet.kt`
- Delete: `app/src/test/java/com/ppnam/station2aa/domain/usecase/RfidUseCaseTest.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/PalletUseCaseTest.kt`

**Interfaces:**
- Consumes: `MqttRepository.request` (Task 5), `MqttOutcome`/`FailureKind` (Task 3), `NextAction` (Task 2).
- Produces: `PalletState` enum; `PalletInfo` data class; `PalletUseCase.lookup(palletRfidTag: String): Result<PalletInfo>`; `PalletUseCase.recoverToHolding(palletRfidTag: String, collectionId: String?, auditReason: String): Result<PalletInfo>`; DTOs `PalletLookupPayload`, `HoldingRecoveryPayload`, `PalletLookupResultResponse`.

This is the vertical slice that proves the transport. It is the contract's simplest request/response pair — no BOM, no Hopper board, no cycle state — and it makes the `RFID_RECOVERY` screen work for the first time.

**The load-bearing rule: `usable` and `recoverable` are computed by Station 2 and MUST NOT be re-derived by the client.** `usable` depends on `palletState` AND `blocked` AND `remainingQuantity`; `recoverable` depends on `palletState` alone. They are independent — a blocked `AtStation1` pallet is `recoverable: true`, and recovering it does not unblock it, so it can come back `usable: false` after a *successful* recovery. Show Station 2's answer; never compute one.

**Vocabulary rule used throughout this plan:** closed contract vocabularies (`palletState`, `HopperState`) are Kotlin enums whose constant names match the wire values exactly. Open vocabularies — the ones the contract says may carry values beyond those listed (`errorCode`, `nextAction`) — are value classes so unknown values pass through. `PalletState` is closed: the contract's table is exhaustive and already includes an explicit `Unknown` member.

Note `holding_recovery_requested` carries `auditReason` but **no manager credentials** — it is not in the contract's privileged-action list.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/domain/usecase/PalletUseCaseTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupResultResponse
import com.mitas.ppnam.station2aa.domain.model.PalletState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PalletUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var useCase: PalletUseCase

    private val holdingPallet = PalletLookupResultResponse(
        found = true,
        usable = true,
        recoverable = false,
        palletRfidTag = "300833120000000000001A2B",
        palletId = "PAL-001",
        productCode = "1600000301",
        productName = "HD WHITE",
        batchNumber = "BATCH-01",
        remainingQuantity = 625.0,
        remainingBags = 25.0,
        unit = "kg",
        localLocation = "Holding",
        palletState = "Holding",
        blocked = false,
    )

    @Before
    fun setup() {
        mqtt = mock()
        useCase = PalletUseCase(mqtt)
    }

    private suspend fun stubLookup(outcome: MqttOutcome<PalletLookupResultResponse>) {
        whenever(
            mqtt.request(
                eq("pallet_lookup_requested"), eq("pallet_lookup_result"),
                any(), any(), eq(PalletLookupResultResponse::class.java)
            )
        ).thenReturn(outcome)
    }

    private suspend fun stubRecovery(outcome: MqttOutcome<PalletLookupResultResponse>) {
        whenever(
            mqtt.request(
                eq("holding_recovery_requested"), eq("holding_recovery_result"),
                any(), any(), eq(PalletLookupResultResponse::class.java)
            )
        ).thenReturn(outcome)
    }

    @Test
    fun `lookup maps an accepted response into PalletInfo`() = runTest {
        stubLookup(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        val info = useCase.lookup("300833120000000000001A2B").getOrThrow()

        assertTrue(info.found)
        assertTrue(info.usable)
        assertFalse(info.recoverable)
        assertEquals(PalletState.Holding, info.palletState)
        assertEquals("PAL-001", info.palletId)
        assertEquals("HD WHITE", info.productName)
        assertEquals(625.0, info.remainingQuantity, 0.001)
        assertEquals("kg", info.unit)
    }

    @Test
    fun `lookup sends the scanned tag as both payload and correlation key`() = runTest {
        stubLookup(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        useCase.lookup("300833120000000000001A2B")

        verify(mqtt).request(
            eq("pallet_lookup_requested"),
            eq("pallet_lookup_result"),
            argThat<Any> { this is PalletLookupPayload && palletRfidTag == "300833120000000000001A2B" },
            eq("300833120000000000001A2B"),
            eq(PalletLookupResultResponse::class.java),
        )
    }

    @Test
    fun `an unknown tag is a successful lookup that simply found nothing`() = runTest {
        // The contract is explicit: accepted means Station 2 answered, not that the answer was
        // favourable. found=false must NOT surface as an error.
        val notFound = PalletLookupResultResponse(found = false, usable = false, recoverable = false)
        stubLookup(MqttOutcome.Accepted(notFound, NextAction.NONE))

        val info = useCase.lookup("UNKNOWN-TAG").getOrThrow()

        assertFalse(info.found)
        assertFalse(info.usable)
        assertFalse(info.recoverable)
    }

    @Test
    fun `a recoverable pallet is reported as recoverable`() = runTest {
        val atStation1 = holdingPallet.copy(
            usable = false, recoverable = true, palletState = "AtStation1", localLocation = "Station 1"
        )
        stubLookup(MqttOutcome.Accepted(atStation1, NextAction.RECOVER_HOLDING))

        val info = useCase.lookup("300833120000000000001A2B").getOrThrow()

        assertFalse(info.usable)
        assertTrue(info.recoverable)
        assertEquals(PalletState.AtStation1, info.palletState)
    }

    @Test
    fun `a blocked pallet in a recoverable state is still recoverable`() = runTest {
        // recoverable is decided by palletState ALONE — blocked is an independent overlay.
        val blockedAtStation1 = holdingPallet.copy(
            usable = false, recoverable = true, palletState = "AtStation1", blocked = true
        )
        stubLookup(MqttOutcome.Accepted(blockedAtStation1, NextAction.RECOVER_HOLDING))

        val info = useCase.lookup("300833120000000000001A2B").getOrThrow()

        assertTrue(info.recoverable)
        assertTrue(info.blocked)
        assertFalse(info.usable)
    }

    @Test
    fun `usable and recoverable are read from the response, never recomputed`() = runTest {
        // Deliberately self-contradictory: state says Holding and unblocked with stock, which a
        // client re-deriving the rule would call usable. Station 2 says otherwise, and Station 2 wins.
        val contradictory = holdingPallet.copy(usable = false, recoverable = true)
        stubLookup(MqttOutcome.Accepted(contradictory, NextAction.NONE))

        val info = useCase.lookup("300833120000000000001A2B").getOrThrow()

        assertFalse("usable must come from the response", info.usable)
        assertTrue("recoverable must come from the response", info.recoverable)
    }

    @Test
    fun `every contract pallet state maps from its wire value`() = runTest {
        val cases = mapOf(
            "Holding" to PalletState.Holding,
            "Mixing" to PalletState.Mixing,
            "AtStation1" to PalletState.AtStation1,
            "Unknown" to PalletState.Unknown,
            "Consumed" to PalletState.Consumed,
        )
        for ((wire, expected) in cases) {
            stubLookup(MqttOutcome.Accepted(holdingPallet.copy(palletState = wire), NextAction.NONE))
            assertEquals(expected, useCase.lookup("tag").getOrThrow().palletState)
        }
    }

    @Test
    fun `an unrecognised pallet state degrades to Unknown rather than crashing`() = runTest {
        stubLookup(MqttOutcome.Accepted(holdingPallet.copy(palletState = "SomeNewState"), NextAction.NONE))
        assertEquals(PalletState.Unknown, useCase.lookup("tag").getOrThrow().palletState)
    }

    @Test
    fun `a rejected lookup fails with the operator-readable reason`() = runTest {
        stubLookup(
            MqttOutcome.Rejected(
                body = PalletLookupResultResponse(),
                errorCode = com.mitas.ppnam.station2aa.data.mqtt.ErrorCode.SESSION_REQUIRED,
                reason = "No valid session on this device.",
                nextAction = NextAction.LOGIN,
            )
        )

        val result = useCase.lookup("tag")

        assertTrue(result.isFailure)
        assertEquals("No valid session on this device.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a timeout fails with a connection message rather than a silent success`() = runTest {
        stubLookup(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.lookup("tag")

        assertTrue(result.isFailure)
        assertEquals("Station 2 did not respond", result.exceptionOrNull()?.message)
    }

    @Test
    fun `being disconnected fails with a connection message`() = runTest {
        stubLookup(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.lookup("tag")

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    @Test
    fun `recovery sends the tag and audit reason`() = runTest {
        stubRecovery(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        useCase.recoverToHolding(
            palletRfidTag = "300833120000000000001A2B",
            collectionId = "COL_000123",
            auditReason = "Pallet is physically at Station 2; fixed door read was missed.",
        )

        verify(mqtt).request(
            eq("holding_recovery_requested"),
            eq("holding_recovery_result"),
            argThat<Any> {
                this is HoldingRecoveryPayload &&
                    palletRfidTag == "300833120000000000001A2B" &&
                    collectionId == "COL_000123" &&
                    auditReason == "Pallet is physically at Station 2; fixed door read was missed."
            },
            eq("COL_000123"),
            eq(PalletLookupResultResponse::class.java),
        )
    }

    @Test
    fun `recovery without a collection omits collectionId and correlates on the tag`() = runTest {
        // collectionId is optional; the contract forbids sending null or "" as a stand-in for absence.
        stubRecovery(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        useCase.recoverToHolding(palletRfidTag = "TAG-1", collectionId = null, auditReason = "Missed door read")

        verify(mqtt).request(
            eq("holding_recovery_requested"),
            eq("holding_recovery_result"),
            argThat<Any> { this is HoldingRecoveryPayload && collectionId == null },
            eq("TAG-1"),
            eq(PalletLookupResultResponse::class.java),
        )
    }

    @Test
    fun `a successful recovery returns the refreshed pallet`() = runTest {
        stubRecovery(MqttOutcome.Accepted(holdingPallet, NextAction.NONE))

        val info = useCase.recoverToHolding("TAG-1", null, "Missed door read").getOrThrow()

        assertEquals(PalletState.Holding, info.palletState)
        assertTrue(info.usable)
    }

    @Test
    fun `a successful recovery can still return an unusable pallet`() = runTest {
        // Recovery registers physical arrival; it does not clear a quality block. The scanner must
        // show that honest result rather than assuming success means ready-to-scan.
        val recoveredButBlocked = holdingPallet.copy(palletState = "Holding", blocked = true, usable = false)
        stubRecovery(MqttOutcome.Accepted(recoveredButBlocked, NextAction.NONE))

        val info = useCase.recoverToHolding("TAG-1", null, "Missed door read").getOrThrow()

        assertEquals(PalletState.Holding, info.palletState)
        assertTrue(info.blocked)
        assertFalse(info.usable)
    }

    @Test
    fun `a rejected recovery fails with its reason`() = runTest {
        stubRecovery(
            MqttOutcome.Rejected(
                body = PalletLookupResultResponse(),
                errorCode = com.mitas.ppnam.station2aa.data.mqtt.ErrorCode.STATE_CONFLICT,
                reason = "Consumed pallets cannot be recovered.",
                nextAction = NextAction.NONE,
            )
        )

        val result = useCase.recoverToHolding("TAG-1", null, "Missed door read")

        assertTrue(result.isFailure)
        assertEquals("Consumed pallets cannot be recovered.", result.exceptionOrNull()?.message)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.PalletUseCaseTest"`
Expected: FAIL — `Unresolved reference: PalletUseCase`.

- [ ] **Step 3: Write the DTOs**

Create `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/PalletMessages.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * Message-specific fields only. The transport injects the envelope — see RequestEnvelope.
 */
data class PalletLookupPayload(
    val palletRfidTag: String,
)

/**
 * `collectionId` is optional: when supplied, the pallet product must be valid for that collection's
 * manual BOM. It is null (and therefore omitted) when recovering outside a collection.
 *
 * Holding recovery carries an auditReason but NO manager credentials — it is not one of the
 * contract's privileged actions.
 */
data class HoldingRecoveryPayload(
    val palletRfidTag: String,
    val collectionId: String? = null,
    val auditReason: String,
)

/**
 * Shared by `pallet_lookup_result` and `holding_recovery_result` — an accepted recovery returns the
 * updated pallet fields from the lookup shape.
 *
 * `usable` and `recoverable` are Station 2's authoritative answers. Do not re-derive them.
 */
data class PalletLookupResultResponse(
    val found: Boolean = false,
    val usable: Boolean = false,
    val recoverable: Boolean = false,
    val palletRfidTag: String? = null,
    val palletId: String? = null,
    val productCode: String? = null,
    val productName: String? = null,
    val batchNumber: String? = null,
    val remainingQuantity: Double = 0.0,
    val remainingBags: Double = 0.0,
    val unit: String? = null,
    val localLocation: String? = null,
    val palletState: String = "",
    val blocked: Boolean = false,
)
```

- [ ] **Step 4: Write the domain model**

Create `app/src/main/java/com/ppnam/station2aa/domain/model/PalletInfo.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.model

/**
 * Contract v3.0 `palletState` — the axis every pallet decision keys off. A closed vocabulary: the
 * contract's table is exhaustive and already carries an explicit Unknown member.
 *
 * Note `blocked` is NOT a state — it is a separate overlay, so a pallet can be Holding *and* blocked.
 */
enum class PalletState {
    /** Station 2 has it, available for collection. */
    Holding,

    /** In use by an active mix. */
    Mixing,

    /** Station 2 has no arrival record — the door read was missed, or it genuinely is still upstream. */
    AtStation1,

    /** Known pallet, indeterminate state. */
    Unknown,

    /** Fully depleted. */
    Consumed;

    companion object {
        /** Degrades an unrecognised value to [Unknown] rather than failing the whole lookup. */
        fun fromWire(raw: String): PalletState =
            entries.firstOrNull { it.name == raw } ?: Unknown
    }
}

/**
 * A pallet as Station 2 sees it.
 *
 * [usable] and [recoverable] are computed by Station 2 and must be displayed, never recomputed:
 *  - usable     is decided by palletState AND blocked AND remainingQuantity
 *  - recoverable is decided by palletState alone
 *
 * They are independent. A blocked AtStation1 pallet is recoverable, and recovering it does not
 * unblock it — so a successful recovery can still leave usable = false.
 */
data class PalletInfo(
    val found: Boolean,
    val usable: Boolean,
    val recoverable: Boolean,
    val palletRfidTag: String,
    val palletId: String,
    val productCode: String,
    val productName: String,
    val batchNumber: String,
    val remainingQuantity: Double,
    val remainingBags: Double,
    val unit: String,
    val localLocation: String,
    val palletState: PalletState,
    val blocked: Boolean,
)
```

- [ ] **Step 5: Write the use case**

Create `app/src/main/java/com/ppnam/station2aa/domain/usecase/PalletUseCase.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.HoldingRecoveryPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.PalletLookupResultResponse
import com.mitas.ppnam.station2aa.domain.model.PalletInfo
import com.mitas.ppnam.station2aa.domain.model.PalletState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PalletUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
) {

    suspend fun lookup(palletRfidTag: String): Result<PalletInfo> =
        mqttRepository.request(
            requestType = "pallet_lookup_requested",
            responseType = "pallet_lookup_result",
            payload = PalletLookupPayload(palletRfidTag = palletRfidTag),
            correlationKey = palletRfidTag,
            responseClass = PalletLookupResultResponse::class.java,
        ).toResult()

    /**
     * Registers that a pallet physically arrived at Station 2 after a missed door read.
     *
     * Local-only: writes movement, exception and audit records, and never posts to SAP. The returned
     * pallet may still be unusable — recovery does not clear a block.
     */
    suspend fun recoverToHolding(
        palletRfidTag: String,
        collectionId: String?,
        auditReason: String,
    ): Result<PalletInfo> =
        mqttRepository.request(
            requestType = "holding_recovery_requested",
            responseType = "holding_recovery_result",
            payload = HoldingRecoveryPayload(
                palletRfidTag = palletRfidTag,
                collectionId = collectionId,
                auditReason = auditReason,
            ),
            correlationKey = collectionId ?: palletRfidTag,
            responseClass = PalletLookupResultResponse::class.java,
        ).toResult()

    private fun MqttOutcome<PalletLookupResultResponse>.toResult(): Result<PalletInfo> = when (this) {
        // accepted means Station 2 answered — not that the answer was favourable. A lookup that
        // correctly found nothing is a success carrying found = false.
        is MqttOutcome.Accepted -> Result.success(body.toPalletInfo())
        is MqttOutcome.Rejected -> Result.failure(Exception(reason ?: "Station 2 rejected the request"))
        is MqttOutcome.NoResponse -> Result.failure(
            Exception(
                when (kind) {
                    FailureKind.NotConnected -> "Not connected to Station 2"
                    FailureKind.Timeout -> "Station 2 did not respond"
                    FailureKind.MalformedResponse -> "Station 2 sent an unreadable response"
                }
            )
        )
    }

    private fun PalletLookupResultResponse.toPalletInfo() = PalletInfo(
        found = found,
        // Straight through from the response. Re-deriving these is a contract violation.
        usable = usable,
        recoverable = recoverable,
        palletRfidTag = palletRfidTag.orEmpty(),
        palletId = palletId.orEmpty(),
        productCode = productCode.orEmpty(),
        productName = productName.orEmpty(),
        batchNumber = batchNumber.orEmpty(),
        remainingQuantity = remainingQuantity,
        remainingBags = remainingBags,
        unit = unit.orEmpty(),
        localLocation = localLocation.orEmpty(),
        palletState = PalletState.fromWire(palletState),
        blocked = blocked,
    )
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.PalletUseCaseTest"`
Expected: PASS (16 tests).

- [ ] **Step 7: Delete the superseded legacy use case and model**

Task 11 rewires `RfidViewModel`; run this step and Task 11 Step 3 together if the compiler complains in between.

```bash
git rm app/src/main/java/com/ppnam/station2aa/domain/usecase/RfidUseCase.kt \
       app/src/main/java/com/ppnam/station2aa/domain/model/Pallet.kt \
       app/src/test/java/com/ppnam/station2aa/domain/usecase/RfidUseCaseTest.kt
```

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/PalletMessages.kt \
        app/src/main/java/com/ppnam/station2aa/domain/model/PalletInfo.kt \
        app/src/main/java/com/ppnam/station2aa/domain/usecase/PalletUseCase.kt \
        app/src/test/java/com/ppnam/station2aa/domain/usecase/PalletUseCaseTest.kt
git commit -m "feat(mqtt): implement v3 pallet lookup and holding recovery"
```

---

### Task 11: Wire the RFID screen to pallet lookup and recovery

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidViewModel.kt` (full rewrite)
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidRecoveryScreen.kt`
- Test: `app/src/test/java/com/ppnam/station2aa/ui/rfid/RfidViewModelTest.kt`

**Interfaces:**
- Consumes: `PalletUseCase.lookup` / `recoverToHolding` (Task 10), `PalletInfo` / `PalletState` (Task 10).
- Produces: `RfidUiState` sealed class with `Idle`, `Loading`, `Result(pallet: PalletInfo)`, `Recovering`, `Error(message: String)`; `RfidViewModel.recoverCurrentPallet()`.

`RfidUiState.PalletFound` becomes `RfidUiState.Result`, because a lookup that ran correctly and found nothing is also a result, not an error — the old naming baked in the wrong assumption.

The screen offers recovery when the response says `recoverable`, per the contract's flow: look up → not usable → offer to recover → recover → confirm on screen.

`offlineQueueRepository` / `pendingCount` stay for now; Task 16 removes them everywhere at once.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ppnam/station2aa/ui/rfid/RfidViewModelTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.rfid

import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.model.PalletInfo
import com.mitas.ppnam.station2aa.domain.model.PalletState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.PalletUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class RfidViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var useCase: PalletUseCase
    private lateinit var viewModel: RfidViewModel

    private fun pallet(
        found: Boolean = true,
        usable: Boolean = true,
        recoverable: Boolean = false,
        state: PalletState = PalletState.Holding,
        blocked: Boolean = false,
    ) = PalletInfo(
        found = found, usable = usable, recoverable = recoverable,
        palletRfidTag = "TAG-1", palletId = "PAL-001", productCode = "1600000301",
        productName = "HD WHITE", batchNumber = "BATCH-01", remainingQuantity = 625.0,
        remainingBags = 25.0, unit = "kg", localLocation = "Holding",
        palletState = state, blocked = blocked,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mock()
        val mqtt: MqttRepository = mock()
        whenever(mqtt.connectionState).thenReturn(
            kotlinx.coroutines.flow.MutableStateFlow(
                com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState.CONNECTED
            )
        )
        val queue: OfflineQueueRepository = mock()
        whenever(queue.pendingCount()).thenReturn(flowOf(0))
        val bus: ScanEventBus = mock()
        whenever(bus.events).thenReturn(MutableSharedFlow())
        viewModel = RfidViewModel(useCase, bus, mqtt, queue)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a usable pallet surfaces as a result`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(Result.success(pallet()))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is RfidUiState.Result)
        assertTrue((state as RfidUiState.Result).pallet.usable)
    }

    @Test
    fun `an unknown tag is a result rather than an error`() = runTest {
        whenever(useCase.lookup("NOPE")).thenReturn(
            Result.success(pallet(found = false, usable = false, state = PalletState.Unknown))
        )

        viewModel.lookupPallet("NOPE")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("found=false must not be an Error", state is RfidUiState.Result)
        assertEquals(false, (state as RfidUiState.Result).pallet.found)
    }

    @Test
    fun `a transport failure surfaces as an error`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(Result.failure(Exception("Not connected to Station 2")))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is RfidUiState.Error)
        assertEquals("Not connected to Station 2", (state as RfidUiState.Error).message)
    }

    @Test
    fun `recovering a pallet sends its tag and replaces the shown result`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(
            Result.success(pallet(usable = false, recoverable = true, state = PalletState.AtStation1))
        )
        whenever(useCase.recoverToHolding(eq("TAG-1"), eq(null), any()))
            .thenReturn(Result.success(pallet()))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        verify(useCase).recoverToHolding(eq("TAG-1"), eq(null), any())
        val state = viewModel.uiState.value as RfidUiState.Result
        assertEquals(PalletState.Holding, state.pallet.palletState)
        assertTrue(state.pallet.usable)
    }

    @Test
    fun `a recovery that leaves the pallet blocked shows the honest result`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(
            Result.success(pallet(usable = false, recoverable = true, state = PalletState.AtStation1, blocked = true))
        )
        whenever(useCase.recoverToHolding(eq("TAG-1"), eq(null), any())).thenReturn(
            Result.success(pallet(usable = false, blocked = true, state = PalletState.Holding))
        )

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        val state = viewModel.uiState.value as RfidUiState.Result
        assertEquals(PalletState.Holding, state.pallet.palletState)
        assertEquals(false, state.pallet.usable)
        assertTrue(state.pallet.blocked)
    }

    @Test
    fun `a failed recovery surfaces as an error`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(
            Result.success(pallet(usable = false, recoverable = true, state = PalletState.AtStation1))
        )
        whenever(useCase.recoverToHolding(eq("TAG-1"), eq(null), any()))
            .thenReturn(Result.failure(Exception("Consumed pallets cannot be recovered.")))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is RfidUiState.Error)
        assertEquals("Consumed pallets cannot be recovered.", (state as RfidUiState.Error).message)
    }

    @Test
    fun `recovery is a no-op when no pallet is on screen`() = runTest {
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        verify(useCase, never()).recoverToHolding(any(), any(), any())
    }

    @Test
    fun `recovery is a no-op when the shown pallet is not recoverable`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(Result.success(pallet(recoverable = false)))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        verify(useCase, never()).recoverToHolding(any(), any(), any())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.rfid.RfidViewModelTest"`
Expected: FAIL — `Unresolved reference: RfidUiState.Result`, `recoverCurrentPallet`.

- [ ] **Step 3: Rewrite the ViewModel**

Replace the entire contents of `app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidViewModel.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.rfid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.local.OfflineQueueRepository
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.model.PalletInfo
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.PalletUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RfidUiState {
    object Idle : RfidUiState()
    object Loading : RfidUiState()
    object Recovering : RfidUiState()

    /**
     * A lookup that Station 2 answered. Note this covers found = false: a lookup that ran correctly
     * and found nothing is a result, not an error.
     */
    data class Result(val pallet: PalletInfo) : RfidUiState()

    /** Station 2 rejected the request, or we never heard back. */
    data class Error(val message: String) : RfidUiState()
}

@HiltViewModel
class RfidViewModel @Inject constructor(
    private val useCase: PalletUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository,
    private val offlineQueueRepository: OfflineQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RfidUiState>(RfidUiState.Idle)
    val uiState: StateFlow<RfidUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val pendingCount: StateFlow<Int> = offlineQueueRepository.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var scanJob: Job? = null

    fun startListening() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                lookupPallet(event.tagId)
            }
        }
    }

    fun lookupPallet(tagId: String) {
        viewModelScope.launch {
            _uiState.value = RfidUiState.Loading
            useCase.lookup(tagId)
                .onSuccess { pallet -> _uiState.value = RfidUiState.Result(pallet) }
                .onFailure { e -> _uiState.value = RfidUiState.Error(e.message ?: "Unknown error") }
        }
    }

    /**
     * Recovers the pallet currently on screen into Holding after a missed door read.
     *
     * Gated on the response's own `recoverable` flag — Station 2 decides recoverability, and the
     * client must not second-guess it.
     */
    fun recoverCurrentPallet() {
        val shown = (_uiState.value as? RfidUiState.Result)?.pallet ?: return
        if (!shown.recoverable) return
        viewModelScope.launch {
            _uiState.value = RfidUiState.Recovering
            useCase.recoverToHolding(
                palletRfidTag = shown.palletRfidTag,
                collectionId = null,
                auditReason = RECOVERY_REASON,
            )
                // The refreshed pallet may still be unusable — recovery registers arrival, it does
                // not clear a block. Show whatever Station 2 says rather than assuming success.
                .onSuccess { pallet -> _uiState.value = RfidUiState.Result(pallet) }
                .onFailure { e -> _uiState.value = RfidUiState.Error(e.message ?: "Recovery failed") }
        }
    }

    fun resetToIdle() {
        _uiState.value = RfidUiState.Idle
        startListening()
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }

    private companion object {
        const val RECOVERY_REASON = "Pallet is physically at Station 2; fixed door read was missed."
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.ui.rfid.RfidViewModelTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Update the screen**

In `app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidRecoveryScreen.kt`, change the title on line 32 from `"RFID Recovery"` to `"RFID Pallet Lookup"` (it now does lookup first, recovery only when offered).

Replace the `is RfidUiState.Loading -> { ... }` branch (lines 48–54) with one that also covers `Recovering`:

```kotlin
                    is RfidUiState.Loading, is RfidUiState.Recovering -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AmberPrimary)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = if (state is RfidUiState.Recovering) "Recovering pallet…" else "Looking up pallet…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                        }
                    }
```

Replace the whole `is RfidUiState.PalletFound -> { ... }` branch (lines 55–82) with:

```kotlin
                    is RfidUiState.Result -> {
                        val pallet = state.pallet
                        val accent = when {
                            !pallet.found -> DangerRed
                            pallet.usable -> SuccessGreen
                            else -> AmberPrimary
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .width(4.dp)
                                        .background(accent)
                                )
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (pallet.usable) Icons.Filled.CheckCircle else Icons.Filled.Info,
                                            null,
                                            tint = accent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = when {
                                                !pallet.found -> "Pallet Not Found"
                                                pallet.usable -> "Pallet Ready"
                                                else -> "Pallet Not Usable"
                                            },
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = accent
                                        )
                                    }
                                    if (pallet.found) {
                                        Spacer(Modifier.height(12.dp))
                                        LabelValueRow("Tag ID", pallet.palletRfidTag)
                                        LabelValueRow("Pallet ID", pallet.palletId)
                                        LabelValueRow("Product", pallet.productName)
                                        LabelValueRow("Batch No", pallet.batchNumber)
                                        LabelValueRow("Remaining", "${pallet.remainingQuantity} ${pallet.unit}")
                                        LabelValueRow("Location", pallet.localLocation)
                                        LabelValueRow("State", pallet.palletState.name)
                                        if (pallet.blocked) {
                                            LabelValueRow("Blocked", "Yes")
                                        }
                                    } else {
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            "This tag is not a known pallet. Resolve it at Station 1 first.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
```

Add the icon import at the top:

```kotlin
import androidx.compose.material.icons.filled.Info
```

Replace the bottom button block (lines 101–117) so a recoverable pallet offers recovery:

```kotlin
            when (val state = uiState) {
                is RfidUiState.Result -> {
                    // Station 2 decides recoverability. Offer the action only when it says so, and
                    // note a successful recovery still won't clear a block — the result will say.
                    if (state.pallet.recoverable) {
                        Button(
                            onClick = { viewModel.recoverCurrentPallet() },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) { Text("Recover to Holding") }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.resetToIdle() },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Scan Another") }
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Done") }
                    }
                }
                is RfidUiState.Error -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.resetToIdle() },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Try Again") }
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) { Text("Done") }
                    }
                }
                else -> Unit
            }
```

- [ ] **Step 6: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Build the app to confirm Compose compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidViewModel.kt \
        app/src/main/java/com/ppnam/station2aa/ui/rfid/RfidRecoveryScreen.kt \
        app/src/test/java/com/ppnam/station2aa/ui/rfid/RfidViewModelTest.kt
git commit -m "feat(rfid): wire pallet lookup and holding recovery to the RFID screen

First time this screen has worked: it previously rode the legacy
{station}/request path, which the backend never subscribed to."
```

---

### Task 12: Port AuthUseCase to v3

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt` (full rewrite)
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/AuthUseCase.kt` (full rewrite)
- Test: `app/src/test/java/com/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt` (full rewrite)

**Interfaces:**
- Consumes: `MqttRepository.request` (Task 5), `MqttOutcome`/`FailureKind` (Task 3), `EmptyPayload` (Task 4).
- Produces: `CredentialsLoginPayload(username, password)`; `BadgeLoginPayload(badgeTag)`; `OperatorContextResponse`; `AuthUseCase.login(method: LoginMethod): Result<OperatorSession>`; `AuthUseCase.logout(): Result<Unit>`. `LoginMethod.Credentials` / `LoginMethod.Badge` are unchanged.

Two v3 changes are pure renames and land here: `reader_login_requested` and `login_tag_scanned` **collapse into the single `login_requested`**, distinguished only by which payload fields are present ("exactly one authentication method is supplied").

**Deferred to sub-project 2:** `sessionState`, `sessionExpiresAtUtc`, presence-driven suspend/resume, and removing role-based gating. This task is a mechanical port — do not add session-lifecycle semantics here. The response DTO deliberately omits fields we do not yet use; Gson ignores unmapped JSON fields.

**Envelope/body overlap:** response DTOs carry message-specific fields only, with **one documented exception** — `OperatorContextResponse` also reads `operatorSessionId`, an envelope field, because login is where the session is issued and the use case has no other way to obtain it. Gson parses both from the same flat JSON object, so this costs nothing.

`AuthUseCase` no longer needs `SettingsRepository` (the transport owns `deviceId`) or `Gson` (the transport owns serialization).

- [ ] **Step 1: Write the failing test**

Replace the entire contents of `app/src/test/java/com/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.mqtt.dto.BadgeLoginPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.CredentialsLoginPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuthUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var useCase: AuthUseCase

    private val accepted = OperatorContextResponse(
        operatorSessionId = "session-id",
        operatorId = "OP-001",
        username = "operator1",
        displayName = "Operator One",
        role = "Operator",
        allowedActions = listOf("scan_ingredient", "start_machine_cycle"),
        allowedTabs = listOf("collect", "premix"),
    )

    @Before
    fun setup() {
        mqtt = mock()
        sessionHolder = OperatorSessionHolder()
        useCase = AuthUseCase(mqtt, sessionHolder)
    }

    private suspend fun stub(outcome: MqttOutcome<OperatorContextResponse>) {
        whenever(
            mqtt.request(any(), any(), any(), any(), eq(OperatorContextResponse::class.java))
        ).thenReturn(outcome)
    }

    @Test
    fun `credentials login uses the single v3 login topic`() = runTest {
        stub(MqttOutcome.Accepted(accepted, NextAction.NONE))

        useCase.login(LoginMethod.Credentials("operator1", "secret"))

        verify(mqtt).request(
            eq("login_requested"),
            eq("operator_context"),
            argThat<Any> {
                this is CredentialsLoginPayload && username == "operator1" && password == "secret"
            },
            eq(null),
            eq(OperatorContextResponse::class.java),
        )
    }

    @Test
    fun `badge login uses the same v3 login topic with a badge payload`() = runTest {
        // v2 had two topics (reader_login_requested / login_tag_scanned). v3 has one, distinguished
        // only by which authentication field is supplied.
        stub(MqttOutcome.Accepted(accepted, NextAction.NONE))

        useCase.login(LoginMethod.Badge("BADGE001"))

        verify(mqtt).request(
            eq("login_requested"),
            eq("operator_context"),
            argThat<Any> { this is BadgeLoginPayload && badgeTag == "BADGE001" },
            eq(null),
            eq(OperatorContextResponse::class.java),
        )
    }

    @Test
    fun `a successful login stores the session`() = runTest {
        stub(MqttOutcome.Accepted(accepted, NextAction.NONE))

        val session = useCase.login(LoginMethod.Credentials("operator1", "secret")).getOrThrow()

        assertEquals("session-id", session.operatorSessionId)
        assertEquals("OP-001", session.operatorId)
        assertEquals("Operator One", session.operatorName)
        assertEquals("Operator", session.role)
        assertEquals(listOf("scan_ingredient", "start_machine_cycle"), session.allowedActions)
        assertEquals(listOf("collect", "premix"), session.allowedTabs)
        assertEquals("session-id", sessionHolder.session.value?.operatorSessionId)
    }

    @Test
    fun `an accepted login with no session id is still a failure`() = runTest {
        stub(MqttOutcome.Accepted(accepted.copy(operatorSessionId = ""), NextAction.NONE))

        val result = useCase.login(LoginMethod.Credentials("operator1", "secret"))

        assertTrue(result.isFailure)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a rejected login fails with the operator-readable reason and stores no session`() = runTest {
        stub(
            MqttOutcome.Rejected(
                body = OperatorContextResponse(),
                errorCode = ErrorCode.PERMISSION_DENIED,
                reason = "Incorrect username or password.",
                nextAction = NextAction.LOGIN,
            )
        )

        val result = useCase.login(LoginMethod.Credentials("operator1", "wrong"))

        assertTrue(result.isFailure)
        assertEquals("Incorrect username or password.", result.exceptionOrNull()?.message)
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `a timeout fails with a connection message`() = runTest {
        stub(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.login(LoginMethod.Credentials("operator1", "secret"))

        assertTrue(result.isFailure)
        assertEquals("Station 2 did not respond", result.exceptionOrNull()?.message)
    }

    @Test
    fun `being disconnected fails with a connection message`() = runTest {
        stub(MqttOutcome.NoResponse(FailureKind.NotConnected))

        val result = useCase.login(LoginMethod.Credentials("operator1", "secret"))

        assertTrue(result.isFailure)
        assertEquals("Not connected to Station 2", result.exceptionOrNull()?.message)
    }

    @Test
    fun `logout sends the envelope-only request and clears the session`() = runTest {
        sessionHolder.set(
            com.mitas.ppnam.station2aa.data.session.OperatorSession(
                operatorSessionId = "session-id",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        stub(MqttOutcome.Accepted(OperatorContextResponse(), NextAction.LOGIN))

        val result = useCase.logout()

        assertTrue(result.isSuccess)
        verify(mqtt).request(
            eq("reader_logout_requested"),
            eq("operator_context"),
            eq(com.mitas.ppnam.station2aa.data.mqtt.EmptyPayload),
            eq(null),
            eq(OperatorContextResponse::class.java),
        )
        assertNull(sessionHolder.session.value)
    }

    @Test
    fun `logout clears the local session even when Station 2 never answers`() = runTest {
        sessionHolder.set(
            com.mitas.ppnam.station2aa.data.session.OperatorSession(
                operatorSessionId = "session-id",
                operatorId = "OP-001",
                operatorName = "Operator One",
                role = "Operator",
            )
        )
        stub(MqttOutcome.NoResponse(FailureKind.Timeout))

        val result = useCase.logout()

        // Leaving an operator stuck logged-in on the handheld because the network blipped would be
        // worse than a server-side session that expires on its own.
        assertTrue(result.isSuccess)
        assertNull(sessionHolder.session.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.AuthUseCaseTest"`
Expected: FAIL — `Unresolved reference: CredentialsLoginPayload`.

- [ ] **Step 3: Rewrite the auth DTOs**

Replace the entire contents of `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * v3 collapses v2's two login topics (reader_login_requested / login_tag_scanned) into one
 * `login_requested`. The authentication method is identified by which payload is sent — the
 * contract requires exactly one method per request.
 *
 * Message-specific fields only; the transport injects the envelope.
 */
data class CredentialsLoginPayload(
    val username: String,
    val password: String,
)

data class BadgeLoginPayload(
    val badgeTag: String,
)

/**
 * Response to both `login_requested` and `reader_logout_requested`.
 *
 * Note this DTO reads `operatorSessionId`, which is an envelope field rather than a body field —
 * the one deliberate overlap in the codebase. Login is where the session is issued, so the use case
 * has no other way to obtain it, and Gson parses envelope and body from the same flat JSON object.
 *
 * `role` is informational only. Nothing in the contract gates on it — see the privileged-actions
 * rules, which authorise on the approver's allowedActions, never on a role.
 *
 * `sessionState` and `sessionExpiresAtUtc` are deliberately unmapped here; sub-project 2 adds them
 * along with the session-lifecycle behaviour they drive. Gson ignores unmapped JSON fields.
 */
data class OperatorContextResponse(
    val operatorSessionId: String = "",
    val operatorId: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    val allowedActions: List<String> = emptyList(),
    val allowedTabs: List<String> = emptyList(),
)
```

- [ ] **Step 4: Rewrite AuthUseCase**

Replace the entire contents of `app/src/main/java/com/ppnam/station2aa/domain/usecase/AuthUseCase.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.EmptyPayload
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.BadgeLoginPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.CredentialsLoginPayload
import com.mitas.ppnam.station2aa.data.mqtt.dto.OperatorContextResponse
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject

sealed class LoginMethod {
    data class Credentials(val username: String, val password: String) : LoginMethod()
    data class Badge(val badgeTag: String) : LoginMethod()
}

class AuthUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val sessionHolder: OperatorSessionHolder,
) {

    suspend fun login(method: LoginMethod): Result<OperatorSession> {
        val payload: Any = when (method) {
            is LoginMethod.Credentials -> CredentialsLoginPayload(method.username, method.password)
            is LoginMethod.Badge -> BadgeLoginPayload(method.badgeTag)
        }

        val outcome = mqttRepository.request(
            requestType = "login_requested",
            responseType = "operator_context",
            payload = payload,
            correlationKey = null,
            responseClass = OperatorContextResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> {
                val response = outcome.body
                if (response.operatorSessionId.isBlank()) {
                    Result.failure(Exception("Station 2 accepted the login but issued no session"))
                } else {
                    val session = OperatorSession(
                        operatorSessionId = response.operatorSessionId,
                        operatorId = response.operatorId.orEmpty(),
                        operatorName = response.displayName.orEmpty(),
                        role = response.role.orEmpty(),
                        allowedActions = response.allowedActions,
                        allowedTabs = response.allowedTabs,
                    )
                    sessionHolder.set(session)
                    Result.success(session)
                }
            }
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Login failed"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    /**
     * Closes this handheld's session. The local session is cleared regardless of the outcome:
     * stranding an operator logged-in because the network blipped would be worse than a server-side
     * session that expires on its own at sessionExpiresAtUtc.
     */
    suspend fun logout(): Result<Unit> {
        mqttRepository.request(
            requestType = "reader_logout_requested",
            responseType = "operator_context",
            payload = EmptyPayload,
            correlationKey = null,
            responseClass = OperatorContextResponse::class.java,
        )
        sessionHolder.clear()
        return Result.success(Unit)
    }
}

internal fun FailureKind.message(): String = when (this) {
    FailureKind.NotConnected -> "Not connected to Station 2"
    FailureKind.Timeout -> "Station 2 did not respond"
    FailureKind.MalformedResponse -> "Station 2 sent an unreadable response"
}
```

- [ ] **Step 5: Reuse the shared failure message in PalletUseCase**

`PalletUseCase` (Task 10) has the same `when (kind)` block inline. Replace it with the shared helper so the operator sees one wording. In `app/src/main/java/com/ppnam/station2aa/domain/usecase/PalletUseCase.kt`, replace:

```kotlin
        is MqttOutcome.NoResponse -> Result.failure(
            Exception(
                when (kind) {
                    FailureKind.NotConnected -> "Not connected to Station 2"
                    FailureKind.Timeout -> "Station 2 did not respond"
                    FailureKind.MalformedResponse -> "Station 2 sent an unreadable response"
                }
            )
        )
```

with:

```kotlin
        is MqttOutcome.NoResponse -> Result.failure(Exception(kind.message()))
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.mitas.ppnam.station2aa.domain.usecase.*"`
Expected: PASS — 9 auth tests plus the 16 pallet tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/AuthMessages.kt \
        app/src/main/java/com/ppnam/station2aa/domain/usecase/AuthUseCase.kt \
        app/src/main/java/com/ppnam/station2aa/domain/usecase/PalletUseCase.kt \
        app/src/test/java/com/ppnam/station2aa/domain/usecase/AuthUseCaseTest.kt
git commit -m "feat(auth): port to v3 transport, collapsing the two login topics into login_requested"
```

---

### Task 13: Port MixingUseCase to the v3 transport

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`
- Modify: `app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt`

**Interfaces:**
- Consumes: `MqttRepository.request` (Task 5), `MqttOutcome` (Task 3), `FailureKind.message()` (Task 12), `PalletUseCase.recoverToHolding` (Task 10).
- Produces: unchanged public signatures for `lookupJob`, `fetchActiveJobCards`, `cancelJob`, `scanIngredient`, `recoverHolding`; request DTOs become envelope-free payloads.

**This is a mechanical port, not a redesign.** Apply the v3 topic renames and move to `request()`. Payload *shape* changes — `lineNumber`, `bagSize`, `availableQuantity`, `overCollectionToleranceBags`, inline manager approval — are sub-project 3's work. Do not attempt them here.

Topic renames applied:

| v2 | v3 |
| --- | --- |
| `job_card_submitted` (no collectionId) → `ingredient_collection_loaded` | `job_card_load_requested` → `bom_loaded` |
| `job_card_submitted` (with collectionId) → `ingredient_collection_loaded` | `collection_resume_requested` → `bom_loaded` |
| `active_ingredient_collections_requested` → `active_ingredient_collections_list` | `active_job_cards_requested` → `active_job_cards_list` |
| `premix_cancelled` → `premix_cancel_result` | `ingredient_collection_cancel_requested` → `ingredient_collection_cancel_result` |
| `ingredient_scanned` → `ingredient_scan_result` | `ingredient_scan_requested` → `ingredient_scan_result` |

Two structural notes:

**`lookupJob` splits by `collectionId`.** v2 sent one topic and let the backend decide. v3 makes load and resume different operations: load always starts a new destination-neutral collection and never silently resumes, while resume replays a stored BOM snapshot without calling SAP. The existing `collectionId` parameter already carries exactly the information needed to choose.

**`recoverHolding` delegates to `PalletUseCase`.** Task 10 implemented this message properly; a second implementation here would be duplicate wire code that could drift.

**`approveManagerException` has no v3 equivalent.** The `manager_approval_requested` topic and the whole `approvalId` handshake are deleted from the contract — v3 resubmits the rejected scan inline with manager credentials and a fresh `messageId`. It is left compiling and marked `@Deprecated` so `MixingViewModel` and `IngredientScanScreen` keep building; sub-project 3 replaces the flow and deletes it. It will not work against a v3 backend, which is acceptable because no v3 backend exists yet and sub-project 3 lands before cutover.

- [ ] **Step 1: Rewrite the job-card request DTOs**

In `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt`, replace `JobCardSubmittedRequest`, `ActiveJobCardsRequest`, and `PreMixCancelledRequest` with envelope-free payloads:

```kotlin
/** `job_card_load_requested` — always starts a new collection; never silently resumes. */
data class JobCardLoadPayload(
    val jobCardNumber: String,
)

/** `collection_resume_requested` — replays the stored BOM snapshot without calling SAP again. */
data class CollectionResumePayload(
    val jobCardNumber: String,
    val collectionId: String,
)

/**
 * `ingredient_collection_cancel_requested`. A privileged action: manager credentials travel inline
 * and are checked against the APPROVER's account, never the sender's session — so they are required
 * even when the sender is themselves a Manager.
 *
 * `auditReason` is the operator's justification, written to the audit trail. It is not the same
 * field as a response's `reason`, which is why Station 2 rejected something.
 */
data class IngredientCollectionCancelPayload(
    val collectionId: String,
    val managerUsername: String,
    val managerPassword: String,
    val auditReason: String,
)
```

Delete `ActiveJobCardsRequest` entirely — `active_job_cards_requested` carries only the envelope, so it uses `EmptyPayload`.

Strip the envelope fields (`messageId`, `schemaVersion`, `deviceId`, `operatorSessionId`, `timestampUtc`, `correlationKey`) from `BomLoadedResponse`, `ActiveJobCardsListResponse`, and `PreMixCancelResultResponse`, keeping `accepted`/`reason` off them too — the transport reads all of those from `ResponseEnvelope`. Rename `PreMixCancelResultResponse` to `IngredientCollectionCancelResultResponse` and `resumedExistingPreMix` to `resumed`, per the contract's `bom_loaded` shape.

- [ ] **Step 2: Rewrite the ingredient request DTO**

In `app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt`, replace `IngredientScannedRequest` with:

```kotlin
/**
 * `ingredient_scan_requested`. Message-specific fields only.
 *
 * Sub-project 3 adds v3's inline manager approval (managerUsername / managerPassword / auditReason
 * on a resubmitted scan with a FRESH messageId) and removes `approvalId`, which v3 does not have.
 */
data class IngredientScanPayload(
    val collectionId: String,
    val palletRfidTag: String,
    val requestedMaterialCode: String? = null,
    val bagSizeOption: String? = null,
    val bagCount: Double? = null,
    val quantity: Double? = null,
)
```

Strip the envelope and `accepted`/`reason` fields from `IngredientScanResultResponse` for the same reason as Step 1.

- [ ] **Step 3: Port MixingUseCase**

In `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`:

Add `PalletUseCase` to the constructor and drop `settingsRepository` and `sessionHolder` (the transport owns `deviceId` and `operatorSessionId` now):

```kotlin
@Singleton
class MixingUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val bomCacheDao: BomCacheDao,
    private val palletUseCase: PalletUseCase,
) {
    private val gson = Gson()
```

Replace `lookupJob` with:

```kotlin
    /**
     * Loads a job card, or resumes an exact existing collection when [collectionId] is supplied.
     *
     * v3 splits what v2 sent as one message: a load always creates a NEW destination-neutral
     * collection (loading the same job again after earlier work is valid and traceable), whereas a
     * resume replays the stored immutable BOM snapshot without calling SAP.
     */
    suspend fun lookupJob(jobCardNumber: String, collectionId: String = ""): Result<ProductionOrder> {
        val resuming = collectionId.isNotBlank()
        val outcome = mqttRepository.request(
            requestType = if (resuming) "collection_resume_requested" else "job_card_load_requested",
            responseType = "bom_loaded",
            payload = if (resuming) {
                CollectionResumePayload(jobCardNumber = jobCardNumber, collectionId = collectionId)
            } else {
                JobCardLoadPayload(jobCardNumber = jobCardNumber)
            },
            correlationKey = if (resuming) collectionId else jobCardNumber,
            responseClass = BomLoadedResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> {
                val order = outcome.body.toProductionOrder()
                bomCacheDao.put(
                    BomCacheEntity(jobCardNumber, gson.toJson(order), Instant.now().toEpochMilli())
                )
                Result.success(order)
            }
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Job card rejected"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    private fun BomLoadedResponse.toProductionOrder() = ProductionOrder(
        docNo = jobCardNumber,
        collectionId = collectionId,
        // im_Backflush lines stay in Station 2's snapshot but are excluded from the handheld's
        // collection array — the one such line names the product being made.
        productBeingMade = ingredients.firstOrNull { it.issueType == "im_Backflush" }?.materialName,
        lines = ingredients
            .filter { it.issueType != "im_Backflush" }
            .map { line ->
                BomLine(
                    itemCode = line.materialCode,
                    itemName = line.materialName,
                    requiredQty = line.plannedQuantity,
                    scannedQty = line.issuedQuantity,
                    remainingQty = line.remainingQuantity,
                    // SAP UoM 269 displays as kg and 268 as each; unknown values pass through.
                    uom = line.unit.ifBlank { line.uomCode },
                )
            },
    )
```

Replace `fetchActiveJobCards` with:

```kotlin
    suspend fun fetchActiveJobCards(): Result<List<ActiveJobCardSummary>> =
        when (
            val outcome = mqttRepository.request(
                requestType = "active_job_cards_requested",
                responseType = "active_job_cards_list",
                payload = EmptyPayload,
                correlationKey = null,
                responseClass = ActiveJobCardsListResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(outcome.body.jobs)
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Could not load active jobs"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
```

(Rename `ActiveJobCardsListResponse.collections` to `jobs` to match the contract, and update `ActiveJobCardSummary` call sites accordingly.)

Replace `cancelJob` with:

```kotlin
    /**
     * Cancels an eligible collection. Manager credentials are ALWAYS required — v3 authorises this
     * on the approver named in the request, never on the sending session, so a Manager cancelling
     * from their own handheld must still supply credentials. Rejected once routing or other
     * protected downstream activity has happened.
     */
    suspend fun cancelJob(
        collectionId: String,
        jobCardNumber: String,
        reason: String,
        managerUsername: String,
        managerPassword: String,
    ): Result<IngredientCollectionCancelResultResponse> =
        when (
            val outcome = mqttRepository.request(
                requestType = "ingredient_collection_cancel_requested",
                responseType = "ingredient_collection_cancel_result",
                payload = IngredientCollectionCancelPayload(
                    collectionId = collectionId,
                    managerUsername = managerUsername,
                    managerPassword = managerPassword,
                    auditReason = reason,
                ),
                correlationKey = collectionId.ifBlank { jobCardNumber },
                responseClass = IngredientCollectionCancelResultResponse::class.java,
            )
        ) {
            is MqttOutcome.Accepted -> Result.success(outcome.body)
            is MqttOutcome.Rejected -> Result.failure(Exception(outcome.reason ?: "Cancel rejected"))
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
```

Note the `managerUsername` / `managerPassword` parameters lose their `= ""` defaults: v3 requires them on every privileged request, so a caller must now pass them explicitly.

Replace `scanIngredient` with:

```kotlin
    suspend fun scanIngredient(
        collectionId: String,
        palletRfidTag: String,
        bagSizeOption: String,
        bagCount: Double,
    ): Result<IngredientScanOutcome> {
        val outcome = mqttRepository.request(
            requestType = "ingredient_scan_requested",
            responseType = "ingredient_scan_result",
            payload = IngredientScanPayload(
                collectionId = collectionId,
                palletRfidTag = palletRfidTag,
                bagSizeOption = bagSizeOption,
                bagCount = bagCount,
            ),
            correlationKey = collectionId,
            responseClass = IngredientScanResultResponse::class.java,
        )

        return when (outcome) {
            is MqttOutcome.Accepted -> Result.success(
                IngredientScanOutcome.Accepted(outcome.body.ingredientProgress.toBomLines())
            )
            is MqttOutcome.Rejected -> {
                val body = outcome.body
                Result.success(
                    when {
                        body.requiresManagerApproval -> IngredientScanOutcome.NeedsManagerApproval(
                            exceptionId = body.exceptionId,
                            reason = outcome.reason ?: "Manager approval required",
                            requestedMaterialCode = body.ingredientProgress
                                .firstOrNull { it.requiresManagerApproval }?.materialCode.orEmpty(),
                        )
                        outcome.nextAction == NextAction.RECOVER_HOLDING ->
                            IngredientScanOutcome.NeedsRecovery(outcome.reason)
                        else -> IngredientScanOutcome.Rejected(outcome.reason ?: "Ingredient scan rejected")
                    }
                )
            }
            is MqttOutcome.NoResponse -> Result.failure(Exception(outcome.kind.message()))
        }
    }

    private fun List<BomProgressLineResponse>.toBomLines(): List<BomLine> = map { line ->
        BomLine(
            itemCode = line.materialCode,
            itemName = line.materialName,
            requiredQty = line.requiredQuantity,
            scannedQty = line.scannedQuantity,
            remainingQty = line.remainingQuantity,
            uom = line.unit.ifBlank { line.uomCode },
            expectedBags = line.expectedBags,
            scannedBags = line.scannedBags,
            remainingBags = line.remainingBags,
        )
    }
```

Note the approval and recovery branches now live under `Rejected`, not `Accepted`: v3 returns `accepted: false` when a scan needs approval, and the response still carries the full refreshed `ingredientProgress` — which is exactly why `Rejected` carries its body.

Replace `recoverHolding` with a delegation:

```kotlin
    /** Delegates to [PalletUseCase] — holding recovery has one implementation, in one place. */
    suspend fun recoverHolding(collectionId: String, palletRfidTag: String): Result<Unit> =
        palletUseCase.recoverToHolding(
            palletRfidTag = palletRfidTag,
            collectionId = collectionId.ifBlank { null },
            auditReason = "Pallet is physically at Station 2; fixed door read was missed.",
        ).map { }
```

Mark `approveManagerException` deprecated, leaving its body ported to `request()`:

```kotlin
    @Deprecated(
        "v3 has no manager_approval_requested topic and no approvalId. A scan needing approval is " +
            "resubmitted inline with managerUsername/managerPassword/auditReason and a FRESH " +
            "messageId. Sub-project 3 replaces this flow and deletes this method. Kept only so " +
            "MixingViewModel and IngredientScanScreen keep compiling; it will not work against a " +
            "v3 backend."
    )
    suspend fun approveManagerException(
        exceptionId: String,
        collectionId: String,
        palletRfidTag: String,
        requestedMaterialCode: String,
        managerUsername: String,
        managerPassword: String,
        reason: String,
    ): Result<String> = Result.failure(
        UnsupportedOperationException("Manager approval is reimplemented in sub-project 3")
    )
```

Delete the now-unused private `HopperCheckResponse` class if `checkHopper` has already gone; otherwise Task 14 removes both.

Add the needed imports and remove the obsolete ones (`MqttTypedResult`, `SettingsRepository`, `OperatorSessionHolder`, `UUID`, the deleted request DTOs).

- [ ] **Step 4: Update MixingUseCaseTest**

`app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt` stubs `sendTyped`. Rewrite every stub to the `request()` form, following the pattern established in `AuthUseCaseTest` Step 1:

```kotlin
whenever(
    mqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java))
).thenReturn(MqttOutcome.Accepted(bomLoadedResponse, NextAction.SCAN_INGREDIENT))
```

Delete the `checkHopper` and `completePremix` test blocks (lines 772–825) — Task 14 deletes those methods.

Add these tests, which cover the v3 behaviour changes this task introduces:

```kotlin
    @Test
    fun `a blank collectionId loads a new job card`() = runTest {
        whenever(
            mqtt.request(eq("job_card_load_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java))
        ).thenReturn(MqttOutcome.Accepted(bomLoadedResponse, NextAction.SCAN_INGREDIENT))

        useCase.lookupJob("510019068")

        verify(mqtt).request(
            eq("job_card_load_requested"), eq("bom_loaded"),
            argThat<Any> { this is JobCardLoadPayload && jobCardNumber == "510019068" },
            eq("510019068"), eq(BomLoadedResponse::class.java),
        )
    }

    @Test
    fun `a supplied collectionId resumes that exact collection instead of reloading SAP`() = runTest {
        whenever(
            mqtt.request(eq("collection_resume_requested"), eq("bom_loaded"), any(), any(), eq(BomLoadedResponse::class.java))
        ).thenReturn(MqttOutcome.Accepted(bomLoadedResponse, NextAction.SCAN_INGREDIENT))

        useCase.lookupJob("510019068", "COL_000123")

        verify(mqtt).request(
            eq("collection_resume_requested"), eq("bom_loaded"),
            argThat<Any> {
                this is CollectionResumePayload && collectionId == "COL_000123" && jobCardNumber == "510019068"
            },
            eq("COL_000123"), eq(BomLoadedResponse::class.java),
        )
    }

    @Test
    fun `a scan needing approval arrives as a rejection that still carries refreshed progress`() = runTest {
        val body = IngredientScanResultResponse(
            collectionId = "COL_000123",
            requiresManagerApproval = true,
            exceptionId = "EXC-1",
            ingredientProgress = listOf(
                BomProgressLineResponse(materialCode = "1600000301", requiresManagerApproval = true)
            ),
        )
        whenever(
            mqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java))
        ).thenReturn(
            MqttOutcome.Rejected(body, null, "Over tolerance", NextAction.RETRY_WITH_MANAGER_APPROVAL)
        )

        val outcome = useCase.scanIngredient("COL_000123", "TAG-1", "full", 1.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsManagerApproval)
        assertEquals("1600000301", (outcome as IngredientScanOutcome.NeedsManagerApproval).requestedMaterialCode)
    }

    @Test
    fun `a scan against an unarrived pallet asks for recovery`() = runTest {
        whenever(
            mqtt.request(eq("ingredient_scan_requested"), eq("ingredient_scan_result"), any(), any(), eq(IngredientScanResultResponse::class.java))
        ).thenReturn(
            MqttOutcome.Rejected(
                IngredientScanResultResponse(), null, "Pallet is not at Station 2", NextAction.RECOVER_HOLDING
            )
        )

        val outcome = useCase.scanIngredient("COL_000123", "TAG-1", "full", 1.0).getOrThrow()

        assertTrue(outcome is IngredientScanOutcome.NeedsRecovery)
    }

    @Test
    fun `recoverHolding delegates to PalletUseCase rather than re-implementing the message`() = runTest {
        whenever(palletUseCase.recoverToHolding(eq("TAG-1"), eq("COL_000123"), any()))
            .thenReturn(Result.success(mock()))

        val result = useCase.recoverHolding("COL_000123", "TAG-1")

        assertTrue(result.isSuccess)
        verify(palletUseCase).recoverToHolding(eq("TAG-1"), eq("COL_000123"), any())
    }
```

- [ ] **Step 5: Update MixingViewModel call sites**

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`:

- `scanIngredient(...)` no longer takes `approvalId` — remove that argument from the call.
- `cancelJob(...)` now requires `managerUsername` and `managerPassword` explicitly. `operatorCanCancelDirectly()` is contract-violating in v3 (roles are informational; manager credentials are required even for Managers) but its removal belongs to sub-project 2 alongside the rest of the role-gating work. For now, pass the credentials the dialog already collects and leave the gate in place.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt \
        app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/JobCardMessages.kt \
        app/src/main/java/com/ppnam/station2aa/data/mqtt/dto/IngredientMessages.kt \
        app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt \
        app/src/test/java/com/ppnam/station2aa/domain/usecase/MixingUseCaseTest.kt
git commit -m "feat(mixing): port to v3 transport and topic names

Splits job_card_submitted into job_card_load_requested and
collection_resume_requested. Payload reshaping and inline manager
approval land in sub-project 3."
```

---

### Task 14: Delete Dashboard, Rajoo, and the hopper/premix screens

**Files:**
- Delete: `app/src/main/java/com/ppnam/station2aa/domain/usecase/DashboardUseCase.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/ui/dashboard/` (whole directory)
- Delete: `app/src/main/java/com/ppnam/station2aa/domain/usecase/RajooUseCase.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/ui/rajoo/` (whole directory)
- Delete: `app/src/main/java/com/ppnam/station2aa/domain/model/AllocationRecord.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/ui/mixing/HopperScanScreen.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/ui/mixing/PreMixCompleteScreen.kt`
- Delete: `app/src/test/java/com/ppnam/station2aa/domain/usecase/RajooUseCaseTest.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`
- Modify: `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Pure subtraction.

Every one of these rides the legacy `{station}/request` protocol, which the backend has never subscribed to — as the in-code comment records, "every call here has therefore always silently timed out."

Dashboard and Rajoo have **no routes in `AppNavGraph`** at all: they are unreachable dead code, and deleting them costs nothing. Rajoo returns in sub-project 5 under v3's unified machine-cycle model, where `allocate-rajoo` becomes `machine_cycle_start_requested`.

`HOPPER_SCAN` and `PREMIX_COMPLETE` **are** routed, but ride `checkHopper` / `completePremix` on the same dead path, so they have never worked either. Sub-project 4 rebuilds them properly on `machine_cycle_start_requested` / `machine_cycle_finish_requested`, where one collection may run several Hoppers concurrently against one shared pre-mix — a workflow the current one-hopper-one-mix screens cannot express anyway.

Removing these routes is not a regression. It makes the navigation honest: every remaining button will work.

- [ ] **Step 1: Delete the unreachable Dashboard and Rajoo code**

```bash
git rm -r app/src/main/java/com/ppnam/station2aa/ui/dashboard \
          app/src/main/java/com/ppnam/station2aa/ui/rajoo
git rm app/src/main/java/com/ppnam/station2aa/domain/usecase/DashboardUseCase.kt \
       app/src/main/java/com/ppnam/station2aa/domain/usecase/RajooUseCase.kt \
       app/src/main/java/com/ppnam/station2aa/domain/model/AllocationRecord.kt \
       app/src/test/java/com/ppnam/station2aa/domain/usecase/RajooUseCaseTest.kt
```

- [ ] **Step 2: Delete the never-functional hopper and premix screens**

```bash
git rm app/src/main/java/com/ppnam/station2aa/ui/mixing/HopperScanScreen.kt \
       app/src/main/java/com/ppnam/station2aa/ui/mixing/PreMixCompleteScreen.kt
```

- [ ] **Step 3: Remove their routes**

In `app/src/main/java/com/ppnam/station2aa/navigation/NavRoutes.kt`, delete these lines:

```kotlin
    const val HOPPER_SCAN = "mixing/hopper_scan/{orderNo}"
    const val PREMIX_COMPLETE = "mixing/premix_complete/{orderNo}"
```

and these functions:

```kotlin
    fun hopperScan(orderNo: String) = "mixing/hopper_scan/$orderNo"
    fun premixComplete(orderNo: String) = "mixing/premix_complete/$orderNo"
```

In `app/src/main/java/com/ppnam/station2aa/navigation/AppNavGraph.kt`, delete the whole `composable(NavRoutes.HOPPER_SCAN) { ... }` block (starting line 74) and the whole `composable(NavRoutes.PREMIX_COMPLETE) { ... }` block (starting line 91), plus any imports of the deleted screens. Delete any navigation call that targets them (search for `hopperScan(` and `premixComplete(`).

- [ ] **Step 4: Remove the dead use-case methods and view-model plumbing**

In `app/src/main/java/com/ppnam/station2aa/domain/usecase/MixingUseCase.kt`, delete the `checkHopper` and `completePremix` methods and the private `HopperCheckResponse` class.

In `app/src/main/java/com/ppnam/station2aa/ui/mixing/MixingViewModel.kt`, delete the `checkHopper` call site (around line 263), the `completePremix` function (around line 285), and the `_hopperCode` state they used, along with any now-unreferenced `MixingUiState` members. Follow the compiler.

- [ ] **Step 5: Delete the corresponding tests**

In `app/src/test/java/com/ppnam/station2aa/ui/mixing/MixingViewModelTest.kt`, delete the tests stubbing `mockUseCase.checkHopper` (around lines 288 and 305) and any covering `completePremix`.

- [ ] **Step 6: Verify nothing references the deleted code**

Run: `grep -rn "checkHopper\|completePremix\|RajooUseCase\|DashboardUseCase\|AllocationRecord\|HopperScanScreen\|PreMixCompleteScreen\|HOPPER_SCAN\|PREMIX_COMPLETE" app/src --include=*.kt`
Expected: no output.

- [ ] **Step 7: Run the full suite and build**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore: delete Dashboard, Rajoo and the hopper/premix screens

All rode the legacy {station}/request protocol the backend never
subscribed to, so none of them have ever worked. Dashboard and Rajoo
were additionally unreachable — no routes existed. Rajoo returns in
sub-project 5 and hopper/premix in sub-project 4, on v3 machine cycles."
```

---

### Task 15: Delete the legacy transport

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttMessages.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTypedResult.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Pure subtraction.

Tasks 12 and 13 moved the last callers onto `request()`. Everything below is now unreferenced.

- [ ] **Step 1: Confirm nothing calls the legacy API**

Run: `grep -rn "sendTyped\|publishTyped\|sendWithTimeout\|MqttResult\|MqttTypedResult\|MqttRequest\b\|MqttResponseMessage" app/src --include=*.kt`
Expected: matches only inside `MqttRepositoryImpl.kt`, `MqttRepository.kt`, `MqttMessages.kt`, `MqttTypedResult.kt`, and `OfflineQueueRepository.kt` (Task 16 deletes that one). If any other file appears, port it before continuing.

- [ ] **Step 2: Remove the legacy methods from the interface**

In `app/src/main/java/com/ppnam/station2aa/domain/repository/MqttRepository.kt`, delete these three declarations and their now-unused imports (`MqttResult`, `MqttTypedResult`):

```kotlin
    suspend fun send(action: String, dataJson: String): MqttResult
    suspend fun <T> sendTyped(
        requestType: String,
        responseType: String,
        requestJson: String,
        responseClass: Class<T>,
        allowOfflineQueue: Boolean
    ): MqttTypedResult<T>
    suspend fun publishTyped(requestType: String, requestJson: String)
```

The interface should now expose exactly: `connectionState`, `stationOnline`, `clockSkewMillis`, `request`, `connect`, `disconnect`, `reconnectWith`.

- [ ] **Step 3: Remove the legacy implementations**

In `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`, delete:
- `send(...)`
- `sendWithTimeout(...)` and its long explanatory comment
- `sendTyped(...)`
- `publishTyped(...)`
- `enqueue(...)` and its comment
- the `offlineQueueDao` constructor parameter (Task 16 deletes the DAO itself)
- any leftover `_incomingTyped` / `_incomingResponses` fields and `handleIncomingTyped`

Remove the now-unused imports: `OfflineQueueDao`, `OfflineQueueEntity`, and `java.util.concurrent.TimeUnit` if `publishOfflineBestEffort` is the only remaining user (it is — keep that import).

- [ ] **Step 4: Delete the legacy message types**

```bash
git rm app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttMessages.kt \
       app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttTypedResult.kt
```

- [ ] **Step 5: Update the test constructor**

In every test that constructs `MqttRepositoryImpl`, drop the `offlineQueueDao` argument. Files: `MqttRepositoryImplTest.kt`, `MqttRequestCorrelationTest.kt`, `MqttRequestRetryTest.kt`, `MqttStationPresenceTest.kt`, `MqttClockSkewTest.kt`. The constructor becomes:

```kotlin
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = OperatorSessionHolder(),
        )
```

- [ ] **Step 6: Run the full suite and build**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore(mqtt): delete the schema 2.0 transport

Removes send/sendTyped/publishTyped/sendWithTimeout and the legacy
action-string envelope. v3 is not wire-compatible with v2 and the
cutover is big-bang, so nothing is retained behind a flag."
```

---

### Task 16: Delete the offline queue

**Files:**
- Delete: `app/src/main/java/com/ppnam/station2aa/data/local/OfflineQueueEntity.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/data/local/OfflineQueueDao.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/data/local/OfflineQueueRepository.kt`
- Delete: `app/src/main/java/com/ppnam/station2aa/worker/OfflineQueueWorker.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/local/AppDatabase.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/di/AppModule.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/PpnamApplication.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/AppSettings.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/settings/SettingsRepository.kt`
- Modify: every screen and ViewModel listed in Step 4

**Interfaces:**
- Consumes: nothing.
- Produces: `AppScaffold` loses its `pendingCount: Int` parameter.

**Why this is a deletion and not a migration.** v3's replay identity would make redelivering a queued message *safe*, which is tempting. But the envelope also requires `timestampUtc` to sit inside Station 2's acceptance window, and a scan queued offline then delivered ten minutes later is rejected as `message_expired`. The timestamp cannot be refreshed without changing the body: keep the `messageId` and it is `message_id_reused`; mint a new one and the idempotency that made queuing safe is gone. Replay lookup runs before timestamp rejection, but that only rescues a duplicate of an **already-accepted** message, not a first delivery that never landed.

The queue is already dormant (no caller ever set `allowOfflineQueue = true`), already drains through the dead legacy path, and already caused one production incident — the `premix_cancelled` blank-payload spam. Offline now means an honest "not connected" state.

`pendingCount` is threaded through **every** screen via `AppScaffold`, so this ripples widely. The work is mechanical; the compiler will find every site.

- [ ] **Step 1: Delete the queue itself**

```bash
git rm app/src/main/java/com/ppnam/station2aa/data/local/OfflineQueueEntity.kt \
       app/src/main/java/com/ppnam/station2aa/data/local/OfflineQueueDao.kt \
       app/src/main/java/com/ppnam/station2aa/data/local/OfflineQueueRepository.kt \
       app/src/main/java/com/ppnam/station2aa/worker/OfflineQueueWorker.kt
```

- [ ] **Step 2: Drop the Room table**

Replace `app/src/main/java/com/ppnam/station2aa/data/local/AppDatabase.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BomCacheEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bomCacheDao(): BomCacheDao
}
```

In `app/src/main/java/com/ppnam/station2aa/di/AppModule.kt`, delete the `provideOfflineQueueDao` provider and the `OfflineQueueDao` import, and add a destructive fallback to the database builder:

```kotlin
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "ppnam_station2.db")
            // The only surviving table is a BOM cache, which is disposable by definition, and the
            // dropped offline_queue held nothing but permanently undeliverable legacy rows. There
            // are no live users to migrate (the v3 cutover is big-bang), so a rebuild is correct.
            // Note: the no-arg overload — Room here is 2.6.1, and the dropAllTables parameter only
            // exists from 2.7 onward.
            .fallbackToDestructiveMigration()
            .build()
```

- [ ] **Step 3: Stop scheduling the drain worker**

In `app/src/main/java/com/ppnam/station2aa/PpnamApplication.kt`, delete the `scheduleOfflineQueueDrain()` function, the `applicationScope.launch { scheduleOfflineQueueDrain() }` call in `onCreate`, the now-unused `applicationScope` field, the `settingsRepository` injection, and the WorkManager imports (`Constraints`, `ExistingPeriodicWorkPolicy`, `NetworkType`, `PeriodicWorkRequestBuilder`, `WorkManager`, `TimeUnit`), plus the `OfflineQueueWorker` import.

Keep `Configuration.Provider` and the `HiltWorkerFactory` injection. There are no workers right now, but sub-projects 4–5 may add them, and unpicking the Hilt/WorkManager wiring is unrelated risk for no gain.

- [ ] **Step 4: Remove pendingCount from the UI**

In `app/src/main/java/com/ppnam/station2aa/ui/components/AppScaffold.kt`, delete the `pendingCount: Int,` parameter (line 29) and simplify the offline label (line 42):

```kotlin
            DangerRed to "Offline"
```

Then remove `pendingCount` from each of these files — delete the `offlineQueueRepository` constructor injection and the `pendingCount` StateFlow from every ViewModel, and the `val pendingCount by viewModel.pendingCount.collectAsState()` line plus the `pendingCount = pendingCount,` argument from every screen:

| ViewModel | Screen |
| --- | --- |
| `ui/home/HomeViewModel.kt` | `ui/home/HomeScreen.kt` |
| `ui/login/LoginViewModel.kt` | `ui/login/LoginScreen.kt` |
| `ui/mixing/MixingViewModel.kt` | `ui/mixing/JobLookupScreen.kt`, `ui/mixing/IngredientScanScreen.kt` |
| `ui/rfid/RfidViewModel.kt` | `ui/rfid/RfidRecoveryScreen.kt` |
| `ui/settings/SettingsViewModel.kt` | `ui/settings/SettingsScreen.kt` |

In `ui/settings/SettingsViewModel.kt` also delete the `offlineQueueCount` StateFlow (line 53) and the `offlineQueueRepository.deletePending()` call (line 102) along with whatever function wraps it. In `ui/settings/SettingsScreen.kt`, delete the "clear queue" control that invoked it and its `pendingCount = offlineQueueCount,` argument (line 70).

- [ ] **Step 5: Remove the drain-interval setting**

In `app/src/main/java/com/ppnam/station2aa/domain/model/AppSettings.kt`, delete:

```kotlin
    val queueDrainIntervalMin: Int = 15
```

In `app/src/main/java/com/ppnam/station2aa/data/settings/SettingsRepository.kt`, delete the `QUEUE_DRAIN_INTERVAL` key, its line in `settingsFlow`, and its line in `save`. Remove the `intPreferencesKey` import only if `SCANNER_ID` no longer needs it (it does — keep it). Delete any Settings UI field bound to it.

- [ ] **Step 6: Verify nothing references the queue**

Run: `grep -rn "OfflineQueue\|pendingCount\|queueDrainIntervalMin\|offline_queue\|deletePending" app/src --include=*.kt`
Expected: no output.

- [ ] **Step 7: Run the full suite and build**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL. Fix any test that constructed a ViewModel with an `OfflineQueueRepository` mock — including `RfidViewModelTest` (Task 11), `LoginViewModelTest`, `HomeViewModelTest`, `MixingViewModelTest`, and `SettingsViewModelTest`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore: delete the offline queue

v3 requires timestampUtc inside an acceptance window, so a message
queued offline and delivered later is rejected as message_expired --
and the timestamp cannot be refreshed without breaking replay identity.
Queuing new operations does not survive the contract's own rules. The
queue was already dormant, already drained through a dead path, and
already caused the premix_cancelled spam incident."
```

---

### Task 17: Remove AppSettings.stationName

**Files:**
- Modify: `app/src/main/java/com/ppnam/station2aa/domain/model/AppSettings.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`
- Modify: `app/src/main/java/com/ppnam/station2aa/ui/settings/SettingsScreen.kt`
- Modify: `app/src/test/java/com/ppnam/station2aa/domain/model/AppSettingsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Pure subtraction.

`stationName` only ever fed the legacy topic builders. v3 hardcodes `PPNAM/station_2/status`, so the setting now has no wire meaning — and its normalisation was already a latent bug, producing `station2` in three builders and `station_2` in a fourth. Removing it deletes a setting an operator could misconfigure into silence.

- [ ] **Step 1: Remove the field**

In `app/src/main/java/com/ppnam/station2aa/domain/model/AppSettings.kt`, delete:

```kotlin
    val stationName: String = "Station 2",
```

- [ ] **Step 2: Remove its persistence**

In `app/src/main/java/com/ppnam/station2aa/data/settings/SettingsRepository.kt`, delete the `STATION_NAME` key, its line in `settingsFlow`, and its line in `save`.

- [ ] **Step 3: Remove its last transport references**

In `app/src/main/java/com/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt`, delete the `currentStationName` field and the two assignments to it (in `connect()` and `reconnectWith()`). Task 7 already removed the last topic that used it.

- [ ] **Step 4: Remove its Settings UI**

In `app/src/main/java/com/ppnam/station2aa/ui/settings/SettingsScreen.kt`, delete the station-name text field (around line 219) — the `value = draft.stationName` / `onValueChange = { viewModel.updateDraft(draft.copy(stationName = it)) }` block and its enclosing field composable.

- [ ] **Step 5: Update the test**

In `app/src/test/java/com/ppnam/station2aa/domain/model/AppSettingsTest.kt`, delete `default stationName is Station 2`.

- [ ] **Step 6: Verify**

Run: `grep -rn "stationName\|STATION_NAME\|currentStationName" app/src --include=*.kt`
Expected: no output.

- [ ] **Step 7: Run the full suite and build**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: PASS, then BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore(settings): remove stationName

Only ever fed the legacy topic builders, whose normalisation disagreed
with itself (station2 vs station_2). v3 hardcodes PPNAM/station_2/status."
```

---

## Definition of Done

- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] `./gradlew assembleDebug` succeeds.
- [ ] `grep -rn "schemaVersion.*2\.0\|sendTyped\|OfflineQueue\|stationName" app/src --include=*.kt` returns nothing.
- [ ] `grep -rn "\"3.0\"" app/src/main --include=*.kt` matches only `MqttSchema.kt`.
- [ ] The out-of-order correlation test exists and passes — it is the bug fix, not a nicety.
- [ ] RFID Pallet Lookup works end-to-end against a fake, including the recover-to-Holding path.

### QoS must be verified by inspection, not by unit test

The `publishFn` seam that makes correlation and retry testable is *itself* the code that sets QoS —
a test which stubs `publishFn` has replaced the thing it would be asserting. So no unit test can
cover this, and the plan does not pretend otherwise. Verify by reading the code:

- [ ] `MqttRepositoryImpl.publishFn`'s **production default body** calls `.qos(MqttQos.AT_LEAST_ONCE)`
      and `.retain(false)` — workflow messages are QoS 1 and never retained.
- [ ] `subscribeAndAnnounce`'s `online` publish and `connect()`/`reconnectWith()`'s LWT and
      `publishOfflineBestEffort`'s `offline` publish all call `.qos(MqttQos.AT_LEAST_ONCE)` and
      `.retain(true)` — presence is QoS 1 and retained.

This gap closes for real at the first integration test against a live broker, which is
sub-project 2's cutover work.

## Handoff to sub-project 2

Carry these forward:

- `OperatorContextResponse` deliberately omits `sessionState` and `sessionExpiresAtUtc` — sub-project 2 adds them with the session-lifecycle behaviour they drive.
- `MixingViewModel.operatorCanCancelDirectly()` is contract-violating and still in place. v3 says roles are informational only, `allowedActions` is a display hint that must not enforce anything, and manager credentials are required on every privileged action even when the sender is a Manager.
- `clockSkewMillis` and `stationOnline` are exposed but have no UI surface yet. They belong in sub-project 2's connection-status work.
- `MixingUseCase.approveManagerException` is a deprecated stub that always fails. Sub-project 3 replaces it with v3's inline approval retry and deletes it.

## Open questions for the Station 2 developer

1. **What is the configured timestamp acceptance window?** It bounds the retry budget (`REQUEST_MAX_ATTEMPTS` × `requestTimeoutMs`, currently 3 × 10s = 30s) and determines how severely device clock drift degrades a handheld. If the window is under ~30s, reduce the retry budget.
2. **Is `station_2` the literal, fixed device id in the presence topic**, or is it derived from Station 2's own configuration? We hardcode it, per the contract.
3. **Are message-specific `errorCode` values expected beyond the 14 shared codes?** Our value-class design tolerates them either way; this confirms the assumption.
