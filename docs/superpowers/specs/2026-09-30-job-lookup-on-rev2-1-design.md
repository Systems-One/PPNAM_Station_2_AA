# Station 2 job lookup on the rev2.1 contract

**Date:** 2026-09-30
**Branch:** `feat/strip-to-job-lookup` (continues; Task 1 deletions from the Sept 10 plan are staged)
**Status:** Approved design, not yet planned
**Supersedes:** `2026-09-10-strip-to-job-lookup-design.md`

## 1. Purpose

Reduce the PPNAM Station 2 Android app to one working vertical slice — SCRAM login, a job list, look up a job card, view its job and BOM read-only — speaking the Station 2 **rev2.1** MQTT contract that the WPF server implements today. The result is the foundation the rebuild continues from.

### Why the Sept 10 design is replaced

The Sept 10 design made the fleet base standard (`C:\Dev\Clients\PPNAM\Docs\MQTT_BASE_README.md`) win over `RFID_MQTT_CONTRACT.md`, because the contract was being overhauled. The overhauled contract has landed (`RFID_MQTT_CONTRACT.md`, "Active September 8 Rev 2 contract", amendments through September 29), the server implements it (`PPNAM.Station2.Core/Services/Rev2ScannerProcessor.cs`), and it states the Android app must adopt it before commissioning. It contradicts the base standard's workflow envelope, error-code casing and correlation model. Building to the base standard would produce an app no real server answers.

### Authority

1. **rev2.1 governs the wire**: topics, envelopes, field names, error codes, correlation, idempotency. Where the contract prose is silent or ambiguous, `Rev2ScannerProcessor.cs` and `Models/Rev2MixingModels.cs` (read-only references) are the tie-breaker.
2. **The base standard governs what rev2.1 leaves open**: device identity, presence and LWT, reconnect and self-heal, structured logging and redaction, the 10 s workflow timeout.

### Confirmed with the customer (2026-09-30)

- Login will never return `allowedTabs` (or `allowedActions`).
- There is no logout topic and none is planned.
- A printed job card barcode scans as the Production Order document number — the numeric string `lookup` takes as `jobCard`.

### Non-goals

- `prepare`, `capture`, `confirm`, `machine_start`, `machine_finish`, and the whole Rajoo family. Their DTOs are not written.
- Per-ingredient Include/Exclude controls. `requiredIngredientChoices` and `ingredientExceptions` are parsed and ignored; they matter only to `prepare`.
- Persisting unanswered commands across reconnects. rev2.1 requires it for commands; both slice actions are reads, where a lost reply is resolved by reading again. It is a prerequisite for the first mutating action.
- Changing connection, presence or self-heal code. Already compliant; see §7.

## 2. Wire facts this design rests on

Taken from the contract and confirmed in `Rev2ScannerProcessor.cs`:

| Fact | Consequence |
|---|---|
| Server subscribes to exactly `scram_start_requested`, `scram_proof_requested`, `rev2_general_requested`, `rev2_rajoo_requested`; anything else gets `client_upgrade_required` | Every other request type in the app is removed |
| Response suffixes: `scram_challenge`, `scram_proof_result`, `rev2_general_result`; push `active_job_cards_invalidated` | |
| Request: `schemaVersion:"rev2.1"`, `deviceId` = topic device, `messageId` 1–100 chars, `timestampUtc` `yyyy-MM-ddTHH:mm:ss.ffffffZ`; workflow adds `sessionId`, `action` | |
| Request rejected (`invalid_envelope`) on duplicate field names, case-insensitive, top level; `password_field_forbidden` on any top-level field whose name **contains** `password`, case-insensitive; payload > 65,536 chars rejected | Client-side guards mirror these |
| Response: `schemaVersion`, `deviceId`, `inResponseToMessageId`, `receivedAtUtc`, `sentAtUtc`, `durationMs` (a double), `success`, `error` (`""` on success), `operatorMessage`, `nextAction`, `data` | Body lives under `data` |
| Responses carry **no** `messageId` of their own | Duplicate suppression cannot use a response id |
| `nextAction` is prose ("Follow the saved job or preparation state.") | Never branched on |
| SCRAM `data`: challenge = `challengeId, serverNonce, salt, iterations, serverFirstMessage, expiresAtUtc`; proof = `serverSignature, session{sessionId, operatorId, displayName, role, sourceDevice, loggedInAtUtc, expiresAtUtc, sessionState, isActive}` | No `allowedTabs` |
| SCRAM `purpose` other than `login` → `purpose_not_enabled`; auth failure → `authentication_failed` (or the service's own code); an identical auth request within 60 s replays the stored reply, a different body with the same id → `message_id_conflict` | |
| Every General reply's `data` is the snapshot: `result{success, message, targetId, cycleId}`, `jobs[]{id, product, closed, requiredMixes}`, `job`, `preparation`, `preparations[]`, `machines[]`, `exceptionListRevision`, `ingredientExceptions[]`, `requiredIngredientChoices[]` | One DTO covers `read` and `lookup` |
| `job` = `id, product, mode, unit, overallQuantity, scopeQuantity, sapCompletedAtLookup, outputPerMix, requiredMixes, materials[], capturedAtUtc, activeSessionId, rajooMachineId, closed, ingredientChoices, exceptionListRevision, allocatedMixes` | |
| `materials[]` = `code, name, unit, perMix, required, collected, excluded` (`remaining` is computed server-side and not serialized) | Remaining is computed on the device as `max(0, required − collected)` |
| Preparation = `id, jobId, mixCount, mixed, produced, stage, ingredientChoices, exceptionListRevision, mixerId, productionId, cycleId, startedAtUtc, confirmedAtUtc, startedBy, materials[]`; `stage` is an enum name (`Collecting` … `Completed`) | |
| Enums serialize as names; timestamps as six-digit UTC | |
| `lookup` accepts only digit-only JCs for Standard Planned/Released orders, header warehouse `FAC`, positive outstanding output; it saves a local job record server-side but never mutates SAP | Device rejects non-digit input before sending |
| Session failure → `operator_session_invalid`; business rejection → `rev2_rejected` with snapshot; unexpected server fault → `outcome_unconfirmed` (retry identically) | |
| Push: `schemaVersion, deviceId, messageId, timestampUtc, mode:"General", reason:"preparation_created", nextAction:"read"`; sent only to devices that made an authenticated General request this server process | |

## 3. Target end state

```
Login (SCRAM)  ->  Home  ->  Job Lookup  ->  Job Detail
                             (job list +     (job + read-only BOM
                              scan/type JC)   + its preparations)
                   Settings (broker config + diagnostics)
```

| Request suffix | `action` | Response suffix | Use |
|---|---|---|---|
| `scram_start_requested` | — | `scram_challenge` | Login step 1 |
| `scram_proof_requested` | — | `scram_proof_result` | Login step 2 |
| `rev2_general_requested` | `read` | `rev2_general_result` | Job list; detail with `targetId` |
| `rev2_general_requested` | `lookup` | `rev2_general_result` | Load one JC from SAP |
| — (push) | — | `active_job_cards_invalidated` | Trigger a fresh `read` |

## 4. The strip

The Sept 10 plan's deletions stand, with these changes:

**Also deleted**
- Badge login: `LoginMethod.Badge`, `BadgeLoginPayload`, `OperatorContextResponse`, the badge path in `LoginViewModel`/`LoginScreen`.
- `reader_logout_requested`. `AuthUseCase.logout()` clears the local session only; the server session expires on its own.
- `ManagerAuthorization`, `ManagerAction`, `ScramPurpose.MANAGER_ACTION`, and SCRAM `actionTarget`/`managerAction` fields.
- `StationAction`, `canShow()`, and the `allowedActions`/`allowedTabs` properties of `OperatorSession`.
- Paging: `ActiveJobsPage`, `ActiveJobCardsPayload`, `ActiveJobCardsListResponse`, `ActiveJobCardSummary`, `loadMoreActiveJobs`, `ErrorCode.PAGE_CURSOR_STALE`.
- All of `JobCardMessages.kt` (`JobCardLoadPayload`, `BomLoadedResponse`, `BomLineResponse`, `BagSizeOptionResponse`, `CollectionSummaryResponse`, `ActiveJobCardsInvalidatedResponse`) — replaced by §6.2 DTOs. `BomLoadedResponseTest` goes with it.
- `NextAction` and every constant in it.
- The Room BOM cache (unchanged from Sept 10).

**Kept, reversing Sept 10**
- `ui/components/UpgradeGate.kt` and the `upgradeRequired` latch. rev2.1 still returns `client_upgrade_required`.

**Kept as in Sept 10**
- `data/rfid/` (`DataWedgeReceiver`, `ScanEventBus`) — the job card is scanned.
- Settings trim, Home reduced to one tile, flattened navigation, the `MixingViewModel` → `JobLookupViewModel` and `MixingUseCase` → `JobLookupUseCase` splits.

## 5. The transport

### 5.1 Request envelope

`RequestEnvelope.build()` produces one flat JSON object: the payload's fields, then the envelope fields written last so they always win:

```json
{
  "action": "lookup",
  "jobCard": "510019296",
  "schemaVersion": "rev2.1",
  "deviceId": "scanner_5c64df8d86a8",
  "messageId": "0f7c1c5e-6a55-4d0b-9a36-6d1c2f0f9b3e",
  "timestampUtc": "2026-09-30T10:15:30.123456Z",
  "sessionId": "…"
}
```

- `sessionId` is written only when a session exists. SCRAM requests are built with no session.
- `correlationKey` and the `correlationKey` parameter of `MqttRepository.request()` are removed.
- `MqttSchema.VERSION = "rev2.1"`.
- `messageId` stays a UUID (36 chars, inside the 100 limit).
- Nulls are omitted, as today.

### 5.2 Response envelope

`ResponseEnvelope` becomes: `schemaVersion`, `deviceId`, `inResponseToMessageId`, `receivedAtUtc`, `sentAtUtc`, `durationMs: Double?`, `success`, `error`, `operatorMessage`, `nextAction`, and — for pushes only — `messageId`, `timestampUtc`, `mode`, `reason`. Every constructor parameter keeps a default (the Gson no-arg-constructor rule documented in the current file).

`parseOutcome` deserializes `data` (a `JsonObject`, possibly absent) into the caller's response class. An absent or null `data` on a rejection yields `Rejected` with a null body; `MqttOutcome.Rejected.body` becomes nullable. An absent `data` on success is `MalformedResponse`.

`MqttOutcome` becomes:

```kotlin
sealed interface MqttOutcome<out T> {
    data class Accepted<T>(val body: T) : MqttOutcome<T>
    data class Rejected<T>(val body: T?, val error: ErrorCode?, val operatorMessage: String?) : MqttOutcome<T>
    data class NoResponse(val kind: FailureKind) : MqttOutcome<Nothing>
}
```

`exceptionId`, `fieldErrors` and `nextAction` go; rev2.1 does not send them.

### 5.3 Correlation, retry, duplicates

- Correlation stays on `inResponseToMessageId` against the `pending` map.
- The 3-attempt loop republishing identical bytes stays: rev2.1 makes `(deviceId, suffix, messageId)` with a body fingerprint the replay identity, so an identical retry is safe by contract.
- Duplicate direct replies: the first completes and removes the waiter; later ones find none and are dropped. `claimResponseId` / `seenResponseIds` is applied **only to pushes**, keyed on the push `messageId`.
- Workflow timeout stays at 10 s (`requestTimeoutMs` default 10 000, configurable).

### 5.4 Session invalidation without an echo

rev2.1 replies do not echo the session. The guard against a late reply logging out a newer session moves to the request side: each `pending` entry records the `sessionId` it was sent with. An `operator_session_invalid` reply clears the local session only when that recorded id equals the currently active one. A late reply whose waiter already timed out cannot be attributed to a session and does not clear it. The next request with a dead session gets its own `operator_session_invalid`, so nothing is lost.

### 5.5 Vocabulary

`ErrorCode` stays a value class; unknown codes pass through intact. Seeded constants: `INVALID_ENVELOPE`, `PASSWORD_FIELD_FORBIDDEN`, `OPERATOR_SESSION_INVALID`, `ACTION_NOT_ALLOWED`, `MESSAGE_ID_CONFLICT`, `REV2_REJECTED`, `CLIENT_UPGRADE_REQUIRED`, `OUTCOME_UNCONFIRMED`, `AUTHENTICATION_FAILED`, `PURPOSE_NOT_ENABLED`. Every other constant is removed.

Handling:

| Code | App behaviour |
|---|---|
| `client_upgrade_required` | Latch `upgradeRequired`; `UpgradeGate` blocks the UI |
| `operator_session_invalid` | §5.4, then route to Login |
| `outcome_unconfirmed` | Show "Station 2 couldn't confirm that — try again". For a read, retrying is simply another read |
| `rev2_rejected` | Show `operatorMessage`; the snapshot in `data` is still applied |
| `password_field_forbidden` | "This app build sent credentials in a form Station 2 no longer accepts. Update the app." — a build defect, not a wrong password |
| anything else | Show `operatorMessage`, falling back to a generic line |

### 5.6 Compliance guards

| # | Guard | Location |
|---|---|---|
| 1 | Reject inbound messages with duplicate property names, case-insensitive, at any depth | strict `JsonReader` pass in `WireJson` before Gson |
| 2 | Throw before publish if any outbound field name at any depth contains `password`, case-insensitive (stricter than the server's top-level check) | `RequestEnvelope.build()` |
| 3 | Throw before publish if the serialized request exceeds 65,536 characters | `RequestEnvelope.build()` |
| 4 | Structured per-message log: direction, topic, QoS, retain, deviceId, suffix, `action`, `success`/`error`, duration | new `data/mqtt/MqttLog.kt` |
| 5 | Recursive redaction before any diagnostic write: `sessionId`, `clientProof`, `serverSignature`, `clientFinalWithoutProof`, `salt`, `serverFirstMessage`, `serverNonce`, `clientNonce`, `challengeId`, any key containing `password`, broker secrets | new `data/mqtt/Redact.kt` |

The Sept 10 `allowedTabs` fail-closed gate is **not** built: the server never sends the field and has confirmed it never will. Every request is authorized server-side by `sessionId`.

## 6. Auth and job lookup

### 6.1 Auth

- `ScramStartPayload(username, clientNonce, purpose = "login")`, `ScramProofPayload(challengeId, clientFinalWithoutProof, clientProof, purpose = "login")`.
- `ScramChallengeResponse` is unchanged in shape; it is now read from `data`.
- `ScramProofResponse(serverSignature, session: Rev2Session?)` with `Rev2Session(sessionId, operatorId, displayName, role, expiresAtUtc, sessionState, isActive)`.
- The proof waits on `scram_proof_result`.
- `ScramCrypto` and the constant-time `serverSignature` check are untouched. The signature is verified before `session` is read.
- `AuthUseCase.login(username, password)` builds `OperatorSession(operatorSessionId = session.sessionId, operatorId, operatorName = displayName, role, sessionState, sessionExpiresAtUtc)`. A missing session, blank `sessionId`, `isActive == false` or a closed state fails the login. `role` is display-only (e.g. `Worker`).

### 6.2 DTOs — `data/mqtt/dto/Rev2GeneralMessages.kt`

```kotlin
data class Rev2GeneralRequest(val action: String, val jobCard: String? = null, val targetId: String? = null)

data class Rev2GeneralSnapshot(
    val result: Rev2CommandResult? = null,
    val jobs: List<Rev2JobSummary> = emptyList(),
    val job: Rev2Job? = null,
    val preparations: List<Rev2Preparation> = emptyList(),
)
data class Rev2CommandResult(val success: Boolean = false, val message: String = "", val targetId: String? = null)
data class Rev2JobSummary(val id: String = "", val product: String = "", val closed: Boolean = false, val requiredMixes: Int = 0)
data class Rev2Job(
    val id: String = "", val product: String = "", val unit: String = "",
    val overallQuantity: Double = 0.0, val scopeQuantity: Double = 0.0, val sapCompletedAtLookup: Double = 0.0,
    val outputPerMix: Double = 0.0, val requiredMixes: Int = 0, val allocatedMixes: Int = 0,
    val capturedAtUtc: String? = null, val closed: Boolean = false,
    val materials: List<Rev2Material> = emptyList(),
)
data class Rev2Material(
    val code: String = "", val name: String = "", val unit: String = "",
    val perMix: Double = 0.0, val required: Double = 0.0, val collected: Double = 0.0, val excluded: Boolean = false,
)
data class Rev2Preparation(
    val id: String = "", val jobId: String = "", val mixCount: Int = 0, val mixed: Int = 0,
    val produced: Int = 0, val stage: String = "", val startedAtUtc: String? = null,
)
```

Unused snapshot fields (`machines`, `ingredientExceptions`, `requiredIngredientChoices`, `exceptionListRevision`, `preparation`, Rajoo fields) are not declared; Gson ignores unknown fields. Quantities are `Double` for display only; nothing is calculated from them except remaining-to-collect.

### 6.3 `JobLookupUseCase`

- `read(targetId: String? = null): JobLookupResult` → `rev2_general_requested`, `action:"read"`.
- `lookup(jobCard: String): JobLookupResult` → trims, rejects anything not `[0-9]+` on the device with "Scan or enter the production order number (digits only)", else sends `action:"lookup"`.
- Both map to the domain: `JobSummary`, `JobDetail(job, materials, preparations for that job)`, keeping `humaniseLookupRejection`'s role for `rev2_rejected` text.

### 6.4 UI

- **Job Lookup** — `read` on open and on reconnect. Lists `jobs` (JC, product, required mixes, closed badge). A scan from `ScanEventBus` or a typed number runs `lookup`, and on success navigates to detail.
- **Job Detail** — `read(targetId = jc)` on open. Header: JC, product, required mixes, allocated mixes, output per mix + unit, captured time. Material rows: code, name, per-mix, required, collected, remaining, unit; excluded rows dimmed with an "Excluded" tag. A read-only list of this job's preparations: id, mix count, mixed/produced, stage. No actions.
- **Push** — the ViewModel owning Job Lookup/Detail subscribes to `active_job_cards_invalidated` via the existing `serverPushHandler` and issues a fresh `read` with a new message id, keeping the current `targetId`. It never cancels an in-flight request.

## 7. Deliberately not changed

Connection, presence (QoS 2 retained `online`/`offline`, LWT), station presence tracking, reconnect, subscription retry and self-heal. The code carries three fixes that must survive: `connectWith()` off the Main dispatcher, the subscribe-retry path, and the `wantsConnection` guard against resurrecting `online`. `MqttStationPresenceTest`, `MqttClockSkewTest` and `MqttClientFactoryTest` must pass unchanged. Clock-skew now reads `sentAtUtc`, falling back to a push's `timestampUtc` — the only change in that path.

## 8. Verification

### 8.1 Simulator

`tools/backend-sim/` is converted to rev2.1, modelled on `Rev2ScannerProcessor.cs`:

| Component | Action |
|---|---|
| `handlers/mixing.py`, `ingredients.py`, `pallets.py` | delete |
| `handlers/jobcards.py` | replace with `handlers/rev2_general.py`: `read` and `lookup` over seeded jobs; other General actions → `action_not_allowed` for this slice |
| `handlers/scram.py`, `auth.py` | keep the crypto; replies wrap in `data`, proof returns `data.session`, `purpose ≠ login` → `purpose_not_enabled`, 60 s identical-body replay, `message_id_conflict` |
| `envelope.py` | rev2.1 validation (schemaVersion, deviceId match, messageId length, six-digit timestamp, duplicate names, `password` names, size) and rev2.1 reply builder |
| `sim.py` | subscribe to exactly the four suffixes; any other suffix → `client_upgrade_required`; a control hook to emit `active_job_cards_invalidated` |
| `selftest.py` | rewritten as the rev2.1 conformance suite |
| `tools/test-harness/` | delete drivers for deleted screens; keep `drive_login.py`, `drive_relogin.py`, `ui.py`, `sniffer.py`, `simctl.py` |

### 8.2 Tests

Test-first for each unit:
- **Envelope** — field set and order, `sessionId` present only with a session, no `correlationKey`, the `password` and size guards.
- **Response parsing** — body from `data`; `error: ""` is success-compatible; a rejection without `data`; `durationMs` as a double; unknown error codes preserved.
- **Duplicates** — a second direct reply is dropped; a repeated push `messageId` is suppressed.
- **Session** — `operator_session_invalid` clears only when the pending entry's session is current.
- **Auth** — session from `data.session`; missing or inactive session fails; signature is checked before the session is read.
- **Use case** — digits-only guard; snapshot mapping; remaining = `max(0, required − collected)`.
- **Inbound duplicate-name rejection** and **redaction** of each listed key.

`MqttRequestCorrelationTest`, `MqttRequestRetryTest`, `MqttResponseDeduplicationTest`, `MqttSessionExpiryTest`, `RequestEnvelopeTest`, `ResponseEnvelopeTest`, `MqttVocabularyTest`, `AuthUseCaseTest`, `OperatorSessionHolderTest` and `LoginViewModelTest` are rewritten to rev2.1. `Schema41EnvelopeTest` is replaced by an equivalent rev2.1 test.

### 8.3 On device

Login → job list → scan a production order number → detail, against the simulator, with a `sniffer.py` capture as evidence of the published format. Then one pass against the real WPF server when available.

## 9. Sequencing

| # | Commit | State after |
|---|---|---|
| 1 | Strip UI: board, ingredient scan, RFID recovery, badge login; flatten nav; Home one tile | builds; old 4.1 job lookup still wired |
| 2 | Strip domain/data: pallet, manager auth, Room, paging, `StationAction`/`canShow` | builds |
| 3 | Settings trim; `CLAUDE.md` path fixed to `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2` | builds |
| 4 | Simulator and harness converted to rev2.1 | simulator speaks rev2.1 |
| 5 | Transport: envelope, response/`data` parsing, `MqttOutcome`, vocabulary, correlation/session/dedup rules | **breaking** — app no longer speaks 4.1 |
| 6 | Auth on rev2.1 | login works against the simulator |
| 7 | `JobLookupUseCase`, DTOs, Job Lookup and Job Detail screens, push handling | **checkpoint** — slice working end to end |
| 8 | Compliance guards, logging, redaction; conformance suite green | **checkpoint** — on-device verification |

Every commit compiles and passes its unit tests. Between commits 5 and 6 the app cannot log in against any server; that window is accepted.

## 10. Deliverables

- An APK: login → job list → lookup → job detail, on rev2.1.
- A green rev2.1 `selftest.py` run.
- A `sniffer.py` capture of the published format.
