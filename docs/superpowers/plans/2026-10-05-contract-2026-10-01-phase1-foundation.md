# Contract 2026-10-01 — Phase 1: wire foundation and durable commands — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the app's wire layer up to Station 2 contract revision 2026-10-01:
- complete envelope and snapshot parsing;
- job mix progress on screen;
- a durable outbox of unanswered commands;
- `recover` after re-login.

Together these are the foundation every mutating action in Phases 2–6 needs.

**Architecture:**
- The transport (`MqttRepositoryImpl`) gains `sendCommand`/`retryCommand`. Both persist the exact published JSON (plus its SHA-256) to a file-backed `CommandOutbox` before publishing, and remove it only on a definite outcome.
- `CommandRecoveryUseCase` sends the contract's `recover` action for a command left unresolved.
- `PendingCommandCoordinator` runs recovery for the signed-in operator, and Home shows the results.
- DTOs cover the full `data` snapshot. They are verified against the exact payloads the real Station 2 processor generated.

**Tech Stack:**
- Kotlin 2.0, Android minSdk 26, Hilt 2.51.1;
- HiveMQ MQTT 5 client 1.3.3, Gson 2.10.1, Jetpack Compose;
- JUnit 4, mockito-kotlin 5, kotlinx-coroutines-test;
- Python 3 backend simulator (`tools/backend-sim`).

**Spec:** `docs/superpowers/specs/2026-10-05-contract-2026-10-01-adoption-design.md`. Its authority is `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` at sibling commit `20a4a4c`.

## Global Constraints

- The sibling repo `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2` is **read-only**. Only `RFID_MQTT_CONTRACT.md` there may ever be edited, and this plan edits nothing there. Copying files *out of* it is allowed.
- Wire schema stays exactly `rev2.1`. The contract revision constant is exactly `2026-10-01`.
- The request size limit is **65,536 UTF-8 bytes**.
- `requestFingerprint` / `originalRequestFingerprint` is the **uppercase hex SHA-256 of the exact UTF-8 request bytes**: 64 characters.
- Persist an unanswered mutation's exact bytes, family, ID and SHA-256 **before** publishing it. Retry by republishing those identical bytes with the same messageId. Never re-serialize.
- A server hint (`active_job_cards_invalidated`) never resolves or alters a pending command.
- Branch on `error` only. `nextAction` and `operatorMessage` are never parsed for control flow.
- Every Gson DTO constructor parameter keeps a default value: see `ResponseEnvelope`'s KDoc. Nullable fields default to `null`.
- Never log session IDs, proofs or credentials. `Redact.kt` already covers the transport log; the outbox is not logged.
- Unit-test command: `.\gradlew.bat :app:testDebugUnitTest --offline [--tests "<fqcn>"]`. Build command: `.\gradlew.bat :app:assembleDebug --offline`. Run both from the repo root `C:\Dev\Clients\PPNAM\Station 2\PPNAM_Station_2_AA` in PowerShell.
- After code changes, run `graphify update .` (repo `CLAUDE.md`).
- Work on branch `feat/contract-2026-10-01-foundation` off `master`.

## Review Focus

1. **The app is killed after a command is persisted (with or without a publish).** On the next start the command must still be in the outbox, byte-identical. Pinned in Task 5 by `a new outbox on the same directory reloads the identical command`.
2. **A corrupt or half-written outbox file.** It must never silently disappear, since it may represent a captured bag. Pinned in Task 5 by `an unreadable file becomes a ManagerReconcile entry` and `a leftover tmp file is ignored`.
3. **A duplicate or late reply arrives after the command was settled and removed.** It must be dropped with no crash and no outbox change. Pinned in Task 6 by `a duplicate reply after settlement changes nothing`.
4. **A refresh hint arrives while a command is pending.** The command must stay pending and still be resolved by its own reply. Pinned in Task 6 by `a hint arriving mid-command does not resolve it`.
5. **A different operator signs in on a scanner holding someone else's unresolved command.** It must never be recovered under the wrong operator. Pinned in Task 7 by `a different operator's command is not sent for recovery` and in Task 8 by `only the signed-in operator's commands are recovered`.

---

## File map

| File | Responsibility |
|---|---|
| `app/src/test/resources/contract/2026-10-01/*.json` | Copied golden payloads: exact request bytes plus complete responses |
| `app/src/test/java/.../contract/ContractFixtures.kt` | Loads those fixtures in tests |
| `data/mqtt/RequestFingerprint.kt` | SHA-256 → 64 uppercase hex |
| `data/mqtt/MqttSchema.kt` | + `CONTRACT_REVISION` |
| `data/mqtt/MqttVocabulary.kt` | + four new error codes |
| `data/mqtt/dto/ResponseEnvelope.kt` | + `contractRevision`, `requestFingerprint` |
| `data/mqtt/OutboundGuard.kt` | Byte-based size limit |
| `data/mqtt/MqttTopics.kt` | Subscription QoS constants |
| `data/mqtt/dto/Rev2GeneralMessages.kt` | Full shared snapshot DTOs (`Rev2Snapshot`, …) plus `Rev2RecoverRequest` |
| `domain/model/Job.kt` | + `MixProgress`, `ActivePreparation`, `originalRequired` |
| `domain/usecase/JobLookupUseCase.kt` | Maps the new fields |
| `ui/joblookup/JobFormat.kt`, `JobLookupScreen.kt`, `JobDetailScreen.kt` | Required / Active / Available to prepare / Finished, and active PREPs |
| `data/mqtt/outbox/PendingCommand.kt` | Persisted command model |
| `data/mqtt/outbox/CommandOutbox.kt` | Interface, `InMemoryCommandOutbox`, `FileCommandOutbox` |
| `data/mqtt/outbox/CommandOutcome.kt` | `CommandOutcome`, `UnresolvedReason`, `unresolvedReasonOf()` |
| `domain/repository/MqttRepository.kt`, `data/mqtt/MqttRepositoryImpl.kt` | `sendCommand`, `retryCommand`, `serverContractRevision` |
| `di/AppModule.kt` | Binds `CommandOutbox` |
| `domain/usecase/CommandRecoveryUseCase.kt` | The `recover` action |
| `domain/usecase/PendingCommandCoordinator.kt` | Recovers the signed-in operator's commands and keeps notices |
| `ui/home/RecoveryNoticeText.kt`, `ui/home/UnresolvedCommandsCard.kt`, `HomeViewModel.kt`, `HomeScreen.kt` | Home card |
| `tools/backend-sim/envelope.py`, `sim.py`, `handlers/rev2_general.py`, `selftest.py` | Simulator speaks the new envelope and `mixProgress` |

In the table above, `...`, `data/`, `domain/`, `ui/` and `di/` stand for `app/src/main/java/com/mitas/ppnam/station2aa/` (main) or `app/src/test/java/com/mitas/ppnam/station2aa/` (tests).

---

### Task 0: Branch

- [ ] **Step 1: Create the branch**

```powershell
git checkout master
git pull --ff-only
git checkout -b feat/contract-2026-10-01-foundation
```

---

### Task 1: Golden contract fixtures and the request fingerprint

**Files:**
- Create: `app/src/test/resources/contract/2026-10-01/` (88 copied `.json` files plus `SOURCE.md`)
- Create: `.gitattributes`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/contract/ContractFixtures.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/RequestFingerprint.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/RequestFingerprintTest.kt`

**Interfaces:**
- Produces: `RequestFingerprint.of(bytes: ByteArray): String` (64 uppercase hex).
- Produces: `ContractFixtures.bytes(name)`, `ContractFixtures.text(name)`, `ContractFixtures.names()`, `ContractFixtures.pairs()`. A pair is a base name such as `"general_add"` that has both `_request.json` and `_response.json`.

- [ ] **Step 1: Copy the fixtures byte-for-byte and stop git rewriting them**

Run in Git Bash from the repo root:

```bash
mkdir -p app/src/test/resources/contract/2026-10-01
cp "../PPNAM-Station-2/DOCS/MQTT/Message_Examples/"*.json app/src/test/resources/contract/2026-10-01/
ls app/src/test/resources/contract/2026-10-01 | wc -l   # expect 87
printf 'app/src/test/resources/contract/** -text\n' > .gitattributes
```

Create `app/src/test/resources/contract/2026-10-01/SOURCE.md`:

```markdown
Copied verbatim from `PPNAM-Station-2/DOCS/MQTT/Message_Examples/` at commit `20a4a4c`
(contract `rev2.1`, revision 2026-10-01). Request files are the exact bytes whose SHA-256 is the
response's `requestFingerprint`. Never reformat these files; `.gitattributes` marks them `-text`.
To refresh, re-copy from the sibling repo after a contract change.
```

- [ ] **Step 2: Write the fixture loader**

`app/src/test/java/com/mitas/ppnam/station2aa/contract/ContractFixtures.kt`:

```kotlin
package com.mitas.ppnam.station2aa.contract

import java.io.File

/** The Station 2 generated payloads for contract revision 2026-10-01 (see SOURCE.md beside them). */
object ContractFixtures {
    private const val DIR = "contract/2026-10-01"

    fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("$DIR/$name")) { "No fixture $name" }
            .use { it.readBytes() }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)

    fun names(): List<String> =
        File(javaClass.classLoader!!.getResource(DIR)!!.toURI()).list()!!.filter { it.endsWith(".json") }.sorted()

    /** Base names with both a request and a response file, e.g. "general_add". */
    fun pairs(): List<String> {
        val all = names().toSet()
        return all.filter { it.endsWith("_request.json") }
            .map { it.removeSuffix("_request.json") }
            .filter { "${it}_response.json" in all }
            .sorted()
    }
}
```

- [ ] **Step 3: Write the failing test**

`app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/RequestFingerprintTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestFingerprintTest {

    @Test
    fun `fingerprint is uppercase hex SHA-256`() {
        assertEquals(
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
            RequestFingerprint.of(ByteArray(0)),
        )
    }

    @Test
    fun `every contract example's requestFingerprint is the hash of its exact request bytes`() {
        val pairs = ContractFixtures.pairs()
        assertEquals(43, pairs.size)
        for (name in pairs) {
            val expected = JsonParser.parseString(ContractFixtures.text("${name}_response.json"))
                .asJsonObject["requestFingerprint"].asString
            assertEquals(name, expected, RequestFingerprint.of(ContractFixtures.bytes("${name}_request.json")))
        }
    }
}
```

- [ ] **Step 4: Run it and see it fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.RequestFingerprintTest"`
Expected: compilation FAILS with `Unresolved reference: RequestFingerprint`.

- [ ] **Step 5: Implement**

`app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/RequestFingerprint.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import java.security.MessageDigest

/**
 * Contract §2/§9: the SHA-256 of the exact UTF-8 request bytes as 64 uppercase hex characters.
 * Station 2 echoes it as `requestFingerprint`; `recover` sends it back as
 * `originalRequestFingerprint`. Hash the bytes that were published — a re-serialisation of the
 * same payload is a different request.
 */
object RequestFingerprint {
    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
}
```

- [ ] **Step 6: Run it and see it pass**

Run the same command. Expected: PASS, 2 tests.

- [ ] **Step 7: Commit**

```bash
git add .gitattributes app/src/test/resources/contract app/src/test/java/com/mitas/ppnam/station2aa/contract app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/RequestFingerprint.kt app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/RequestFingerprintTest.kt
git commit -m "test(contract): golden 2026-10-01 payloads and the request fingerprint"
```

---

### Task 2: Envelope conformance (revision, error codes, byte limit, subscription QoS)

**Files:**
- Modify: `data/mqtt/MqttSchema.kt:13-14`
- Modify: `data/mqtt/MqttVocabulary.kt` (whole file)
- Modify: `data/mqtt/dto/ResponseEnvelope.kt:17-39`
- Modify: `data/mqtt/OutboundGuard.kt` (whole file)
- Modify: `data/mqtt/MqttTopics.kt`
- Modify: `domain/repository/MqttRepository.kt`
- Modify: `data/mqtt/MqttRepositoryImpl.kt`: the state flows near line 70, `request()` near line 452, `handleIncomingResponse` near line 568, and `subscribeAndAnnounce` near lines 256-275
- Modify tests: `OutboundGuardTest.kt:53,58`, `RequestEnvelopeTest.kt:80`
- Test: create `data/mqtt/ContractEnvelopeTest.kt`

**Interfaces:**
- Produces: `MqttSchema.CONTRACT_REVISION = "2026-10-01"`.
- Produces: `ErrorCode.COLLECTION_REVISION_CONFLICT`, `ErrorCode.RECEIPT_OWNER_MISMATCH`, `ErrorCode.RECEIPT_RECOVERY_UNAVAILABLE`, `ErrorCode.RECEIPT_SEALED`.
- Produces: `ResponseEnvelope.contractRevision: String`, `ResponseEnvelope.requestFingerprint: String`.
- Produces: `OutboundGuard.MAX_PAYLOAD_BYTES`.
- Produces: `MqttTopics.RESPONSE_QOS`, `MqttTopics.PRESENCE_QOS`.
- Produces: `MqttRepository.serverContractRevision: StateFlow<String?>`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/ContractEnvelopeTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.hivemq.client.mqtt.datatypes.MqttQos
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ContractEnvelopeTest {

    private fun repo(): MqttRepositoryImpl {
        val identity = mock<DeviceIdentity>()
        whenever(identity.deviceId()).thenReturn("scanner_1")
        return MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = OperatorSessionHolder(),
            deviceIdentity = identity,
        )
    }

    @Test
    fun `the contract revision is 2026-10-01 on wire schema rev2_1`() {
        assertEquals("rev2.1", MqttSchema.VERSION)
        assertEquals("2026-10-01", MqttSchema.CONTRACT_REVISION)
    }

    @Test
    fun `the response envelope carries contractRevision and requestFingerprint`() {
        val env = WireJson.gson.fromJson(ContractFixtures.text("general_add_response.json"), ResponseEnvelope::class.java)
        assertEquals("2026-10-01", env.contractRevision)
        assertEquals(64, env.requestFingerprint.length)
        assertEquals("read_saved_state", env.nextAction)
    }

    @Test
    fun `new error codes parse from the stale-decision example`() {
        val env = WireJson.gson.fromJson(
            ContractFixtures.text("general_revision_rejected_response.json"), ResponseEnvelope::class.java,
        )
        assertEquals(ErrorCode.COLLECTION_REVISION_CONFLICT, env.errorCode)
        val sealed = WireJson.gson.fromJson(
            ContractFixtures.text("general_delayed_sealed_response.json"), ResponseEnvelope::class.java,
        )
        assertEquals(ErrorCode.RECEIPT_SEALED, sealed.errorCode)
    }

    @Test
    fun `any parsed reply records the server's contract revision, even an unmatched one`() {
        val repo = repo()
        assertNull(repo.serverContractRevision.value)
        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_1/res/rev2_general_result",
            ContractFixtures.bytes("general_read_list_response.json"),
        )
        assertEquals("2026-10-01", repo.serverContractRevision.value)
    }

    @Test
    fun `the size limit counts UTF-8 bytes, not characters`() {
        // 33,000 two-byte characters: under the limit in chars, over it in bytes.
        val text = "é".repeat(33_000)
        assertThrows(OversizedPayloadException::class.java) { OutboundGuard.assertWithinSize(text) }
        OutboundGuard.assertWithinSize("é".repeat(32_768)) // exactly 65,536 bytes: allowed
    }

    @Test
    fun `responses subscribe at QoS 1 and presence at QoS 2`() {
        assertEquals(MqttQos.AT_LEAST_ONCE, MqttTopics.RESPONSE_QOS)
        assertEquals(MqttQos.EXACTLY_ONCE, MqttTopics.PRESENCE_QOS)
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.ContractEnvelopeTest"`
Expected: compilation FAILS (`CONTRACT_REVISION`, `contractRevision`, `serverContractRevision`, `RESPONSE_QOS` unresolved).

- [ ] **Step 3: Schema and vocabulary**

In `MqttSchema.kt`, directly under `const val VERSION = "rev2.1"`, add:

```kotlin
    /** The contract revision this build implements. Station 2 echoes its own in every reply. */
    const val CONTRACT_REVISION = "2026-10-01"
```

Replace `MqttVocabulary.kt` with:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

/**
 * rev2.1 `error`. A value class rather than an enum: an unknown code must pass through intact
 * rather than fail the parse — the SCRAM service returns its own `scram_*` codes.
 *
 * There is deliberately no `NextAction` type. Since contract 2026-10-01 `nextAction` is a stable
 * token, but the contract calls it "guidance, not an action to execute blindly": control flow
 * branches on `error` alone (see `unresolvedReasonOf`), and `nextAction` is kept for logs.
 */
@JvmInline
value class ErrorCode(val raw: String) {
    companion object {
        val INVALID_ENVELOPE = ErrorCode("invalid_envelope")
        /** A field name containing `password` was sent. A build defect, never a wrong password. */
        val PASSWORD_FIELD_FORBIDDEN = ErrorCode("password_field_forbidden")
        val OPERATOR_SESSION_INVALID = ErrorCode("operator_session_invalid")
        val ACTION_NOT_ALLOWED = ErrorCode("action_not_allowed")
        /** The same messageId already exists with a different body. Recover the original. */
        val MESSAGE_ID_CONFLICT = ErrorCode("message_id_conflict")
        /** A definite business rejection. `data` still carries the refreshed snapshot. */
        val REV2_REJECTED = ErrorCode("rev2_rejected")
        val CLIENT_UPGRADE_REQUIRED = ErrorCode("client_upgrade_required")
        /** Station 2 faulted mid-request. Retry the identical request with the same messageId. */
        val OUTCOME_UNCONFIRMED = ErrorCode("outcome_unconfirmed")
        val AUTHENTICATION_FAILED = ErrorCode("authentication_failed")
        val PURPOSE_NOT_ENABLED = ErrorCode("purpose_not_enabled")
        /** The collection changed before an ingredient decision. Re-read and re-review. */
        val COLLECTION_REVISION_CONFLICT = ErrorCode("collection_revision_conflict")
        /** The receipt belongs to another operator. A manager reconciles. */
        val RECEIPT_OWNER_MISMATCH = ErrorCode("receipt_owner_mismatch")
        /** A legacy receipt has no owner evidence. A manager reconciles. */
        val RECEIPT_RECOVERY_UNAVAILABLE = ErrorCode("receipt_recovery_unavailable")
        /** Recovery sealed this messageId: the original did not and now cannot execute. */
        val RECEIPT_SEALED = ErrorCode("receipt_sealed")
    }
}
```

- [ ] **Step 4: Response envelope**

In `ResponseEnvelope.kt`, replace the `data class ResponseEnvelope(` parameter list with:

```kotlin
data class ResponseEnvelope(
    val schemaVersion: String = "",
    /** Station 2's contract revision, e.g. `2026-10-01`. Pushes carry it too. */
    val contractRevision: String = "",
    val deviceId: String = "",
    val inResponseToMessageId: String = "",
    /** Uppercase hex SHA-256 of the exact request bytes Station 2 received. */
    val requestFingerprint: String = "",
    val receivedAtUtc: String? = null,
    val sentAtUtc: String? = null,
    val durationMs: Double? = null,
    val success: Boolean = false,
    /** Stable lowercase code; `""` on success. */
    val error: String = "",
    val operatorMessage: String = "",
    /** Guidance token (contract §10), e.g. `read_saved_state`. Logged, never branched on. */
    val nextAction: String = "",
    // Server pushes only.
    val messageId: String = "",
    val timestampUtc: String = "",
    val mode: String = "",
    val reason: String = "",
) {
```

Keep the existing body (`errorCode`, `displayMessage`) unchanged.

- [ ] **Step 5: Byte-based size guard**

Replace the exception class and the guard's size members in `OutboundGuard.kt`:

```kotlin
/** An outgoing message exceeded Station 2's request size limit. */
class OversizedPayloadException(val byteCount: Int) :
    IllegalArgumentException("Refusing to publish a $byteCount-byte request (limit ${OutboundGuard.MAX_PAYLOAD_BYTES})")
```

```kotlin
    /** Contract §2: at most 65,536 UTF-8 bytes — a multibyte name counts by its bytes. */
    const val MAX_PAYLOAD_BYTES = 65_536
```

```kotlin
    fun assertWithinSize(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_PAYLOAD_BYTES) throw OversizedPayloadException(bytes)
    }
```

In the KDoc above `object OutboundGuard`, change "a request over 65,536 characters" to "a request over 65,536 UTF-8 bytes". Then update the old tests. In `OutboundGuardTest.kt` (lines 53 and 58) and `RequestEnvelopeTest.kt` (line 80), replace `OutboundGuard.MAX_PAYLOAD_CHARS` with `OutboundGuard.MAX_PAYLOAD_BYTES`. Those tests use ASCII `"x"`, so the character count equals the byte count and their assertions still hold.

- [ ] **Step 6: Subscription QoS**

In `MqttTopics.kt`, add the import `import com.hivemq.client.mqtt.datatypes.MqttQos`, and inside the object, after `STATION_PRESENCE`:

```kotlin
    /** Contract §1: own `res/+` at QoS 1; station and own presence at QoS 2. */
    val RESPONSE_QOS: MqttQos = MqttQos.AT_LEAST_ONCE
    val PRESENCE_QOS: MqttQos = MqttQos.EXACTLY_ONCE
```

In `MqttRepositoryImpl.subscribeAndAnnounce`, add `.qos(...)` after each `.topicFilter(...)`:

```kotlin
        client.subscribeWith()
            .topicFilter(MqttTopics.responseWildcard(deviceId))
            .qos(MqttTopics.RESPONSE_QOS)
            .callback { publish -> handleIncomingResponse(publish.topic.toString(), publish.payloadAsBytes) }
            .send()
            .await()
        client.subscribeWith()
            .topicFilter(MqttTopics.STATION_PRESENCE)
            .qos(MqttTopics.PRESENCE_QOS)
            .callback { publish -> handleStationPresence(publish.payloadAsBytes) }
            .send()
            .await()
```

Do the same, with `.qos(MqttTopics.PRESENCE_QOS)`, on the own-presence subscription that follows.

- [ ] **Step 7: Record the server's contract revision; publish explicit UTF-8**

In `MqttRepository.kt`, add below `upgradeRequired`:

```kotlin
    /**
     * The `contractRevision` of the last parsed Station 2 message, or null before one arrives.
     * A value other than [com.mitas.ppnam.station2aa.data.mqtt.MqttSchema.CONTRACT_REVISION] is
     * logged; it does not block (the server enforces only the wire schema `rev2.1`).
     */
    val serverContractRevision: StateFlow<String?>
```

In `MqttRepositoryImpl.kt`, beside `_upgradeRequired`:

```kotlin
    private val _serverContractRevision = MutableStateFlow<String?>(null)
    override val serverContractRevision: StateFlow<String?> = _serverContractRevision.asStateFlow()
```

In `handleIncomingResponse`, directly after the `recordClockSkew(...)` line:

```kotlin
        envelope.contractRevision.takeIf { it.isNotBlank() }?.let { revision ->
            if (revision != MqttSchema.CONTRACT_REVISION && _serverContractRevision.value != revision) {
                Log.w(TAG, "Station 2 speaks contract $revision; this build implements ${MqttSchema.CONTRACT_REVISION}")
            }
            _serverContractRevision.value = revision
        }
```

In `request()`, change `val bytes = json.toByteArray()` to `val bytes = json.toByteArray(Charsets.UTF_8)`.

- [ ] **Step 8: Run the new and the touched tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.*"`
Expected: PASS, including `ContractEnvelopeTest` (6 tests), `OutboundGuardTest`, `RequestEnvelopeTest` and `ResponseEnvelopeTest`.

- [ ] **Step 9: Commit**

```bash
git add -A app/src
git commit -m "feat(mqtt): contract 2026-10-01 envelope, error codes, byte limit and QoS"
```

---

### Task 3: Full snapshot DTOs, verified against every example

**Files:**
- Modify: `data/mqtt/dto/Rev2GeneralMessages.kt` (rewrite)
- Modify (rename `Rev2GeneralSnapshot` → `Rev2Snapshot`): `domain/usecase/JobLookupUseCase.kt`, `test/.../Rev2GeneralWireShapeTest.kt`, `test/.../WireNullToleranceTest.kt`, `test/.../JobLookupUseCaseTest.kt`
- Test: create `data/mqtt/dto/Rev2SnapshotFixtureTest.kt`

**Interfaces:**
- Produces (all in `com.mitas.ppnam.station2aa.data.mqtt.dto`): `Rev2Snapshot`, `Rev2CommandResult(success, message, targetId, cycleId, errorCode)`, `Rev2Recovery(originalMessageId, outcome, result)`, `Rev2Capabilities`, `Rev2JobSummary(+mixProgress)`, `Rev2MixProgress`, `Rev2ActivePreparation`, `Rev2Job(+mode, activeSessionId, rajooMachineId, ingredientChoices, exceptionListRevision, collectionRevision, mixProgress)`, `Rev2Material(+originalRequired, remaining)`, `Rev2Preparation` (full), `Rev2Machine`, `Rev2IngredientChoice`, `Rev2CatalogEntry`, `Rev2CollectionException`, `Rev2RecoverRequest`.
- `Rev2GeneralRequest` is unchanged.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/dto/Rev2SnapshotFixtureTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rev2SnapshotFixtureTest {

    private fun dataOf(name: String): JsonObject =
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"].asJsonObject

    private fun snapshot(name: String): Rev2Snapshot = WireJson.gson.fromJson(dataOf(name), Rev2Snapshot::class.java)

    private fun declared(cls: Class<*>): Set<String> = cls.declaredFields.map { it.name }.toSet()

    /** Every key Station 2 sends must have a home in the DTO, so a contract addition is noticed. */
    private fun assertKeysDeclared(where: String, json: JsonObject, cls: Class<*>) {
        val missing = json.keySet() - declared(cls)
        assertTrue("$where: undeclared ${cls.simpleName} fields $missing", missing.isEmpty())
    }

    @Test
    fun `every snapshot key in every example is declared`() {
        val workflow = ContractFixtures.pairs().filter { it.startsWith("general") || it.startsWith("rajoo") || it == "final_capture" }
        for (name in workflow) {
            val data = JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"]
            if (data == null || data.isJsonNull) continue
            val obj = data.asJsonObject
            assertKeysDeclared(name, obj, Rev2Snapshot::class.java)
            obj["job"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { job ->
                assertKeysDeclared("$name.job", job, Rev2Job::class.java)
                job["materials"].asJsonArray.forEach { assertKeysDeclared("$name.job.materials", it.asJsonObject, Rev2Material::class.java) }
                job["mixProgress"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { mp ->
                    assertKeysDeclared("$name.mixProgress", mp, Rev2MixProgress::class.java)
                    mp["activePreparations"].asJsonArray.forEach { assertKeysDeclared("$name.activePreparations", it.asJsonObject, Rev2ActivePreparation::class.java) }
                }
            }
            obj["preparation"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { assertKeysDeclared("$name.preparation", it, Rev2Preparation::class.java) }
            obj["jobs"].asJsonArray.forEach { assertKeysDeclared("$name.jobs", it.asJsonObject, Rev2JobSummary::class.java) }
            obj["machines"].asJsonArray.forEach { assertKeysDeclared("$name.machines", it.asJsonObject, Rev2Machine::class.java) }
            obj["collectionExceptions"].asJsonArray.forEach { assertKeysDeclared("$name.collectionExceptions", it.asJsonObject, Rev2CollectionException::class.java) }
            obj["result"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { assertKeysDeclared("$name.result", it, Rev2CommandResult::class.java) }
        }
    }

    @Test
    fun `prepare 4 of 60 shows 4 active and 56 available to prepare`() {
        val s = snapshot("general_prepare")
        val mp = s.job!!.mixProgress!!
        assertEquals(60, mp.requiredMixes)
        assertEquals(4, mp.allocatedMixes)
        assertEquals(4, mp.activeMixes)
        assertEquals(56, mp.availableToPrepareMixes)
        assertEquals(60, mp.remainingToFinishMixes)
        assertEquals(1, mp.activePreparationCount)
        assertEquals(s.preparation!!.id, mp.activePreparations.single().id)
        assertEquals("Collecting", mp.activePreparations.single().stage)
        assertEquals(4, s.jobs.single().mixProgress!!.activeMixes)
        assertEquals("General", s.mode)
        assertTrue(s.capabilities!!.receiptRecovery)
    }

    @Test
    fun `an operator-added row has originalRequired zero and a server-attributed exception`() {
        val s = snapshot("general_add")
        val added = s.preparation!!.materials.last()
        assertEquals("ADDITIVE", added.code)
        assertEquals(0.0, added.originalRequired!!, 0.0)
        assertEquals(4.0, added.required, 0.0)
        val ex = s.commandExceptions.single()
        assertEquals("Ingredient added", ex.kind)
        assertEquals("scanner_1", ex.device)
        assertNull(ex.reviewedAtUtc)
        assertEquals(1, s.preparation!!.collectionRevision)
    }

    @Test
    fun `a manager review shows on the scanner as reviewed`() {
        val s = snapshot("general_read_reviewed")
        assertTrue(s.collectionExceptions.isNotEmpty())
        assertTrue(s.collectionExceptions.any { it.reviewedBy != null && it.reviewedAtUtc != null && it.reviewReason != null })
    }

    @Test
    fun `recovery outcomes parse with the nested original result`() {
        val committed = snapshot("general_recover_committed").recovery!!
        assertEquals("committed", committed.outcome)
        assertTrue(committed.result!!.success)
        val sealed = snapshot("general_recover_not_executed").recovery!!
        assertEquals("not_executed", sealed.outcome)
        assertEquals("receipt_sealed", sealed.result!!.errorCode)
        assertFalse(sealed.result!!.success)
    }

    @Test
    fun `Rajoo has no mix progress and full-job targets`() {
        val s = snapshot("rajoo_read")
        assertEquals("Rajoo", s.mode)
        assertNull(s.job!!.mixProgress)
        assertTrue(s.job!!.materials.all { it.perMix == 0.0 })
        assertNotNull(s.job!!.collectionRevision)
    }

    @Test
    fun `machines and catalog parse`() {
        val s = snapshot("general_lookup")
        val pair = s.machines.first { it.id == "DOL-MIX-01" }
        assertEquals("FixedPair", pair.kind)
        assertEquals("DOL-01", pair.pairId)
        assertEquals("OPTIONAL", s.requiredIngredientChoices.single().code)
        assertEquals(1, s.exceptionListRevision)
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2SnapshotFixtureTest"`
Expected: compilation FAILS (`Rev2Snapshot` unresolved).

- [ ] **Step 3: Rewrite the DTO file**

Replace `data/mqtt/dto/Rev2GeneralMessages.kt` entirely. New fields are appended after the existing ones, so the positional constructor calls in existing tests still compile.

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.dto

/**
 * rev2.1 workflow DTOs, contract revision 2026-10-01 (§8). General (`rev2_general_*`) and Rajoo
 * (`rev2_rajoo_*`) replies share one snapshot shape; [Rev2Snapshot.mode] says which.
 *
 * Every constructor parameter keeps a default: see [ResponseEnvelope] for why that is load-bearing.
 * Nullable fields default to null — WireJson prunes JSON nulls, so the default is what a null
 * becomes. Rev2SnapshotFixtureTest fails if Station 2 sends a field declared nowhere here.
 */

/** Request payload. Null fields are omitted on the wire. */
data class Rev2GeneralRequest(
    val action: String,
    /** `lookup` only: the Production Order number, digits only. */
    val jobCard: String? = null,
    /** `read` only: a JC or preparation id to include as detail. */
    val targetId: String? = null,
)

/** Contract §9: recover an unanswered command in the same family. Sent with a NEW messageId. */
data class Rev2RecoverRequest(
    val action: String = "recover",
    /** Optional selection; recovery uses the saved result target when present. */
    val targetId: String? = null,
    val originalMessageId: String,
    /** 64 uppercase hex: SHA-256 of the original request's exact UTF-8 bytes. */
    val originalRequestFingerprint: String,
)

data class Rev2Snapshot(
    val result: Rev2CommandResult? = null,
    val recovery: Rev2Recovery? = null,
    /** `General` or `Rajoo`. */
    val mode: String = "",
    val capabilities: Rev2Capabilities? = null,
    val jobs: List<Rev2JobSummary> = emptyList(),
    val job: Rev2Job? = null,
    val preparation: Rev2Preparation? = null,
    val preparations: List<Rev2Preparation> = emptyList(),
    val machines: List<Rev2Machine> = emptyList(),
    val exceptionListRevision: Int = 0,
    val ingredientExceptions: List<Rev2CatalogEntry> = emptyList(),
    val requiredIngredientChoices: List<Rev2CatalogEntry> = emptyList(),
    val collectionExceptions: List<Rev2CollectionException> = emptyList(),
    /** The subset of [collectionExceptions] created by this device's message (or the recovered original). */
    val commandExceptions: List<Rev2CollectionException> = emptyList(),
)

data class Rev2CommandResult(
    val success: Boolean = false,
    val message: String = "",
    val targetId: String? = null,
    /** Server-owned `CYC_…`/`RAJ_…` id when the operation started one. */
    val cycleId: String? = null,
    /** Omitted when null; e.g. `receipt_sealed` inside a not_executed recovery. */
    val errorCode: String? = null,
)

data class Rev2Recovery(
    val originalMessageId: String = "",
    /** `committed`, `rejected` or `not_executed`. */
    val outcome: String = "",
    val result: Rev2CommandResult? = null,
)

data class Rev2Capabilities(
    val operatorIngredientDecisions: Boolean = false,
    val receiptRecovery: Boolean = false,
    val managerReviewDesktopOnly: Boolean = false,
    val jobMixProgress: Boolean = false,
)

data class Rev2JobSummary(
    val id: String = "",
    val product: String = "",
    val closed: Boolean = false,
    val requiredMixes: Int = 0,
    /** Same values as the selected job's; null for Rajoo. */
    val mixProgress: Rev2MixProgress? = null,
)

/** Contract §8.1. All counters are nonnegative; **Active** does not mean a mixer is running. */
data class Rev2MixProgress(
    val requiredMixes: Int = 0,
    val allocatedMixes: Int = 0,
    val activeMixes: Int = 0,
    val availableToPrepareMixes: Int = 0,
    val remainingToFinishMixes: Int = 0,
    val collectedMixes: Int = 0,
    val confirmedMixes: Int = 0,
    val mixedMixes: Int = 0,
    val producedMixes: Int = 0,
    val activePreparationCount: Int = 0,
    val activePreparations: List<Rev2ActivePreparation> = emptyList(),
)

data class Rev2ActivePreparation(
    val id: String = "",
    val jobId: String = "",
    val mixCount: Int = 0,
    val stage: String = "",
    val mixed: Int = 0,
    val produced: Int = 0,
    val remainingToFinishMixes: Int = 0,
    val collectionRevision: Int = 0,
    val startedAtUtc: String? = null,
    val startedBy: String? = null,
    val mixerId: String? = null,
    val productionId: String? = null,
    val cycleId: String? = null,
)

data class Rev2Job(
    val id: String = "",
    val product: String = "",
    val unit: String = "",
    val overallQuantity: Double = 0.0,
    val scopeQuantity: Double = 0.0,
    val sapCompletedAtLookup: Double = 0.0,
    val outputPerMix: Double = 0.0,
    val requiredMixes: Int = 0,
    /** Kept by Station 2 for compatibility; prefer [mixProgress]. */
    val allocatedMixes: Int = 0,
    val capturedAtUtc: String? = null,
    val closed: Boolean = false,
    val materials: List<Rev2Material> = emptyList(),
    val mode: String = "",
    /** Rajoo only: the active `RAJ_…` session. */
    val activeSessionId: String? = null,
    val rajooMachineId: String? = null,
    /** Rajoo first-session choices; null for General. */
    val ingredientChoices: List<Rev2IngredientChoice>? = null,
    val exceptionListRevision: Int? = null,
    /** Rajoo decision guard. General decisions use the PREPARATION's revision, not this. */
    val collectionRevision: Int = 0,
    /** Null for Rajoo. */
    val mixProgress: Rev2MixProgress? = null,
)

/**
 * `required` is the effective target. `remaining` is sent by Station 2 as
 * `max(0, required - collected)`; the domain layer derives the same value.
 */
data class Rev2Material(
    val code: String = "",
    val name: String = "",
    val unit: String = "",
    val perMix: Double = 0.0,
    val required: Double = 0.0,
    val collected: Double = 0.0,
    /** Excluded (initial choice, omit, or substituted away): shown, never collected. */
    val excluded: Boolean = false,
    /** Original scope target; 0 for an operator-added row; null when a legacy baseline is unknown. */
    val originalRequired: Double? = null,
    val remaining: Double = 0.0,
)

data class Rev2Preparation(
    val id: String = "",
    val jobId: String = "",
    val mixCount: Int = 0,
    val mixed: Int = 0,
    val produced: Int = 0,
    /** Enum name: Collecting, AwaitingConfirmation, ReadyForMixer, Mixing, … Completed, Cancelled. */
    val stage: String = "",
    val startedAtUtc: String? = null,
    /** Legacy evidence only; never send it. */
    val includeTackifier: Boolean? = null,
    val ingredientChoices: List<Rev2IngredientChoice> = emptyList(),
    val exceptionListRevision: Int? = null,
    /** The guard for General ingredient decisions. */
    val collectionRevision: Int = 0,
    val mixerId: String? = null,
    val productionId: String? = null,
    val cycleId: String? = null,
    val confirmedAtUtc: String? = null,
    val startedBy: String? = null,
    val materials: List<Rev2Material> = emptyList(),
)

data class Rev2Machine(
    val id: String = "",
    val name: String = "",
    val area: String = "",
    /** Blank until an Admin commissions it. */
    val code: String = "",
    val enabled: Boolean = false,
    /** `FixedPair`, `Main`, `Mixer6` or `Rajoo`. */
    val kind: String = "",
    val isProduction: Boolean = false,
    val pairId: String? = null,
    val destinations: List<String> = emptyList(),
)

data class Rev2IngredientChoice(
    val code: String = "",
    val include: Boolean = true,
)

/** A Settings exception-catalog row (§7). */
data class Rev2CatalogEntry(
    val code: String = "",
    val description: String = "",
    val note: String = "",
)

/** An operational collection exception (§6). Review fields are null while open. */
data class Rev2CollectionException(
    val id: String = "",
    val timestampUtc: String = "",
    val operatorId: String = "",
    val device: String = "",
    val messageId: String = "",
    val jobId: String = "",
    val batchId: String? = null,
    val sessionId: String? = null,
    val kind: String = "",
    val details: String = "",
    val reason: String = "",
    val beforeJson: String = "",
    val afterJson: String = "",
    val reviewedAtUtc: String? = null,
    val reviewedBy: String? = null,
    val reviewReason: String? = null,
)
```

- [ ] **Step 4: Rename the snapshot type everywhere**

Run in Git Bash from the repo root:

```bash
grep -rl "Rev2GeneralSnapshot" app/src | xargs sed -i 's/Rev2GeneralSnapshot/Rev2Snapshot/g'
grep -rn "Rev2GeneralSnapshot" app/src   # expect no output
```

- [ ] **Step 5: Run the DTO, wire-shape and use-case tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.*" --tests "com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCaseTest"`
Expected: PASS, including 7 tests in `Rev2SnapshotFixtureTest`.

If `every snapshot key in every example is declared` names an undeclared field, add it to the named DTO with a default value. Do not remove the check.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "feat(mqtt): full rev2.1 2026-10-01 snapshot DTOs checked against Station 2 examples"
```

---

### Task 4: Required / Active / Available to prepare / Finished on the job list and detail

**Files:**
- Modify: `domain/model/Job.kt`
- Modify: `domain/usecase/JobLookupUseCase.kt:97-117` (`toDomain`)
- Modify: `ui/joblookup/JobFormat.kt`
- Modify: `ui/joblookup/JobLookupScreen.kt:155-160`
- Modify: `ui/joblookup/JobDetailScreen.kt:53-54` and the preparations section
- Test: `test/.../domain/usecase/JobLookupUseCaseTest.kt` (add tests), `test/.../ui/joblookup/JobFormatTest.kt` (add tests)

**Interfaces:**
- Consumes: `Rev2Snapshot`, `Rev2MixProgress` (Task 3), `ContractFixtures` (Task 1).
- Produces: `MixProgress(requiredMixes, allocatedMixes, activeMixes, availableToPrepareMixes, remainingToFinishMixes, collectedMixes, confirmedMixes, mixedMixes, producedMixes, activePreparations: List<ActivePreparation>)`.
- Produces: `ActivePreparation(id, mixCount, stage, mixed, produced, remainingToFinishMixes)`.
- Produces: `JobSummary.mixProgress: MixProgress?`, `JobDetail.mixProgress: MixProgress?`, `JobMaterial.originalRequired: Double?`.
- Produces: `MixProgress.countsLine(): String`, `ActivePreparation.summaryLine(): String`.

- [ ] **Step 1: Write the failing tests**

Append to `JobLookupUseCaseTest` (class body). Add the imports `com.mitas.ppnam.station2aa.contract.ContractFixtures`, `com.mitas.ppnam.station2aa.data.mqtt.WireJson` and `com.google.gson.JsonParser`.

```kotlin
    private fun fixtureSnapshot(name: String): Rev2Snapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"],
        Rev2Snapshot::class.java,
    )

    @Test
    fun `job mix progress maps onto the list and the detail`() = runTest {
        stub(MqttOutcome.Accepted(fixtureSnapshot("general_prepare")))
        val loaded = useCase.read("1") as JobLookupResult.Loaded
        val detail = loaded.snapshot.detail!!.mixProgress!!
        assertEquals(60, detail.requiredMixes)
        assertEquals(4, detail.activeMixes)
        assertEquals(56, detail.availableToPrepareMixes)
        assertEquals(0, detail.producedMixes)
        assertEquals("Collecting", detail.activePreparations.single().stage)
        assertEquals(4, detail.activePreparations.single().mixCount)
        assertEquals(56, loaded.snapshot.jobs.single().mixProgress!!.availableToPrepareMixes)
    }

    @Test
    fun `a reply without mix progress maps to null, not zeros`() = runTest {
        stub(MqttOutcome.Accepted(snapshot))
        val loaded = useCase.read("510019068") as JobLookupResult.Loaded
        assertNull(loaded.snapshot.detail!!.mixProgress)
        assertNull(loaded.snapshot.jobs.single().mixProgress)
    }
```

Append to `JobFormatTest`:

```kotlin
    @Test
    fun `mix progress reads Required, Active, Available to prepare, Finished`() {
        val mp = MixProgress(
            requiredMixes = 60, allocatedMixes = 4, activeMixes = 4, availableToPrepareMixes = 56,
            remainingToFinishMixes = 60, collectedMixes = 0, confirmedMixes = 0, mixedMixes = 0,
            producedMixes = 0, activePreparations = emptyList(),
        )
        assertEquals("Required 60 · Active 4 · Available to prepare 56 · Finished 0", mp.countsLine())
    }

    @Test
    fun `an active preparation line shows its count, stage and progress`() {
        val prep = ActivePreparation("PREP_1", mixCount = 4, stage = "ReadyForMixer", mixed = 1, produced = 0, remainingToFinishMixes = 4)
        assertEquals("4 mixes · ReadyForMixer · 1 mixed · 0 finished", prep.summaryLine())
    }
```

Add the imports `com.mitas.ppnam.station2aa.domain.model.MixProgress` and `com.mitas.ppnam.station2aa.domain.model.ActivePreparation` to `JobFormatTest`.

- [ ] **Step 2: Run them and see them fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCaseTest" --tests "com.mitas.ppnam.station2aa.ui.joblookup.JobFormatTest"`
Expected: compilation FAILS (`mixProgress`, `MixProgress` unresolved).

- [ ] **Step 3: Domain model**

In `domain/model/Job.kt`:
- Append `val mixProgress: MixProgress? = null,` as the last parameter of `JobSummary`.
- Append `val originalRequired: Double? = null,` as the last parameter of `JobMaterial`.
- Append `val mixProgress: MixProgress? = null,` as the last parameter of `JobDetail`.

Then add these two classes at the end of the file:

```kotlin
/** Contract §8.1 job totals — the same projection the desktop shows. Server-computed; never derived here. */
data class MixProgress(
    val requiredMixes: Int,
    val allocatedMixes: Int,
    val activeMixes: Int,
    val availableToPrepareMixes: Int,
    val remainingToFinishMixes: Int,
    val collectedMixes: Int,
    val confirmedMixes: Int,
    val mixedMixes: Int,
    val producedMixes: Int,
    val activePreparations: List<ActivePreparation>,
)

/** A saved preparation still awaiting production finish — resume it by `read` with [id]. */
data class ActivePreparation(
    val id: String,
    val mixCount: Int,
    val stage: String,
    val mixed: Int,
    val produced: Int,
    val remainingToFinishMixes: Int,
)
```

- [ ] **Step 4: Mapping**

In `JobLookupUseCase.kt`, replace `private fun Rev2Snapshot.toDomain()` and add the helper below it. Add the imports for `MixProgress`, `ActivePreparation` and `Rev2MixProgress`.

```kotlin
private fun Rev2Snapshot.toDomain(): JobLookupSnapshot = JobLookupSnapshot(
    jobs = jobs.map { JobSummary(it.id, it.product, it.requiredMixes, it.closed, it.mixProgress?.toDomain()) },
    detail = job?.let { job ->
        JobDetail(
            jobCard = job.id,
            product = job.product,
            unit = job.unit,
            outputPerMix = job.outputPerMix,
            requiredMixes = job.requiredMixes,
            allocatedMixes = job.allocatedMixes,
            capturedAtUtc = job.capturedAtUtc?.let { runCatching { Instant.parse(it) }.getOrNull() },
            closed = job.closed,
            materials = job.materials.map {
                JobMaterial(it.code, it.name, it.unit, it.perMix, it.required, it.collected, it.excluded, it.originalRequired)
            },
            preparations = preparations.filter { it.jobId == job.id }.map {
                JobPreparation(it.id, it.mixCount, it.mixed, it.produced, it.stage)
            },
            mixProgress = job.mixProgress?.toDomain(),
        )
    },
)

private fun Rev2MixProgress.toDomain(): MixProgress = MixProgress(
    requiredMixes = requiredMixes,
    allocatedMixes = allocatedMixes,
    activeMixes = activeMixes,
    availableToPrepareMixes = availableToPrepareMixes,
    remainingToFinishMixes = remainingToFinishMixes,
    collectedMixes = collectedMixes,
    confirmedMixes = confirmedMixes,
    mixedMixes = mixedMixes,
    producedMixes = producedMixes,
    activePreparations = activePreparations.map {
        ActivePreparation(it.id, it.mixCount, it.stage, it.mixed, it.produced, it.remainingToFinishMixes)
    },
)
```

- [ ] **Step 5: Formatters**

Append to `ui/joblookup/JobFormat.kt`, with the imports `MixProgress` and `ActivePreparation`:

```kotlin
/** Contract §8.1's required labels, in its order. */
internal fun MixProgress.countsLine(): String =
    "Required $requiredMixes · Active $activeMixes · Available to prepare $availableToPrepareMixes · Finished $producedMixes"

internal fun ActivePreparation.summaryLine(): String =
    "$mixCount ${if (mixCount == 1) "mix" else "mixes"} · $stage · $mixed mixed · $produced finished"
```

- [ ] **Step 6: Run the tests and see them pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 7: Job list row**

In `JobLookupScreen.kt`, replace the `buildString { … }` block inside the row's second `Text` (lines 155-160) with:

```kotlin
                                buildString {
                                    val progress = job.mixProgress
                                    if (progress != null) append(progress.countsLine())
                                    else append("${job.requiredMixes} ${if (job.requiredMixes == 1) "mix" else "mixes"}")
                                    if (job.closed) append(" · closed")
                                },
```

- [ ] **Step 8: Job detail**

In `JobDetailScreen.kt`, replace the two rows `LabelValueRow("Required mixes", …)` and `LabelValueRow("Prepared mixes", …)` with:

```kotlin
                        val progress = detail.mixProgress
                        if (progress != null) {
                            LabelValueRow("Required", progress.requiredMixes.toString())
                            LabelValueRow("Active", progress.activeMixes.toString())
                            LabelValueRow("Available to prepare", progress.availableToPrepareMixes.toString())
                            LabelValueRow("Finished", progress.producedMixes.toString())
                        } else {
                            LabelValueRow("Required mixes", detail.requiredMixes.toString())
                            LabelValueRow("Prepared mixes", detail.allocatedMixes.toString())
                        }
```

Directly before the `if (detail.preparations.isNotEmpty())` block, add the active-preparations section:

```kotlin
                val active = detail.mixProgress?.activePreparations.orEmpty()
                if (active.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Active preparations", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    }
                    items(active, key = { "active-${it.id}" }) { prep ->
                        ListItem(
                            headlineContent = { Text(prep.id, color = TextPrimary) },
                            supportingContent = { Text(prep.summaryLine(), color = TextMuted) },
                        )
                    }
                }
```

`summaryLine` is overloaded by receiver type (`JobPreparation` and `ActivePreparation`), so no rename is needed.

- [ ] **Step 9: Build and run the whole suite**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline` and then `.\gradlew.bat :app:assembleDebug --offline`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add -A app/src
git commit -m "feat(jobs): show Required/Active/Available to prepare/Finished and active PREPs"
```

---

### Task 5: The durable command outbox

**Files:**
- Create: `data/mqtt/outbox/PendingCommand.kt`
- Create: `data/mqtt/outbox/CommandOutbox.kt`
- Test: create `data/mqtt/outbox/FileCommandOutboxTest.kt`

**Interfaces:**
- Consumes: `RequestFingerprint.of` (Task 1).
- Produces: `enum class PendingStatus { Unresolved, ManagerReconcile }`.
- Produces: `data class PendingCommand(messageId, requestType, responseType, action, targetId: String?, payload: String, fingerprint, operatorId, sessionId, createdAtUtc, status)` with `val payloadBytes: ByteArray`.
- Produces: `interface CommandOutbox { val commands: StateFlow<List<PendingCommand>>; fun save(c); fun remove(messageId); fun markManagerReconcile(messageId) }`.
- Produces: `class InMemoryCommandOutbox : CommandOutbox` and `class FileCommandOutbox(dir: File, gson: Gson = Gson()) : CommandOutbox`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/outbox/FileCommandOutboxTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.outbox

import com.mitas.ppnam.station2aa.data.mqtt.RequestFingerprint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileCommandOutboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun command(id: String = "11111111-1111-1111-1111-111111111111"): PendingCommand {
        // Non-ASCII and escapes on purpose: the payload must survive a disk round trip byte-for-byte.
        val payload = """{"action":"capture","code":"TAG-é\"x","schemaVersion":"rev2.1","messageId":"$id"}"""
        return PendingCommand(
            messageId = id,
            requestType = "rev2_general_requested",
            responseType = "rev2_general_result",
            action = "capture",
            targetId = "PREP_1",
            payload = payload,
            fingerprint = RequestFingerprint.of(payload.toByteArray(Charsets.UTF_8)),
            operatorId = "OP-1",
            sessionId = "S-1",
            createdAtUtc = "2026-10-05T08:00:00.000000Z",
        )
    }

    @Test
    fun `a new outbox on the same directory reloads the identical command`() {
        val dir = tmp.newFolder("outbox")
        val original = command()
        FileCommandOutbox(dir).save(original)

        val reloaded = FileCommandOutbox(dir).commands.value.single()
        assertEquals(original, reloaded)
        assertArrayEquals(original.payloadBytes, reloaded.payloadBytes)
        assertEquals(PendingStatus.Unresolved, reloaded.status)
    }

    @Test
    fun `remove deletes the file`() {
        val dir = tmp.newFolder("outbox")
        val outbox = FileCommandOutbox(dir)
        outbox.save(command())
        outbox.remove(command().messageId)
        assertTrue(outbox.commands.value.isEmpty())
        assertTrue(FileCommandOutbox(dir).commands.value.isEmpty())
    }

    @Test
    fun `markManagerReconcile persists`() {
        val dir = tmp.newFolder("outbox")
        FileCommandOutbox(dir).apply { save(command()); markManagerReconcile(command().messageId) }
        assertEquals(PendingStatus.ManagerReconcile, FileCommandOutbox(dir).commands.value.single().status)
    }

    @Test
    fun `saving the same messageId twice keeps one entry`() {
        val outbox = FileCommandOutbox(tmp.newFolder("outbox"))
        outbox.save(command())
        outbox.save(command())
        assertEquals(1, outbox.commands.value.size)
    }

    @Test
    fun `an unreadable file becomes a ManagerReconcile entry`() {
        val dir = tmp.newFolder("outbox")
        File(dir, "22222222-2222-2222-2222-222222222222.json").writeText("{not json")
        val entry = FileCommandOutbox(dir).commands.value.single()
        assertEquals("22222222-2222-2222-2222-222222222222", entry.messageId)
        assertEquals(PendingStatus.ManagerReconcile, entry.status)
    }

    @Test
    fun `a payload that no longer matches its fingerprint becomes ManagerReconcile`() {
        val dir = tmp.newFolder("outbox")
        FileCommandOutbox(dir).save(command().copy(fingerprint = "0".repeat(64)))
        assertEquals(PendingStatus.ManagerReconcile, FileCommandOutbox(dir).commands.value.single().status)
    }

    @Test
    fun `a leftover tmp file is ignored and cleaned up`() {
        val dir = tmp.newFolder("outbox")
        val stray = File(dir, "33333333-3333-3333-3333-333333333333.json.tmp").apply { writeText("{}") }
        assertTrue(FileCommandOutbox(dir).commands.value.isEmpty())
        assertTrue(!stray.exists())
    }

    @Test
    fun `the in-memory outbox behaves the same for save and remove`() {
        val outbox = InMemoryCommandOutbox()
        outbox.save(command())
        outbox.markManagerReconcile(command().messageId)
        assertEquals(PendingStatus.ManagerReconcile, outbox.commands.value.single().status)
        outbox.remove(command().messageId)
        assertTrue(outbox.commands.value.isEmpty())
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.outbox.FileCommandOutboxTest"`
Expected: compilation FAILS (`FileCommandOutbox` unresolved).

- [ ] **Step 3: The model**

`app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/outbox/PendingCommand.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.outbox

enum class PendingStatus {
    /** Outcome unknown: retry identically, or sign in and recover. */
    Unresolved,

    /** Station 2 cannot attribute the receipt (or the saved copy is damaged): a manager reconciles. */
    ManagerReconcile,
}

/**
 * A mutation whose outcome is not yet known (contract §2/§9). [payload] is the exact JSON that was
 * published; a retry republishes exactly these bytes with the same [messageId], and `recover`
 * quotes [fingerprint]. Defaults exist only so Gson can construct it.
 */
data class PendingCommand(
    val messageId: String = "",
    /** The request family suffix, e.g. `rev2_general_requested`. */
    val requestType: String = "",
    val responseType: String = "",
    val action: String = "",
    val targetId: String? = null,
    val payload: String = "",
    val fingerprint: String = "",
    /** The server operator who sent it; only that operator may recover it. */
    val operatorId: String = "",
    /** The session it was sent with; an identical retry is only meaningful in that session. */
    val sessionId: String = "",
    val createdAtUtc: String = "",
    val status: PendingStatus = PendingStatus.Unresolved,
) {
    val payloadBytes: ByteArray get() = payload.toByteArray(Charsets.UTF_8)
}
```

- [ ] **Step 4: The outbox**

`app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/outbox/CommandOutbox.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.outbox

import android.util.Log
import com.google.gson.Gson
import com.mitas.ppnam.station2aa.data.mqtt.RequestFingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Unanswered commands, kept until Station 2's outcome is known. `save` must finish before publishing. */
interface CommandOutbox {
    val commands: StateFlow<List<PendingCommand>>
    fun save(command: PendingCommand)
    fun remove(messageId: String)
    fun markManagerReconcile(messageId: String)
}

/** For tests and as the transport's constructor default; production binds [FileCommandOutbox]. */
class InMemoryCommandOutbox : CommandOutbox {
    private val _commands = MutableStateFlow<List<PendingCommand>>(emptyList())
    override val commands: StateFlow<List<PendingCommand>> = _commands.asStateFlow()
    override fun save(command: PendingCommand) =
        _commands.update { list -> list.filterNot { it.messageId == command.messageId } + command }
    override fun remove(messageId: String) = _commands.update { list -> list.filterNot { it.messageId == messageId } }
    override fun markManagerReconcile(messageId: String) = _commands.update { list ->
        list.map { if (it.messageId == messageId) it.copy(status = PendingStatus.ManagerReconcile) else it }
    }
}

/**
 * One JSON file per command in [dir], named by messageId (a UUID, so always a safe file name).
 * Writes go to a temp file, are fsynced, then atomically moved over the target: a crash leaves the
 * old file or the new one, never half of one. A file that cannot be read, or whose payload no
 * longer matches its fingerprint, is surfaced as [PendingStatus.ManagerReconcile] — never dropped,
 * because it may stand for a bag that was physically captured.
 */
class FileCommandOutbox(
    private val dir: File,
    private val gson: Gson = Gson(),
) : CommandOutbox {

    private val lock = Any()
    private val _commands = MutableStateFlow(load())
    override val commands: StateFlow<List<PendingCommand>> = _commands.asStateFlow()

    override fun save(command: PendingCommand) {
        synchronized(lock) {
            write(command)
            _commands.update { list -> list.filterNot { it.messageId == command.messageId } + command }
        }
    }

    override fun remove(messageId: String) {
        synchronized(lock) {
            fileFor(messageId).delete()
            _commands.update { list -> list.filterNot { it.messageId == messageId } }
        }
    }

    override fun markManagerReconcile(messageId: String) {
        synchronized(lock) {
            val current = _commands.value.firstOrNull { it.messageId == messageId } ?: return
            save(current.copy(status = PendingStatus.ManagerReconcile))
        }
    }

    private fun fileFor(messageId: String) = File(dir, "$messageId$SUFFIX")

    private fun write(command: PendingCommand) {
        dir.mkdirs()
        val target = fileFor(command.messageId)
        val temp = File(dir, "${command.messageId}$SUFFIX$TEMP_SUFFIX")
        FileOutputStream(temp).use { out ->
            out.write(gson.toJson(command).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load(): List<PendingCommand> {
        val files = dir.listFiles() ?: return emptyList()
        // A temp file is a save that never completed, so its command was never published.
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }
        return files.filter { it.name.endsWith(SUFFIX) }.map { file ->
            val id = file.name.removeSuffix(SUFFIX)
            val parsed = try {
                gson.fromJson(file.readText(Charsets.UTF_8), PendingCommand::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Unreadable outbox entry $id", e)
                null
            }
            when {
                parsed == null || parsed.messageId != id ->
                    PendingCommand(messageId = id, status = PendingStatus.ManagerReconcile)
                RequestFingerprint.of(parsed.payloadBytes) != parsed.fingerprint ->
                    parsed.copy(status = PendingStatus.ManagerReconcile)
                else -> parsed
            }
        }.sortedBy { it.createdAtUtc }
    }

    private companion object {
        const val TAG = "CommandOutbox"
        const val SUFFIX = ".json"
        const val TEMP_SUFFIX = ".tmp"
    }
}
```

The temp name is `<id>.json.tmp`, so it ends with `.tmp` and not `.json`. That keeps it out of the `SUFFIX` filter.

- [ ] **Step 5: Run it and see it pass**

Run the Step 2 command. Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "feat(mqtt): durable file-backed outbox for unanswered commands"
```

---

### Task 6: `sendCommand` and `retryCommand` in the transport

**Files:**
- Create: `data/mqtt/outbox/CommandOutcome.kt`
- Modify: `domain/repository/MqttRepository.kt`
- Modify: `data/mqtt/MqttRepositoryImpl.kt` (constructor, `request()`, new methods)
- Modify: `di/AppModule.kt`
- Test: create `data/mqtt/CommandTransportTest.kt`; create `data/mqtt/outbox/CommandOutcomeTest.kt`

**Interfaces:**
- Consumes: `CommandOutbox`, `PendingCommand`, `PendingStatus` (Task 5); `RequestFingerprint` (Task 1); the `ErrorCode` constants (Task 2).
- Produces:
  ```kotlin
  sealed interface CommandOutcome<out T> {
      data class Settled<T>(val outcome: MqttOutcome<T>) : CommandOutcome<T>
      data class Unresolved<T>(val command: PendingCommand, val reason: UnresolvedReason, val body: T?, val operatorMessage: String?) : CommandOutcome<T>
  }
  enum class UnresolvedReason { RetryIdentical, LoginThenRecover, Recover, ManagerReconcile }
  fun unresolvedReasonOf(outcome: MqttOutcome<*>): UnresolvedReason?   // null = settled
  ```
- Produces: `MqttRepository.sendCommand(requestType: String, responseType: String, payload: Any, responseClass: Class<T>): CommandOutcome<T>` and `MqttRepository.retryCommand(command: PendingCommand, responseClass: Class<T>): CommandOutcome<T>`.

- [ ] **Step 1: Write the failing classification test**

`app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/outbox/CommandOutcomeTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.outbox

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandOutcomeTest {

    private fun rejected(code: String?) = MqttOutcome.Rejected<Unit>(null, code?.let(::ErrorCode), null)

    @Test
    fun `accepted and definite rejections settle`() {
        assertNull(unresolvedReasonOf(MqttOutcome.Accepted(Unit)))
        listOf(
            "rev2_rejected", "collection_revision_conflict", "action_not_allowed", "invalid_envelope",
            "password_field_forbidden", "client_upgrade_required", "receipt_sealed",
        ).forEach { assertNull(it, unresolvedReasonOf(rejected(it))) }
    }

    @Test
    fun `uncertain outcomes stay unresolved with the contract's next step`() {
        assertEquals(UnresolvedReason.RetryIdentical, unresolvedReasonOf(MqttOutcome.NoResponse(FailureKind.Timeout)))
        assertEquals(UnresolvedReason.RetryIdentical, unresolvedReasonOf(MqttOutcome.NoResponse(FailureKind.NotConnected)))
        assertEquals(UnresolvedReason.RetryIdentical, unresolvedReasonOf(MqttOutcome.NoResponse(FailureKind.MalformedResponse)))
        assertEquals(UnresolvedReason.RetryIdentical, unresolvedReasonOf(rejected("outcome_unconfirmed")))
        assertEquals(UnresolvedReason.LoginThenRecover, unresolvedReasonOf(rejected("operator_session_invalid")))
        assertEquals(UnresolvedReason.Recover, unresolvedReasonOf(rejected("message_id_conflict")))
        assertEquals(UnresolvedReason.ManagerReconcile, unresolvedReasonOf(rejected("receipt_owner_mismatch")))
        assertEquals(UnresolvedReason.ManagerReconcile, unresolvedReasonOf(rejected("receipt_recovery_unavailable")))
    }

    @Test
    fun `an unknown or missing error code is never assumed settled`() {
        assertEquals(UnresolvedReason.Recover, unresolvedReasonOf(rejected("some_future_code")))
        assertEquals(UnresolvedReason.Recover, unresolvedReasonOf(rejected(null)))
    }
}
```

- [ ] **Step 2: Write the failing transport test**

`app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/CommandTransportTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutcome
import com.mitas.ppnam.station2aa.data.mqtt.outbox.InMemoryCommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CommandTransportTest {

    data class CaptureBody(val action: String = "capture", val targetId: String = "PREP_1", val code: String = "TAG-1")
    data class Body(val value: String = "")

    private val device = "scanner_1"
    private lateinit var repo: MqttRepositoryImpl
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var outbox: InMemoryCommandOutbox
    private val published = mutableListOf<Pair<String, ByteArray>>()
    private var outboxSizeAtPublish = -1

    @Before
    fun setup() {
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(OperatorSession("S-1", "OP-1", "Op", "Worker"))
        outbox = InMemoryCommandOutbox()
        val identity = mock<DeviceIdentity>()
        whenever(identity.deviceId()).thenReturn(device)
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = sessionHolder,
            deviceIdentity = identity,
            commandOutbox = outbox,
        )
        published.clear()
        repo.publishFn = { topic, bytes -> outboxSizeAtPublish = outbox.commands.value.size; published += topic to bytes }
        setConnected(true)
    }

    private fun setConnected(connected: Boolean) {
        val field = MqttRepositoryImpl::class.java.getDeclaredField("_connectionState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(repo) as MutableStateFlow<MqttConnectionState>).value =
            if (connected) MqttConnectionState.CONNECTED else MqttConnectionState.DISCONNECTED
    }

    private fun idOf(index: Int) = JsonParser.parseString(String(published[index].second)).asJsonObject["messageId"].asString

    private fun reply(id: String, success: Boolean = true, error: String = "", data: String? = """{"value":"ok"}""") {
        val dataPart = if (data == null) "" else ""","data":$data"""
        val json = """{"schemaVersion":"rev2.1","contractRevision":"2026-10-01","deviceId":"$device",""" +
            """"inResponseToMessageId":"$id","requestFingerprint":"","success":$success,"error":"$error",""" +
            """"operatorMessage":"","nextAction":"read_saved_state"$dataPart}"""
        repo.handleIncomingResponse("PPNAM/station_2/$device/res/rev2_general_result", json.toByteArray())
    }

    private fun hint() {
        val json = """{"schemaVersion":"rev2.1","contractRevision":"2026-10-01","deviceId":"$device",""" +
            """"messageId":"hint-1","timestampUtc":"2026-10-01T08:00:00.000000Z","mode":"General","reason":"capture","nextAction":"read"}"""
        repo.handleIncomingResponse("PPNAM/station_2/$device/res/active_job_cards_invalidated", json.toByteArray())
    }

    private suspend fun kotlinx.coroutines.test.TestScope.send() =
        async { repo.sendCommand("rev2_general_requested", "rev2_general_result", CaptureBody(), Body::class.java) }
            .also { runCurrent() }

    @Test
    fun `the command is persisted before it is published, with the fingerprint of the exact bytes`() = runTest {
        val call = send()
        assertEquals(1, outboxSizeAtPublish)
        val saved = outbox.commands.value.single()
        assertArrayEquals(published.single().second, saved.payloadBytes)
        assertEquals(RequestFingerprint.of(published.single().second), saved.fingerprint)
        assertEquals("OP-1", saved.operatorId)
        assertEquals("S-1", saved.sessionId)
        assertEquals("capture", saved.action)
        assertEquals("PREP_1", saved.targetId)
        reply(idOf(0))
        call.await()
    }

    @Test
    fun `success settles and leaves the outbox`() = runTest {
        val call = send()
        reply(idOf(0))
        val result = call.await()
        assertTrue(result is CommandOutcome.Settled && result.outcome is MqttOutcome.Accepted)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `a definite business rejection settles with its snapshot`() = runTest {
        val call = send()
        reply(idOf(0), success = false, error = "rev2_rejected", data = """{"value":"snapshot"}""")
        val result = call.await() as CommandOutcome.Settled
        assertEquals("snapshot", (result.outcome as MqttOutcome.Rejected).body!!.value)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `no reply stays unresolved for an identical retry`() = runTest {
        val result = send().await()   // virtual time runs the timeout out
        assertEquals(UnresolvedReason.RetryIdentical, (result as CommandOutcome.Unresolved).reason)
        assertEquals(1, outbox.commands.value.size)
    }

    @Test
    fun `outcome_unconfirmed and session loss stay unresolved`() = runTest {
        val first = send()
        reply(idOf(0), success = false, error = "outcome_unconfirmed", data = null)
        assertEquals(UnresolvedReason.RetryIdentical, (first.await() as CommandOutcome.Unresolved).reason)

        val second = send()
        reply(idOf(1), success = false, error = "operator_session_invalid", data = null)
        assertEquals(UnresolvedReason.LoginThenRecover, (second.await() as CommandOutcome.Unresolved).reason)
        assertEquals(2, outbox.commands.value.size)
    }

    @Test
    fun `an unattributable receipt is marked for a manager`() = runTest {
        val call = send()
        reply(idOf(0), success = false, error = "receipt_owner_mismatch", data = null)
        assertEquals(UnresolvedReason.ManagerReconcile, (call.await() as CommandOutcome.Unresolved).reason)
        assertEquals(PendingStatus.ManagerReconcile, outbox.commands.value.single().status)
    }

    @Test
    fun `retry republishes the identical bytes and message id`() = runTest {
        send().await()   // times out
        val command = outbox.commands.value.single()
        val retry = async { repo.retryCommand(command, Body::class.java) }
        runCurrent()
        assertArrayEquals(published[0].second, published[1].second)
        reply(command.messageId)
        assertTrue(retry.await() is CommandOutcome.Settled)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `retry after a re-login does not publish and asks for recovery`() = runTest {
        send().await()
        sessionHolder.set(OperatorSession("S-2", "OP-1", "Op", "Worker"))
        val result = repo.retryCommand(outbox.commands.value.single(), Body::class.java)
        assertEquals(UnresolvedReason.LoginThenRecover, (result as CommandOutcome.Unresolved).reason)
        assertEquals(1, published.size)
    }

    @Test
    fun `not connected sends nothing and persists nothing`() = runTest {
        setConnected(false)
        val result = repo.sendCommand("rev2_general_requested", "rev2_general_result", CaptureBody(), Body::class.java)
        assertEquals(MqttOutcome.NoResponse(FailureKind.NotConnected), (result as CommandOutcome.Settled).outcome)
        assertTrue(outbox.commands.value.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `a hint arriving mid-command does not resolve it`() = runTest {
        repo.setServerPushHandler { _, _, _ -> }
        val call = send()
        hint()
        runCurrent()
        assertTrue(call.isActive)
        assertEquals(1, outbox.commands.value.size)
        reply(idOf(0))
        assertTrue(call.await() is CommandOutcome.Settled)
    }

    @Test
    fun `a duplicate reply after settlement changes nothing`() = runTest {
        val call = send()
        reply(idOf(0))
        call.await()
        reply(idOf(0))   // QoS 1 redelivery
        assertTrue(outbox.commands.value.isEmpty())
    }
}
```

- [ ] **Step 3: Run both tests and see them fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.CommandTransportTest" --tests "com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutcomeTest"`
Expected: compilation FAILS (`CommandOutcome`, `sendCommand`, `commandOutbox` unresolved).

- [ ] **Step 4: Outcome types and classification**

`app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/outbox/CommandOutcome.kt`:

```kotlin
package com.mitas.ppnam.station2aa.data.mqtt.outbox

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome

/** The result of a mutating command (contract §9). */
sealed interface CommandOutcome<out T> {
    /**
     * Station 2 decided ([MqttOutcome.Accepted] or a definite [MqttOutcome.Rejected]) and the
     * command has left the outbox — or [MqttOutcome.NoResponse] NotConnected, meaning nothing was
     * published or persisted.
     */
    data class Settled<T>(val outcome: MqttOutcome<T>) : CommandOutcome<T>

    /** The outcome is unknown; [command] stays in the outbox. Never replace it with a new message id. */
    data class Unresolved<T>(
        val command: PendingCommand,
        val reason: UnresolvedReason,
        /** The snapshot, if the reply carried one. */
        val body: T?,
        val operatorMessage: String?,
    ) : CommandOutcome<T>
}

enum class UnresolvedReason {
    /** No reply / `outcome_unconfirmed`: republish the identical bytes while the session lasts. */
    RetryIdentical,

    /** `operator_session_invalid`: the same operator signs in again, then `recover`. */
    LoginThenRecover,

    /** `message_id_conflict` or an unrecognised failure: `recover` tells the truth. */
    Recover,

    /** `receipt_owner_mismatch` / `receipt_recovery_unavailable`: a manager reconciles. */
    ManagerReconcile,
}

private val DEFINITE = setOf(
    ErrorCode.REV2_REJECTED,
    ErrorCode.COLLECTION_REVISION_CONFLICT,
    ErrorCode.ACTION_NOT_ALLOWED,
    ErrorCode.INVALID_ENVELOPE,
    ErrorCode.PASSWORD_FIELD_FORBIDDEN,
    ErrorCode.CLIENT_UPGRADE_REQUIRED,
    ErrorCode.RECEIPT_SEALED,
)

/** Null when the outcome is settled. Branches on `error` only — never `nextAction` or prose. */
fun unresolvedReasonOf(outcome: MqttOutcome<*>): UnresolvedReason? = when (outcome) {
    is MqttOutcome.Accepted -> null
    is MqttOutcome.NoResponse -> UnresolvedReason.RetryIdentical
    is MqttOutcome.Rejected -> when (outcome.error) {
        in DEFINITE -> null
        ErrorCode.OUTCOME_UNCONFIRMED -> UnresolvedReason.RetryIdentical
        ErrorCode.OPERATOR_SESSION_INVALID -> UnresolvedReason.LoginThenRecover
        ErrorCode.RECEIPT_OWNER_MISMATCH, ErrorCode.RECEIPT_RECOVERY_UNAVAILABLE -> UnresolvedReason.ManagerReconcile
        else -> UnresolvedReason.Recover
    }
}
```

- [ ] **Step 5: Repository interface**

In `MqttRepository.kt`, add the import `com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutcome` and `com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand`. Then add after `request(...)`:

```kotlin
    /**
     * Publishes a MUTATION. The exact bytes are saved to the outbox before publishing and removed
     * only on a definite outcome, so an unanswered capture survives reconnects and restarts.
     * Reads keep using [request]: a lost read is resolved by reading again.
     */
    suspend fun <T : Any> sendCommand(
        requestType: String,
        responseType: String,
        payload: Any,
        responseClass: Class<T>,
    ): CommandOutcome<T>

    /** Republishes [command]'s identical bytes and message id, only within the session it was sent in. */
    suspend fun <T : Any> retryCommand(command: PendingCommand, responseClass: Class<T>): CommandOutcome<T>
```

- [ ] **Step 6: Transport implementation**

In `MqttRepositoryImpl.kt`:

(a) Add the constructor parameter last, with a default so the existing tests keep compiling. Hilt still injects the bound `CommandOutbox` through the full constructor.

```kotlin
    private val deviceIdentity: DeviceIdentity,
    private val commandOutbox: CommandOutbox = InMemoryCommandOutbox(),
) : MqttRepository {
```

Add the imports `com.mitas.ppnam.station2aa.data.mqtt.outbox.*`.

(b) Replace the body of `request()` from `val action = …` to the end of the method with a call to a shared helper, and add that helper:

```kotlin
        val action = (gson.toJsonTree(payload) as? com.google.gson.JsonObject)
            ?.get("action")?.takeIf { it.isJsonPrimitive }?.asString
        return exchange(topic, json.toByteArray(Charsets.UTF_8), messageId, sessionId, requestType, responseType, action, responseClass)
    }

    /**
     * One publish and one wait, correlated on [messageId]. Shared by reads and commands so both
     * get the same logging, session-invalid handling and duplicate-reply behaviour.
     */
    private suspend fun <T : Any> exchange(
        topic: String,
        bytes: ByteArray,
        messageId: String,
        sessionId: String,
        requestType: String,
        responseType: String,
        action: String?,
        responseClass: Class<T>,
    ): MqttOutcome<T> {
        val startedAt = System.currentTimeMillis()
        val waiter = CompletableDeferred<String>()
        pending[messageId] = PendingRequest(waiter, sessionId)
        // One attempt. Retrying is an explicit caller decision (audit S2-05; contract §9).
        try {
            try {
                publishFn(topic, bytes)
                MqttLog.message(
                    Direction.OUT, topic, 1, false, deviceId, requestType, action, "published",
                    payload = String(bytes, Charsets.UTF_8),
                )
            } catch (e: CancellationException) {
                // CancellationException is an Exception in Kotlin, so the generic catch below
                // would swallow it and report a normal failure — breaking structured
                // concurrency when a caller's scope is torn down mid-publish. Rethrow first.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "publish failed for $requestType", e)
                return MqttOutcome.NoResponse(FailureKind.NotConnected)
            }
            val raw = withTimeoutOrNull(requestTimeoutMs) { waiter.await() }
            if (raw == null) {
                MqttLog.message(
                    Direction.RESULT, topic, 1, false, deviceId, requestType, action,
                    "timeout", durationMs = System.currentTimeMillis() - startedAt,
                )
                return MqttOutcome.NoResponse(FailureKind.Timeout)
            }
            val outcome = parseOutcome(raw, responseClass, responseType)
            MqttLog.message(
                Direction.RESULT, topic, 1, false, deviceId, requestType, action,
                outcomeResult(outcome), durationMs = System.currentTimeMillis() - startedAt,
            )
            return outcome
        } finally {
            pending.remove(messageId)
        }
    }
```

(c) Add the command methods after `exchange`:

```kotlin
    override suspend fun <T : Any> sendCommand(
        requestType: String,
        responseType: String,
        payload: Any,
        responseClass: Class<T>,
    ): CommandOutcome<T> {
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return CommandOutcome.Settled(MqttOutcome.NoResponse(FailureKind.NotConnected))
        }
        val session = sessionHolder.session.value
            ?: return CommandOutcome.Settled(MqttOutcome.Rejected(null, ErrorCode.OPERATOR_SESSION_INVALID, null))
        val messageId = UUID.randomUUID().toString()
        val now = MqttSchema.formatTimestamp(nowFn())
        val json = RequestEnvelope.build(
            gson = gson,
            payload = payload,
            messageId = messageId,
            deviceId = deviceId,
            sessionId = session.operatorSessionId,
            timestampUtc = now,
        )
        val fields = gson.toJsonTree(payload).asJsonObject
        val command = PendingCommand(
            messageId = messageId,
            requestType = requestType,
            responseType = responseType,
            action = fields["action"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            targetId = fields["targetId"]?.takeIf { it.isJsonPrimitive }?.asString,
            payload = json,
            fingerprint = RequestFingerprint.of(json.toByteArray(Charsets.UTF_8)),
            operatorId = session.operatorId,
            sessionId = session.operatorSessionId,
            createdAtUtc = now,
        )
        commandOutbox.save(command)   // contract §2: persisted BEFORE it can reach the broker
        return deliver(command, responseClass)
    }

    override suspend fun <T : Any> retryCommand(command: PendingCommand, responseClass: Class<T>): CommandOutcome<T> {
        if (command.status == PendingStatus.ManagerReconcile) {
            return CommandOutcome.Unresolved(command, UnresolvedReason.ManagerReconcile, null, null)
        }
        // The bytes carry their original sessionId; replaying them under a new session is pointless.
        if (sessionHolder.currentSessionIdOrEmpty() != command.sessionId) {
            return CommandOutcome.Unresolved(command, UnresolvedReason.LoginThenRecover, null, null)
        }
        if (_connectionState.value != MqttConnectionState.CONNECTED) {
            return CommandOutcome.Unresolved(command, UnresolvedReason.RetryIdentical, null, null)
        }
        return deliver(command, responseClass)
    }

    private suspend fun <T : Any> deliver(command: PendingCommand, responseClass: Class<T>): CommandOutcome<T> {
        val outcome = exchange(
            topic = MqttTopics.request(deviceId, command.requestType),
            bytes = command.payloadBytes,
            messageId = command.messageId,
            sessionId = command.sessionId,
            requestType = command.requestType,
            responseType = command.responseType,
            action = command.action,
            responseClass = responseClass,
        )
        val rejected = outcome as? MqttOutcome.Rejected<T>
        return when (val reason = unresolvedReasonOf(outcome)) {
            null -> {
                commandOutbox.remove(command.messageId)
                CommandOutcome.Settled(outcome)
            }
            UnresolvedReason.ManagerReconcile -> {
                commandOutbox.markManagerReconcile(command.messageId)
                CommandOutcome.Unresolved(
                    command.copy(status = PendingStatus.ManagerReconcile), reason, rejected?.body, rejected?.operatorMessage,
                )
            }
            else -> CommandOutcome.Unresolved(command, reason, rejected?.body, rejected?.operatorMessage)
        }
    }
```

(d) In `di/AppModule.kt`, add:

```kotlin
    @Provides
    @Singleton
    fun provideCommandOutbox(@ApplicationContext context: Context): CommandOutbox =
        FileCommandOutbox(File(context.filesDir, "outbox"))
```

with the imports `android.content.Context`, `dagger.hilt.android.qualifiers.ApplicationContext`, `java.io.File`, `com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox` and `com.mitas.ppnam.station2aa.data.mqtt.outbox.FileCommandOutbox`.

- [ ] **Step 7: Run the new tests, then all transport tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.*"`
Expected: PASS, including `CommandTransportTest` (11 tests) and `CommandOutcomeTest` (3), plus the existing `Rev2TransportTest` and `MqttRequestTimeoutTest` unchanged.

- [ ] **Step 8: Commit**

```bash
git add -A app/src
git commit -m "feat(mqtt): sendCommand/retryCommand persist before publish and settle only on a definite outcome"
```

---

### Task 7: The `recover` action

**Files:**
- Create: `domain/usecase/CommandRecoveryUseCase.kt`
- Test: create `domain/usecase/CommandRecoveryUseCaseTest.kt`

**Interfaces:**
- Consumes: `MqttRepository.request`, `Rev2RecoverRequest`, `Rev2Snapshot` (Task 3), `CommandOutbox` and `PendingCommand` (Task 5), `ErrorCode` (Task 2), and `FailureKind.message()` (existing, internal, in `AuthUseCase.kt`).
- Produces:
  ```kotlin
  enum class RecoveryOutcome { Committed, Rejected, NotExecuted }
  sealed interface RecoveryResult { val command: PendingCommand
      data class Resolved(command, outcome: RecoveryOutcome, message: String, snapshot: Rev2Snapshot)
      data class StillUnresolved(command, message: String)
      data class NeedsManager(command, message: String)
      data class OtherOperator(command) }
  class CommandRecoveryUseCase { suspend fun recover(command: PendingCommand): RecoveryResult }
  ```

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/CommandRecoveryUseCaseTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RecoverRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.InMemoryCommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CommandRecoveryUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var outbox: InMemoryCommandOutbox
    private lateinit var sessions: OperatorSessionHolder
    private lateinit var useCase: CommandRecoveryUseCase

    // The original capture from the committed-recovery example.
    private val command = PendingCommand(
        messageId = "example-008",
        requestType = "rev2_general_requested",
        responseType = "rev2_general_result",
        action = "capture",
        targetId = "PREP_440fbd8f9cd3411e9be943a33f1a3ceb",
        payload = ContractFixtures.text("general_capture_request.json"),
        fingerprint = "72593FAA534ABF7E39B154342A88D26B99E5F81B27A76317E24C8DD426B77BCF",
        operatorId = "OP-1",
        sessionId = "old-session",
    )

    private fun fixture(name: String): Rev2Snapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"], Rev2Snapshot::class.java,
    )

    @Before
    fun setup() {
        mqtt = mock()
        outbox = InMemoryCommandOutbox().apply { save(command) }
        sessions = OperatorSessionHolder().apply { set(OperatorSession("new-session", "OP-1", "Op", "Worker")) }
        useCase = CommandRecoveryUseCase(mqtt, outbox, sessions)
    }

    private suspend fun stub(outcome: MqttOutcome<Rev2Snapshot>) {
        whenever(mqtt.request(any(), any(), any(), eq(Rev2Snapshot::class.java))).thenReturn(outcome)
    }

    @Test
    fun `recover is sent in the original family with the original id and raw hash`() = runTest {
        stub(MqttOutcome.Accepted(fixture("general_recover_committed")))
        useCase.recover(command)
        val captor = argumentCaptor<Any>()
        verify(mqtt).request(eq("rev2_general_requested"), eq("rev2_general_result"), captor.capture(), eq(Rev2Snapshot::class.java))
        val sent = captor.firstValue as Rev2RecoverRequest
        assertEquals("recover", sent.action)
        assertEquals("example-008", sent.originalMessageId)
        assertEquals(command.fingerprint, sent.originalRequestFingerprint)
        assertEquals(command.targetId, sent.targetId)
    }

    @Test
    fun `committed resolves and leaves the outbox`() = runTest {
        stub(MqttOutcome.Accepted(fixture("general_recover_committed")))
        val result = useCase.recover(command) as RecoveryResult.Resolved
        assertEquals(RecoveryOutcome.Committed, result.outcome)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `not executed resolves as sealed`() = runTest {
        val snapshot = fixture("general_recover_not_executed")
        stub(MqttOutcome.Accepted(snapshot.copy(recovery = snapshot.recovery!!.copy(originalMessageId = "example-008"))))
        assertEquals(RecoveryOutcome.NotExecuted, (useCase.recover(command) as RecoveryResult.Resolved).outcome)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `a recovery reply about a different message does not resolve this one`() = runTest {
        stub(MqttOutcome.Accepted(fixture("general_recover_not_executed")))   // names example-delayed-bag
        assertTrue(useCase.recover(command) is RecoveryResult.StillUnresolved)
        assertEquals(1, outbox.commands.value.size)
    }

    @Test
    fun `owner mismatch needs a manager and is marked`() = runTest {
        stub(MqttOutcome.Rejected(null, ErrorCode.RECEIPT_OWNER_MISMATCH, "Another operator owns this receipt"))
        assertTrue(useCase.recover(command) is RecoveryResult.NeedsManager)
        assertEquals(PendingStatus.ManagerReconcile, outbox.commands.value.single().status)
    }

    @Test
    fun `no reply leaves it unresolved for another recovery`() = runTest {
        stub(MqttOutcome.NoResponse(FailureKind.Timeout))
        assertTrue(useCase.recover(command) is RecoveryResult.StillUnresolved)
        assertEquals(1, outbox.commands.value.size)
    }

    @Test
    fun `a different operator's command is not sent for recovery`() = runTest {
        sessions.set(OperatorSession("s", "OP-2", "Other", "Worker"))
        assertTrue(useCase.recover(command) is RecoveryResult.OtherOperator)
        verify(mqtt, never()).request(any(), any(), any(), eq(Rev2Snapshot::class.java))
    }
}
```

- [ ] **Step 2: Run it and see it fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.domain.usecase.CommandRecoveryUseCaseTest"`
Expected: compilation FAILS (`CommandRecoveryUseCase` unresolved).

- [ ] **Step 3: Implement**

`app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/CommandRecoveryUseCase.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RecoverRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import javax.inject.Inject
import javax.inject.Singleton

enum class RecoveryOutcome {
    /** The original succeeded. Never perform it again. */
    Committed,

    /** The original was definitely rejected. Review and correct with a new intent. */
    Rejected,

    /** The original never ran, and its id is now sealed. Re-read, and ask before doing it again. */
    NotExecuted;

    companion object {
        fun fromWire(value: String): RecoveryOutcome? = when (value) {
            "committed" -> Committed
            "rejected" -> Rejected
            "not_executed" -> NotExecuted
            else -> null
        }
    }
}

sealed interface RecoveryResult {
    val command: PendingCommand

    data class Resolved(
        override val command: PendingCommand,
        val outcome: RecoveryOutcome,
        /** The original operation's saved result message. */
        val message: String,
        val snapshot: Rev2Snapshot,
    ) : RecoveryResult

    data class StillUnresolved(override val command: PendingCommand, val message: String) : RecoveryResult
    data class NeedsManager(override val command: PendingCommand, val message: String) : RecoveryResult
    data class OtherOperator(override val command: PendingCommand) : RecoveryResult
}

/**
 * Contract §9: after a re-login (or a message-id conflict), ask Station 2 what became of an
 * unanswered command. The recovery itself performs no physical operation and is safe to repeat,
 * so every attempt uses a new message id via [MqttRepository.request].
 */
@Singleton
class CommandRecoveryUseCase @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val outbox: CommandOutbox,
    private val sessionHolder: OperatorSessionHolder,
) {

    suspend fun recover(command: PendingCommand): RecoveryResult {
        val session = sessionHolder.session.value
            ?: return RecoveryResult.StillUnresolved(command, "Sign in to resolve this command")
        if (session.operatorId != command.operatorId) return RecoveryResult.OtherOperator(command)
        if (command.status == PendingStatus.ManagerReconcile) return RecoveryResult.NeedsManager(command, MANAGER_MESSAGE)

        val outcome = mqttRepository.request(
            requestType = command.requestType,
            responseType = command.responseType,
            payload = Rev2RecoverRequest(
                targetId = command.targetId,
                originalMessageId = command.messageId,
                originalRequestFingerprint = command.fingerprint,
            ),
            responseClass = Rev2Snapshot::class.java,
        )
        return when (outcome) {
            is MqttOutcome.Accepted -> {
                val recovery = outcome.body.recovery
                val kind = recovery?.outcome?.let(RecoveryOutcome::fromWire)
                if (recovery == null || kind == null || recovery.originalMessageId != command.messageId) {
                    RecoveryResult.StillUnresolved(command, "Station 2's recovery reply was incomplete — try again")
                } else {
                    outbox.remove(command.messageId)
                    RecoveryResult.Resolved(command, kind, recovery.result?.message.orEmpty(), outcome.body)
                }
            }
            is MqttOutcome.Rejected -> when (outcome.error) {
                ErrorCode.RECEIPT_OWNER_MISMATCH, ErrorCode.RECEIPT_RECOVERY_UNAVAILABLE -> {
                    outbox.markManagerReconcile(command.messageId)
                    RecoveryResult.NeedsManager(command, outcome.operatorMessage ?: MANAGER_MESSAGE)
                }
                else -> RecoveryResult.StillUnresolved(
                    command, outcome.operatorMessage ?: "Station 2 could not recover this command yet",
                )
            }
            is MqttOutcome.NoResponse -> RecoveryResult.StillUnresolved(command, outcome.kind.message())
        }
    }

    private companion object {
        const val MANAGER_MESSAGE = "A manager must reconcile this at the station before you scan again"
    }
}
```

- [ ] **Step 4: Run it and see it pass**

Run the Step 2 command. Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(recovery): recover unanswered commands by original id and raw hash"
```

---

### Task 8: Recovery after login, and the Home card

**Files:**
- Create: `domain/usecase/PendingCommandCoordinator.kt`
- Create: `ui/home/RecoveryNoticeText.kt`
- Create: `ui/home/UnresolvedCommandsCard.kt`
- Modify: `ui/home/HomeViewModel.kt`, `ui/home/HomeScreen.kt` (the `Column` near line 93)
- Modify: `test/.../ui/home/HomeViewModelTest.kt:58`
- Test: create `domain/usecase/PendingCommandCoordinatorTest.kt` and `ui/home/RecoveryNoticeTextTest.kt`

**Interfaces:**
- Consumes: `CommandRecoveryUseCase`, `RecoveryResult`, `RecoveryOutcome` (Task 7); `CommandOutbox`, `PendingCommand`, `PendingStatus` (Task 5).
- Produces: `PendingCommandCoordinator` with `val pending: StateFlow<List<PendingCommand>>`, `val notices: StateFlow<List<RecoveryResult>>`, `suspend fun recoverForCurrentOperator()`, `fun dismiss(notice: RecoveryResult)`.
- Produces: `RecoveryResult.noticeText(): String`, `PendingCommand.label(): String`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/PendingCommandCoordinatorTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.InMemoryCommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PendingCommandCoordinatorTest {

    private fun cmd(id: String, operator: String, session: String) =
        PendingCommand(messageId = id, operatorId = operator, sessionId = session, action = "capture")

    @Test
    fun `only the signed-in operator's commands from earlier sessions are recovered`() = runTest {
        val outbox = InMemoryCommandOutbox().apply {
            save(cmd("a", "OP-1", "old"))
            save(cmd("b", "OP-2", "old"))
            save(cmd("c", "OP-1", "current"))   // still in its own session: the owning screen retries it
        }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("current", "OP-1", "Op", "Worker")) }
        val recovery = mock<CommandRecoveryUseCase>()
        whenever(recovery.recover(any())).thenAnswer {
            RecoveryResult.Resolved(it.getArgument(0), RecoveryOutcome.Committed, "done", Rev2Snapshot())
        }
        val coordinator = PendingCommandCoordinator(outbox, recovery, sessions)

        coordinator.recoverForCurrentOperator()

        verify(recovery).recover(cmd("a", "OP-1", "old"))
        verify(recovery, never()).recover(cmd("b", "OP-2", "old"))
        verify(recovery, never()).recover(cmd("c", "OP-1", "current"))
        assertEquals(listOf("a"), coordinator.notices.value.map { it.command.messageId })
    }

    @Test
    fun `with nobody signed in nothing is sent`() = runTest {
        val outbox = InMemoryCommandOutbox().apply { save(cmd("a", "OP-1", "old")) }
        val recovery = mock<CommandRecoveryUseCase>()
        PendingCommandCoordinator(outbox, recovery, OperatorSessionHolder()).recoverForCurrentOperator()
        verify(recovery, never()).recover(any())
    }

    @Test
    fun `dismiss removes a notice`() = runTest {
        val outbox = InMemoryCommandOutbox().apply { save(cmd("a", "OP-1", "old")) }
        val sessions = OperatorSessionHolder().apply { set(OperatorSession("current", "OP-1", "Op", "Worker")) }
        val recovery = mock<CommandRecoveryUseCase>()
        whenever(recovery.recover(any())).thenAnswer { RecoveryResult.StillUnresolved(it.getArgument(0), "later") }
        val coordinator = PendingCommandCoordinator(outbox, recovery, sessions)
        coordinator.recoverForCurrentOperator()
        coordinator.dismiss(coordinator.notices.value.single())
        assertTrue(coordinator.notices.value.isEmpty())
    }
}
```

`app/src/test/java/com/mitas/ppnam/station2aa/ui/home/RecoveryNoticeTextTest.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryNoticeTextTest {

    private val cmd = PendingCommand(messageId = "m", action = "capture", targetId = "PREP_1", operatorId = "OP-9")

    @Test
    fun `each outcome tells the operator what to do`() {
        assertEquals(
            "capture PREP_1: Station 2 had already done this. It was not repeated.",
            RecoveryResult.Resolved(cmd, RecoveryOutcome.Committed, "Bag recorded", Rev2Snapshot()).noticeText(),
        )
        assertEquals(
            "capture PREP_1: Station 2 rejected it — Too much. Re-read before trying again.",
            RecoveryResult.Resolved(cmd, RecoveryOutcome.Rejected, "Too much", Rev2Snapshot()).noticeText(),
        )
        assertEquals(
            "capture PREP_1: this never happened. Re-read the job, then do it again only if it is still needed.",
            RecoveryResult.Resolved(cmd, RecoveryOutcome.NotExecuted, "", Rev2Snapshot()).noticeText(),
        )
        assertEquals(
            "capture PREP_1: a manager must reconcile this at the station. Owner unknown",
            RecoveryResult.NeedsManager(cmd, "Owner unknown").noticeText(),
        )
        assertEquals(
            "capture PREP_1: still unresolved — No reply. It will be checked again.",
            RecoveryResult.StillUnresolved(cmd, "No reply").noticeText(),
        )
        assertEquals(
            "capture PREP_1: sent by operator OP-9. They must sign in on this scanner to resolve it.",
            RecoveryResult.OtherOperator(cmd).noticeText(),
        )
    }
}
```

- [ ] **Step 2: Run them and see them fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinatorTest" --tests "com.mitas.ppnam.station2aa.ui.home.RecoveryNoticeTextTest"`
Expected: compilation FAILS.

- [ ] **Step 3: Coordinator**

`app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/PendingCommandCoordinator.kt`:

```kotlin
package com.mitas.ppnam.station2aa.domain.usecase

import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contract §9 step 1–3: once the same operator is signed in again, recover each command they left
 * unresolved in an EARLIER session. A command from the current session is the owning screen's to
 * retry identically. Another operator's command is never sent; it is shown so they can come back.
 */
@Singleton
class PendingCommandCoordinator @Inject constructor(
    private val outbox: CommandOutbox,
    private val recovery: CommandRecoveryUseCase,
    private val sessionHolder: OperatorSessionHolder,
) {
    val pending: StateFlow<List<PendingCommand>> get() = outbox.commands

    private val _notices = MutableStateFlow<List<RecoveryResult>>(emptyList())
    val notices: StateFlow<List<RecoveryResult>> = _notices.asStateFlow()

    private val mutex = Mutex()

    suspend fun recoverForCurrentOperator() = mutex.withLock {
        val session = sessionHolder.session.value ?: return@withLock
        val due = outbox.commands.value.filter {
            it.operatorId == session.operatorId &&
                it.status == PendingStatus.Unresolved &&
                it.sessionId != session.operatorSessionId
        }
        for (command in due) {
            val result = recovery.recover(command)
            _notices.update { list -> list.filterNot { it.command.messageId == command.messageId } + result }
        }
    }

    fun dismiss(notice: RecoveryResult) = _notices.update { it - notice }
}
```

- [ ] **Step 4: Notice wording**

`app/src/main/java/com/mitas/ppnam/station2aa/ui/home/RecoveryNoticeText.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.home

import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult

/** "capture PREP_1" — what the operator did, in the terms the scanner showed them. */
internal fun PendingCommand.label(): String = listOfNotNull(action.ifBlank { "command" }, targetId).joinToString(" ")

internal fun RecoveryResult.noticeText(): String {
    val what = command.label()
    return when (this) {
        is RecoveryResult.Resolved -> when (outcome) {
            RecoveryOutcome.Committed -> "$what: Station 2 had already done this. It was not repeated."
            RecoveryOutcome.Rejected -> "$what: Station 2 rejected it — $message. Re-read before trying again."
            RecoveryOutcome.NotExecuted ->
                "$what: this never happened. Re-read the job, then do it again only if it is still needed."
        }
        is RecoveryResult.NeedsManager -> "$what: a manager must reconcile this at the station. $message"
        is RecoveryResult.StillUnresolved -> "$what: still unresolved — $message. It will be checked again."
        is RecoveryResult.OtherOperator ->
            "$what: sent by operator ${command.operatorId}. They must sign in on this scanner to resolve it."
    }
}
```

- [ ] **Step 5: Run the Step 2 tests and see them pass**

Expected: PASS, 4 tests.

- [ ] **Step 6: Home view model**

Replace `HomeViewModel`'s constructor and add the members. Add the imports `PendingCommand`, `PendingCommandCoordinator` and `RecoveryResult`.

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder,
    private val coordinator: PendingCommandCoordinator,
) : ViewModel() {

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    /** Every unanswered command on this scanner, whoever sent it. */
    val pendingCommands: StateFlow<List<PendingCommand>> = coordinator.pending

    val recoveryNotices: StateFlow<List<RecoveryResult>> = coordinator.notices

    init {
        // Home is where every login lands: recover what this operator left unresolved.
        recoverPending()
    }

    fun recoverPending() {
        viewModelScope.launch { coordinator.recoverForCurrentOperator() }
    }

    fun dismissNotice(notice: RecoveryResult) = coordinator.dismiss(notice)
```

Keep the existing `_logoutEvent`, `logoutEvent` and `logout()` below this unchanged.

In `HomeViewModelTest.kt` line 58, replace the construction with:

```kotlin
        val coordinator = mock<PendingCommandCoordinator>()
        whenever(coordinator.pending).thenReturn(MutableStateFlow(emptyList()))
        whenever(coordinator.notices).thenReturn(MutableStateFlow(emptyList()))
        viewModel = HomeViewModel(mockMqttRepository, mockAuthUseCase, mockSessionHolder, coordinator)
```

Add the imports `com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator`, `kotlinx.coroutines.flow.MutableStateFlow` and `org.mockito.kotlin.whenever` if they are not already present.

- [ ] **Step 7: The card**

`app/src/main/java/com/mitas/ppnam/station2aa/ui/home/UnresolvedCommandsCard.kt`:

```kotlin
package com.mitas.ppnam.station2aa.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult

/**
 * Unanswered commands and what recovery found. Shown only when there is something to say: an
 * unresolved capture is a physical-stock question the operator must not lose sight of.
 */
@Composable
fun UnresolvedCommandsCard(
    pending: List<PendingCommand>,
    notices: List<RecoveryResult>,
    onCheckAgain: () -> Unit,
    onDismiss: (RecoveryResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending.isEmpty() && notices.isEmpty()) return
    val noticed = notices.map { it.command.messageId }.toSet()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending.isNotEmpty()) {
                Text(
                    "${pending.size} unanswered ${if (pending.size == 1) "command" else "commands"}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            notices.forEach { notice ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(notice.noticeText(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (notice !is RecoveryResult.StillUnresolved) {
                        TextButton(onClick = { onDismiss(notice) }) { Text("OK") }
                    }
                }
            }
            pending.filter { it.messageId !in noticed }.forEach { command ->
                val state = if (command.status == PendingStatus.ManagerReconcile) "needs a manager" else "waiting for Station 2"
                Text("${command.label()}: $state", style = MaterialTheme.typography.bodySmall)
            }
            if (pending.any { it.status == PendingStatus.Unresolved }) {
                OutlinedButton(onClick = onCheckAgain) { Text("Check again") }
            }
        }
    }
}
```

- [ ] **Step 8: Place it on Home**

In `HomeScreen.kt`, collect the two flows next to `val session by viewModel.session.collectAsState()`:

```kotlin
    val pendingCommands by viewModel.pendingCommands.collectAsState()
    val recoveryNotices by viewModel.recoveryNotices.collectAsState()
```

Inside the `Column(` that begins near line 93, directly after the greeting `Text(...)` and before the first `HomeTile(`, insert:

```kotlin
            UnresolvedCommandsCard(
                pending = pendingCommands,
                notices = recoveryNotices,
                onCheckAgain = viewModel::recoverPending,
                onDismiss = viewModel::dismissNotice,
            )
```

- [ ] **Step 9: Whole suite and build**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline` and then `.\gradlew.bat :app:assembleDebug --offline`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add -A app/src
git commit -m "feat(home): recover the operator's unanswered commands after login and show the outcome"
```

---

### Task 9: Simulator speaks the 2026-10-01 envelope and `mixProgress`

**Files:**
- Modify: `tools/backend-sim/envelope.py:20,35-37`
- Modify: `tools/backend-sim/sim.py` (`handle_request`, near line 281)
- Modify: `tools/backend-sim/handlers/rev2_general.py:118-134` (`snapshot`)
- Modify: `tools/backend-sim/selftest.py:27-28`

**Interfaces:**
- Produces: every simulated direct reply carries `contractRevision` and `requestFingerprint` and uses token `nextAction` values. General snapshots carry `mixProgress`, `mode`, `capabilities`, `recovery` and the exception lists.

- [ ] **Step 1: Make the selftest expect the new envelope (failing)**

In `selftest.py`, extend `REPLY_KEYS`:

```python
REPLY_KEYS = {"schemaVersion", "contractRevision", "deviceId", "inResponseToMessageId", "requestFingerprint",
              "receivedAtUtc", "sentAtUtc", "durationMs", "success", "error", "operatorMessage", "nextAction", "data"}
```

Run (Git Bash, repo root): `cd tools/backend-sim && python selftest.py; cd ../..`
Expected: FAIL lines naming `contractRevision` and `requestFingerprint` as missing.

- [ ] **Step 2: Envelope constants and tokens**

In `envelope.py`, under `SCHEMA_VERSION = "rev2.1"`, add `CONTRACT_REVISION = "2026-10-01"`. Replace the two prose constants with the contract §10 tokens:

```python
SUCCESS_NEXT_ACTION = "read_saved_state"
FAILURE_NEXT_ACTION = "correct_request"
```

In `reply(...)`, add `"contractRevision": CONTRACT_REVISION,` after `"schemaVersion"`, and `"requestFingerprint": "",` after `"inResponseToMessageId"`.

- [ ] **Step 3: Fingerprint the raw request**

In `sim.py`, add `import hashlib` at the top. In `handle_request`, directly before `self.publish_response(...)`:

```python
        # Contract §2: uppercase hex SHA-256 of the exact request bytes the station received.
        response["requestFingerprint"] = hashlib.sha256(payload or b"").hexdigest().upper()
```

- [ ] **Step 4: Snapshot fields and `mixProgress`**

In `handlers/rev2_general.py`, add above `snapshot`:

```python
CAPABILITIES = {"operatorIngredientDecisions": True, "receiptRecovery": True,
                "managerReviewDesktopOnly": True, "jobMixProgress": True}


def mix_progress(job, preparations):
    """Contract §8.1, from the simulator's saved preparations (collectedMixes is not modelled: 0)."""
    preps = [p for p in preparations if p["jobId"] == job["id"] and p["stage"] != "Cancelled"]
    active = [p for p in preps if p["stage"] != "Completed"]
    produced = sum(p["produced"] for p in preps)
    allocated = sum(p["mixCount"] for p in preps)
    required = job["requiredMixes"]
    return {
        "requiredMixes": required,
        "allocatedMixes": allocated,
        "activeMixes": sum(max(0, p["mixCount"] - p["produced"]) for p in active),
        "availableToPrepareMixes": max(0, required - allocated),
        "remainingToFinishMixes": max(0, required - produced),
        "collectedMixes": 0,
        "confirmedMixes": sum(p["mixCount"] for p in preps if p.get("confirmedAtUtc")),
        "mixedMixes": sum(p["mixed"] for p in preps),
        "producedMixes": produced,
        "activePreparationCount": len(active),
        "activePreparations": [{
            "id": p["id"], "jobId": p["jobId"], "mixCount": p["mixCount"], "stage": p["stage"],
            "mixed": p["mixed"], "produced": p["produced"],
            "remainingToFinishMixes": max(0, p["mixCount"] - p["produced"]),
            "collectionRevision": p.get("collectionRevision", 0), "startedAtUtc": p["startedAtUtc"],
            "startedBy": p["startedBy"], "mixerId": p["mixerId"], "productionId": p["productionId"],
            "cycleId": p["cycleId"],
        } for p in active],
    }
```

Replace the `return {...}` of `snapshot` with:

```python
    return {
        "recovery": None,
        "mode": "General",
        "capabilities": CAPABILITIES,
        "jobs": [{"id": j["id"], "product": j["product"], "closed": j["closed"],
                  "requiredMixes": j["requiredMixes"],
                  "mixProgress": mix_progress(j, preparations)} for j in world.rev2_jobs.values()],
        "job": None if job is None else dict(
            job,
            allocatedMixes=sum(p["mixCount"] for p in preparations if p["jobId"] == job["id"]),
            collectionRevision=0,
            mixProgress=mix_progress(job, preparations)),
        "preparation": preparation if job is not None else None,
        "preparations": preparations,
        "machines": [],
        "exceptionListRevision": 1,
        "ingredientExceptions": [],
        "requiredIngredientChoices": [],
        "collectionExceptions": [],
        "commandExceptions": [],
    }
```

- [ ] **Step 5: Selftest passes**

Run: `cd tools/backend-sim && python selftest.py; cd ../..`
Expected: no FAIL lines; exit code 0.

- [ ] **Step 6: Commit**

```bash
git add tools/backend-sim
git commit -m "chore(sim): 2026-10-01 envelope, fingerprint, nextAction tokens and mixProgress"
```

---

### Task 10: Verify, refresh the graph, and hand off

- [ ] **Step 1: Full suite and build**

Run: `.\gradlew.bat :app:testDebugUnitTest --offline` then `.\gradlew.bat :app:assembleDebug --offline`
Expected: both BUILD SUCCESSFUL, with zero failing tests.

- [ ] **Step 2: Device smoke test against the simulator**

1. Start `python tools/backend-sim/sim.py` with the broker flags in its docstring.
2. Install the debug APK, sign in and open Jobs.
3. Expected: each job row reads `Required N · Active A · Available to prepare P · Finished F`. The seeded job `510019068` shows Active 2 and lists `PREP_demo0001` under Active preparations.
4. Expected: Home shows no unanswered-commands card, because the outbox is empty.

Record the result, with a screenshot path, in the PR description.

- [ ] **Step 3: Update the knowledge graph**

Run: `graphify update .`

- [ ] **Step 4: Mark the spec**

In `docs/superpowers/specs/2026-10-05-contract-2026-10-01-adoption-design.md`, change `**Status:** Draft for review` to `**Status:** Phase 1 implemented (branch feat/contract-2026-10-01-foundation)`.

- [ ] **Step 5: Commit**

```bash
git add -A docs graphify-out
git commit -m "docs: mark contract 2026-10-01 phase 1 implemented"
```

Release tagging (the `chore(release): vX.Y.Z (code)` convention) is left to the user's decision after review.
