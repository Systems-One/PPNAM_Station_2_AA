# Strip Station 2 to job lookup, on the fleet MQTT base standard

**Date:** 2026-09-10
**Branch:** `feat/strip-to-job-lookup` (off `feat/jc-driven-mixing` @ `ecf1cc5`)
**Status:** Superseded by `2026-09-30-job-lookup-on-rev2-1-design.md` (rev2.1 contract landed)

## 1. Purpose

Reduce the PPNAM Station 2 Android app to a single working vertical slice — log in, look up a job card, view its BOM read-only — and put its MQTT layer fully on the fleet base standard `C:\Dev\Clients\PPNAM\Andriod\MQTT_BASE_README.md`. The result is the foundation a rebuild starts from, not a shippable production app.

### Authority decision

`MQTT_BASE_README.md` wins over `RFID_MQTT_CONTRACT.md` wherever they conflict. The Station 2 contract is being overhauled and is treated as outdated for this work. The practical consequence is recorded in §7.

### Non-goals

- Rebuilding collection, mixing, pallet handling or tag recovery. They are deleted.
- Keeping the app able to talk to the current Station 2 WPF backend. It cannot, by design — see §7.
- Rewriting connection, presence, or self-heal code. It is already compliant.

## 2. Current compliance against the base standard

`ecf1cc5` plus the 2026-08-17 topic restructure already satisfy most of the standard.

**Compliant, not to be touched:**

| § | Item | Evidence |
|---|---|---|
| 1 | `PPNAM/station_2/...` hierarchy; segment validation rejecting `/ + #`; `res/+` full-wildcard subscription | `MqttTopics.kt` |
| 2 | Derived `scanner_` + 12 hex device id, persisted once, shown in Settings → Diagnostics; separate `ScannerApp_XXXXXXXX` client id | `DeviceIdentity.kt`, `MqttClientFactory.kt` |
| 3 | Retained QoS 2 `online`/`offline` on base nodes; LWT; graceful offline-before-disconnect; presence self-heal; station presence tracked | `MqttRepositoryImpl.kt:263-336` |
| 4 | req/res at QoS 1 unretained; six-fractional-digit `Z` timestamps; optionals omitted; broker credentials transport-only; PUBACK never shown as business success | `MqttSchema.kt`, `RequestEnvelope.kt` |
| 5 | Full SCRAM-SHA-256; constant-time `serverSignature` verification before session acceptance | `ScramCrypto.kt:128-135` |
| 6 | Reconnect restores subscriptions and republishes retained `online` | `buildClient()` onConnected |

**Gaps this work closes:**

1. Workflow messages use the full 4.1 envelope; the standard specifies a lightweight one (§4).
2. Workflow error codes are `lowercase_snake_case`; the standard specifies `UPPERCASE_SNAKE_CASE` (§4).
3. Duplicate property names are not rejected — Gson silently last-wins (§4).
4. No client-side plaintext-credential guard before dispatch (§4).
5. No structured per-message logging (§7).
6. No redaction of secrets before diagnostic writes (§7).
7. `allowedTabs` — the list of workflows the operator may use — is stored on `OperatorSession` but read by nothing. §5 requires the app to enable exactly the listed workflows and to fail **closed** on a missing or empty list. Note this is a different field from `allowedActions`, whose fail-open `canShow()` is a UI control hint and is correct as written.
8. `requestTimeoutMs` defaults to 20 s; §4 specifies a 10 s workflow timeout.

`CLAUDE.md` also points at `C:\Dev\PPNAM-Station-2`, which no longer exists. The backend and contract now live at `C:\Dev\Clients\PPNAM\Windows\PPNAM-Station-2`. Fix the path and the read-only rule that references it.

## 3. Target end state

```
Login (SCRAM)  ->  Home  ->  Job Lookup  ->  Job Detail
                                             read-only BOM
                   Settings (broker config + diagnostics)
```

Two workflow messages on the wire, plus the four auth messages:

| Request | Response | Envelope |
|---|---|---|
| `scram_start_requested` | `scram_challenge` | auth (4.1) |
| `scram_proof_requested` | `scram_proof_result` | auth (4.1) |
| `login_requested` | `operator_context` | auth (4.1) |
| `reader_logout_requested` | `operator_context` | auth (4.1) |
| `active_job_cards_requested` | `active_job_cards_list` | workflow |
| `job_card_load_requested` | `bom_loaded` | workflow |

`active_job_cards_invalidated` is retained as an uncorrelated server push.

## 4. The strip

### 4.1 Delete outright

Git history is the record; recoverable via `git show ecf1cc5:<path>`.

| Area | Files |
|---|---|
| Mixing board | `ui/mixing/board/` (3 files, 1412 ln); `domain/usecase/MixingBoardUseCase.kt`; `domain/model/MixingBoard.kt`; `data/mqtt/dto/MixingMessages.kt` |
| Ingredient scan | `ui/mixing/IngredientScanScreen.kt` (1078 ln); `domain/model/IngredientScanOutcome.kt`; `data/mqtt/dto/IngredientMessages.kt` |
| Pallet / holding | `ui/rfid/` (2 files); `domain/usecase/PalletUseCase.kt`; `domain/model/PalletInfo.kt`; `data/mqtt/dto/PalletMessages.kt` |
| Manager approval | `data/auth/ManagerAuthorization.kt` — reachable only from ingredient approval, waiver, cancel and force-close, all deleted |
| Upgrade gate | `ui/components/UpgradeGate.kt`; the `upgradeRequired` latch and `client_upgrade_required` handling in `MqttRepositoryImpl` |
| Offline BOM cache | `data/local/` (`AppDatabase`, `BomCacheDao`, `BomCacheEntity`) and its `AppModule` bindings. Its only writer was `MixingUseCase`; §6 of the standard makes workflows stateless request/response. Removes Room from the build. |
| Tests | `IngredientScanScreenKtTest`, `MixingBoardScreenKtTest`, `MixingBoardViewModelTest`, `MixingAreaPickerScreenKtTest`, `MixingBoardUseCaseTest`, `PalletUseCaseTest`, `PalletStateTest`, `RfidViewModelTest`, `MixingMessagesTest`, `MixingOverviewWireCaptureTest`, `IngredientScanResultTest`, `BomCacheDaoTest` |

4,275 lines of main source across those files, plus 12 test files. A further ~1,000 lines go in the §4.2 trims (chiefly the lower two-thirds of `MixingViewModel` and the four dropped `MixingUseCase` methods).

**`data/rfid/` is kept.** `DataWedgeReceiver` and `ScanEventBus` are the hardware barcode input, not the tag-recovery flow. `LoginViewModel` uses it for badge login and job lookup needs it to scan a job card.

### 4.2 Split and trim

- **`ui/mixing/MixingViewModel.kt` (861 ln)** cuts at line 340. Above: connection status, session, logout, `lookupJob`, `loadActiveJobs`, `loadMoreActiveJobs`. Below: scan handling, all deleted. Becomes `ui/joblookup/JobLookupViewModel.kt`, ~230 lines.
- **`domain/usecase/MixingUseCase.kt`** keeps `lookupJob`, `fetchActiveJobCards`, `BomLoadedResponse.toProductionOrder()`, `BomLineResponse.toBomLine()` and `humaniseLookupRejection`. Drops `scanIngredient`, `waiveShortBags`, `cancelJob`, `recoverHolding`. Becomes `domain/usecase/JobLookupUseCase.kt`.
- **`data/mqtt/dto/JobCardMessages.kt`** keeps `ActiveJobCardsPayload`, `ActiveJobCardsListResponse`, `ActiveJobCardSummary`, `ActiveJobCardsInvalidatedResponse`, `JobCardLoadPayload`, `BomLoadedResponse`, `BomLineResponse`, `BagSizeOptionResponse`, `CollectionSummaryResponse`. Drops `CollectionResumePayload`, `IngredientCollectionCancelPayload`, `IngredientCollectionCancelResultResponse`.
- **`MqttVocabulary.kt`** — see §5.1; the two value classes are replaced by two families, each holding only codes the surviving messages can return.
- **`ui/home/HomeScreen.kt`** — three tiles become one.
- **`navigation/`** — both nested `navigation {}` graphs flatten. Routes reduce to `LOGIN`, `HOME`, `SETTINGS`, `JOB_LOOKUP`, `JOB_DETAIL`.
- **`ui/settings/SettingsScreen.kt` (460 ln)** — trimmed to broker connection config (host, port, TLS, certificate validation, username, password, request timeout) and a Diagnostics section showing the derived device id read-only, broker state, station presence and clock skew. §2.3 requires the device id be readable here for enrolment.

### 4.3 Build new

`ui/joblookup/JobDetailScreen.kt` — read-only rendering of `bom_loaded`: job card number, production order document number, collection status, `collectionSummary.summary`, and the `ingredients[]` list. No scanning, no actions, no mutations.

## 5. The transport

### 5.1 Two envelope families, enforced by the type system

Auth is unchanged: `messageId`, `schemaVersion: "4.1"`, `deviceId`, `timestampUtc`, optional `correlationKey`; correlated on `inResponseToMessageId`; replay identity `(deviceId, requestType, messageId)`; response dedup on response `messageId`; constant-time `serverSignature` check before session acceptance.

Workflow moves to the lightweight envelope:

```json
{
  "ts": "2026-09-10T07:02:00.000000Z",
  "deviceId": "scanner_5c64df8d86a8",
  "operatorSessionId": "sess-...",
  "jobCardNumber": "JC0001"
}
```

No `messageId`, no `schemaVersion`. Responses carry `ts`, `deviceId`, and echo the correlating fields.

- `RequestEnvelope.build()` splits into `AuthEnvelope.build()` and `WorkflowEnvelope.build()`.
- `ErrorCode` splits into `AuthErrorCode` (`lowercase_snake_case`) and `WorkflowErrorCode` (`UPPERCASE_SNAKE_CASE`). Separate value classes so the compiler rejects a cross-family comparison, which would otherwise be silent and always false.
- `WorkflowErrorCode` is seeded with the four common codes the standard names: `INVALID_PAYLOAD`, `AUTHENTICATION_REQUIRED`, `OPERATOR_SESSION_INVALID`, `ACTION_NOT_ALLOWED`. Station-specific codes are added when the rewritten contract defines them. Unknown codes pass through intact rather than failing the parse.

### 5.2 Correlation without `messageId`

The pending registry keys on **`(responseType, correlatorValue?)`**.

- A workflow request may declare a correlator: a field name and its expected echoed value. `job_card_load_requested` uses `jobCardNumber`, which `bom_loaded` already echoes. The waiter matches only a response echoing that value.
- With no correlator — `active_job_cards_requested`, which echoes nothing — the entry keys on `responseType` alone and **a newer request supersedes the older**, cancelling its waiter. This matches the semantics of a list refresh.
- Workflow timeout is 10 s per §4. `requestTimeoutMs` default drops from 20 s to 10 s and stays configurable in Settings.

Three properties change as a direct consequence. They are accepted, not overlooked:

1. **Retry is no longer idempotent by contract.** The 3-attempt loop republishing identical bytes is safe today only because `(deviceId, requestType, messageId)` makes the station deduplicate. Without `messageId` a retry is a genuine second request. Both surviving workflow messages are reads, so retry remains safe for them. To stop a future mutating message inheriting the retry loop by accident, the workflow request path takes a required `idempotent: Boolean`; only `true` enables retry.
2. **Duplicate response suppression narrows to auth.** `seenResponseIds` keys on response `messageId`, which workflow responses will not carry. Harmless for two read messages — re-rendering a list or a BOM is a no-op — but it is a real reduction from today and must be revisited before any mutating workflow message is added.
3. **The session-clear guard needs `operatorSessionId` echoed back.** Today a `session_required` rejection clears the local session only when its `operatorSessionId` matches the currently active one; that check exists because a late reply for a superseded session was logging operators out mid-action. The base standard does not say workflow responses echo `operatorSessionId`. Until the rewritten contract settles it, **fail safe: no echo present means do not clear the session.**

### 5.3 Compliance fixes

| # | Fix | Location |
|---|---|---|
| 1 | Reject duplicate property names, case-insensitive, at any depth | strict `JsonReader` pass in `WireJson`, on every inbound message |
| 2 | Reject `password` / `managerPassword` anywhere in an outgoing JSON tree, before publish | `AuthEnvelope` / `WorkflowEnvelope` build path |
| 3 | Structured per-message log: direction, topic, QoS, retain, deviceId, message type, result or error code, duration | new `data/mqtt/MqttLog.kt` |
| 4 | Recursive redaction of `password`, `managerPassword`, `clientProof`, `serverSignature`, `authorizationToken`, SCRAM verifier keys and broker secrets before any diagnostic write | new `data/mqtt/Redact.kt` |
| 5 | `allowedTabs` fail-closed: empty or missing enables no workflows | new `OperatorSession.canUseWorkflow(tab)`; gates the Home job-lookup tile |

On item 5: `allowedActions`/`canShow()` is **not** the field in question and is not changed. It is a UI control hint that deliberately fails open, and after the strip its three `StationAction` constants (force close, collection cancel, short-bag approval) all belong to deleted flows — so `StationAction`, `canShow()` and its nullable extension become dead code and are removed in §4.1. The `allowedActions` property stays on `OperatorSession`, parsed but unread, since login still returns it.

### 5.4 Deliberately not done

- **No subscription to `PPNAM/station_2/res/+`.** §1 requires apps to *tolerate* the station-broadcast tree; not subscribing satisfies that completely. Documented in `MqttTopics.kt` rather than adding a subscription with no consumer.
- **No edits to connection, presence, or self-heal.** Already compliant, and the code carries three hard-won fixes: `connectWith()` moved off the Main dispatcher to stop an input-dispatch ANR; the subscribe-retry path that avoids stranding a live-but-unsubscribed client in `DISCONNECTED`; and the `wantsConnection` guard stopping `disconnect()`'s own retained `offline` echo from resurrecting `online`. These get tests, not changes.

## 6. Verification

### 6.1 The simulator is the vehicle

`tools/backend-sim/` (2,022 lines) is a Python Station 2 simulator with a handler registry mirroring the app's message families. It is stripped and converted in lockstep with the app, because it is the only thing the new wire format can be tested against.

| Simulator component | Action |
|---|---|
| `handlers/mixing.py` (830 ln), `ingredients.py` (282), `pallets.py` (103) | delete |
| `handlers/jobcards.py` (309) | trim to `active_job_cards_requested` and `job_card_load_requested` |
| `handlers/auth.py`, `scram.py`, `common.py` | keep — auth is unchanged |
| `envelope.py` (268) | split into `build_auth_response` / `build_workflow_response`; reject a workflow request carrying `messageId` or `schemaVersion` |
| `selftest.py` (752 ln) | rewrite around the two surviving messages; becomes the base-standard conformance suite |
| `tools/test-harness/drive_mix_*.py`, `board.py`, `collect*.py`, `d_assign_*.py` | delete — they drive deleted screens |
| `tools/test-harness/drive_login.py`, `drive_relogin.py`, `ui.py`, `sniffer.py`, `simctl.py` | keep |

`sniffer.py` is the evidence that the wire format is what this spec claims.

### 6.2 Test strategy

Test-driven throughout. Each compliance fix is a natural test-first unit:

- **Duplicate property names** — `{"a":1,"A":2}` and nested variants assert rejection.
- **Plaintext-credential guard** — `build()` throws on `password` / `managerPassword` at any depth.
- **Redaction** — every named secret is scrubbed from a diagnostic write.
- **`allowedTabs` fail-closed** — empty list enables no workflow. This inverts existing behaviour, so the fail-open expectation in `OperatorSessionHolderTest` is flipped.
- **Envelope split** — a workflow request carries no `messageId` or `schemaVersion`; an auth request still carries both.

Correlation gets a suite replacing `MqttRequestCorrelationTest`: match by echoed `jobCardNumber`; a correlator-less response completing the single outstanding waiter; a newer request superseding an older one; the 10 s timeout.

`MqttStationPresenceTest`, `MqttClockSkewTest` and `MqttRepositoryImplTest` are kept and must pass **unchanged**. A break in them means §5.4 was violated.

## 7. Accepted consequence

Once §5 lands, the app cannot communicate with the current Station 2 WPF backend. That backend speaks the 4.1 envelope with `lowercase_snake_case` codes on every workflow message. This is the direct result of choosing the base standard over the contract and is accepted for this work. Production use requires the backend overhaul to ship first.

Three questions the rewritten contract must answer, to be handed over at completion:

1. Do workflow responses echo `operatorSessionId`? (Required by §5.2 item 3.)
2. What field correlates `active_job_cards_list` to its request, if not supersession?
3. What is the Station 2 `UPPERCASE_SNAKE_CASE` workflow error-code set beyond the four common codes?

## 8. Sequencing

New branch `feat/strip-to-job-lookup` off current HEAD. Not off `master` — `master` is 41 commits behind and would lose the icon pack, the tag-triggered release workflow and the base-standard commit.

| # | Commit | State after |
|---|---|---|
| 1 | Strip UI: board, ingredient scan, RFID recovery, upgrade gate, their tests; flatten nav | builds; job lookup works on the old envelope |
| 2 | Strip domain and data: use cases, DTOs, models, Room cache; trim vocabulary | builds |
| 3 | Split `MixingViewModel` / `MixingUseCase` into `ui/joblookup/` and `JobLookupUseCase`; add `JobDetailScreen` | **checkpoint** — full slice working against the current simulator |
| 4 | Strip the simulator and harness to match | simulator serves the two messages |
| 5 | Envelope split, error-code families, correlation rework — app and simulator together | **breaking**; works only against the new simulator |
| 6 | The five compliance fixes; 10 s workflow timeout; fix the stale `CLAUDE.md` path | **checkpoint** — conformance suite green |

On-device verification at commits 3 and 6.

## 9. Deliverables

- A working APK: login → job lookup → job detail.
- A green `selftest.py` conformance run.
- A `sniffer.py` wire capture showing the actual published format.
- The three open contract questions from §7, written up for the backend rewrite.
