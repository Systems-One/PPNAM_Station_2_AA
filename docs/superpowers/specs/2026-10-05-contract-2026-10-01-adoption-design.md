# Adopting Station 2 contract revision 2026-10-01 on Android

**Date:** 2026-10-05
**Status:** Draft for review
**Supersedes (scope only):** the Non-goals of `2026-09-30-job-lookup-on-rev2-1-design.md` §1
**Authority:** `C:\Dev\Clients\PPNAM\Station 2\PPNAM-Station-2\RFID_MQTT_CONTRACT.md` as committed in sibling-repo commit `20a4a4c` ("mqtt", 2026-10-05). The heading reads "Wire schema `rev2.1`. Contract revision **2026-10-01**". Acceptance cases: `RFID_SCANNER_APP_TEST_CASES.md` in the same commit. Golden payloads: `DOCS/MQTT/Message_Examples/*.json` (exact request bytes plus complete responses, generated from the real processor).

The sibling repo is read-only, except for `RFID_MQTT_CONTRACT.md` (see `CLAUDE.md`). This work only reads it. It copies the example JSON into this repo as test fixtures.

## 1. Where we are

App v1.3.1 (versionCode 4) implements the 2026-09-30 slice:
- SCRAM login;
- General `read` and `lookup`;
- a read-only job list and detail.

It sends `schemaVersion: "rev2.1"`. The wire version string has not changed, so the server still accepts our requests. The contract around that version string has grown a lot.

## 2. What the 2026-10-01 contract changed

| Area | Change | Android impact |
|---|---|---|
| Response envelope | Adds `contractRevision` (`2026-10-01`), `requestFingerprint` (uppercase hex SHA-256 of the exact request bytes) and `deviceId`. `nextAction` becomes a stable token (§10), not prose | Parse the new fields. Branch on `error`, never on `nextAction` or `operatorMessage` |
| Request size | Limit is 65,536 **UTF-8 bytes**, not characters | `OutboundGuard` counts bytes |
| Errors | Adds `collection_revision_conflict`, `receipt_owner_mismatch`, `receipt_recovery_unavailable`, `receipt_sealed` and the `scram_*` family | Add these to the vocabulary and map each to a handling class (§4.2) |
| Snapshot `data` | Adds `recovery`, `mode`, `capabilities`, `collectionExceptions`, `commandExceptions`, `jobs[].mixProgress`, `job.mixProgress`, `job.collectionRevision`, `preparation.collectionRevision`, material `originalRequired`/`remaining`, and `result.cycleId`/`errorCode` | Full DTOs |
| Job mix progress (§8.1) | Job list and detail **must** show Required / Active / Available to prepare / Finished, and list active PREP IDs, counts and stages | UI change in Phase 1 |
| Durable commands (§2, §9) | Persist each unanswered mutation's exact bytes, family, ID and SHA-256 **before** publishing. Retry with identical bytes and ID while the session is valid. Otherwise log in again as the same operator and send `recover` | New outbox + recovery. A precondition for every mutating action |
| `recover` action (§4, §5, §9) | Both families. `originalMessageId` plus `originalRequestFingerprint` give `committed` / `rejected` / `not_executed` | Phase 1 |
| `adjust_ingredient` (§6) | Add / Substitute / Quantity / Omit, with `reason` (1–1000 chars) and the latest `collectionRevision`. `targetQuantity` is the whole-scope total | Phase 4 |
| Exceptions (§6) | Server-attributed records with review status. Show date/time (SAST), operator ID and details after each decision or rejection. Scanner review/clear is forbidden | Phase 4 |
| Hints (§11) | Now Rajoo too, and many more `reason` values. A hint never resolves a pending command | Read on any reason; Rajoo hints wait for Phase 6 |
| `prepare` / `rajoo_start` | Optional `reason` | Phases 2 and 6 |

The General actions `prepare`, `capture`, `confirm`, `machine_start` and `machine_finish`, and the whole Rajoo family, already existed in September. The app has never implemented them. The new contract makes them mandatory for commissioning: acceptance areas GEN, EXC, MIX, RAJ, REC and PUSH.

## 3. Delivery: six phases, one plan each

Each phase ships as its own branch and release, and leaves the app working. A plan is written when its phase starts, against the code as it then stands.

| Phase | Scope | Acceptance IDs | Depends on |
|---|---|---|---|
| **1. Wire foundation + durable commands** | Envelope fields, vocabulary, byte limit, subscription QoS, full snapshot DTOs, `mixProgress` on list/detail, fixture-driven tests, `CommandOutbox`, `sendCommand`/`retryCommand`, `recover`, recovery after login, an Unresolved-commands card on Home, simulator envelope update | MQTT-01..07 (client side), GEN-11 display, REC-02/03/04/05/07 client side, PUSH-02 | — |
| 2. General preparation and collection | Include/Exclude choices UI, `prepare` (mixCount ≤ available), resume PREP by `read`, `capture` per scanned bag/fraction (BigDecimal quantities), `confirm`, the not-executed "confirm replacement" prompt | GEN-02..13, REC-01 | 1 |
| 3. Machine cycles | `machine_start`/`machine_finish` using the exact server `cycleId`, with stage-driven next-action UI | MIX-01..04 | 2 |
| 4. Operator ingredient decisions + exceptions | `adjust_ingredient` (4 kinds), the reason dialog, the proposed-total preview (current/collected/proposed/difference/remaining), `collection_revision_conflict` → re-read and re-review, and an exceptions list with review status | EXC-01..10 | 2 |
| 5. Hints and refresh polish | Read on every hint reason; refresh after replies, reconnect and login; keep pending commands intact through hints | PUSH-01..03 | 2 |
| 6. Rajoo family | `rev2_rajoo_requested`: lookup (with machine), read, `rajoo_start` (first-session choices), capture, `adjust_ingredient`, `rajoo_finish`, `rajoo_close`, and recovery in the Rajoo family | RAJ-01..05 | 1, 2 and 4 (shared UI) |

## 4. Phase 1 design decisions

### 4.1 Outbox storage

Each command is one JSON file under `filesDir/outbox/`. It is written atomically (temp file, then `fsync`, then rename) and named by its UUID messageId.

There is no Room database. The app deleted its Room cache in September. A handful of files needs no schema, KSP or migrations. A command holds:
- messageId, request family, response family and action;
- targetId;
- the **exact published JSON string**, plus its SHA-256;
- operatorId and sessionId;
- the creation time;
- a status: `Unresolved` or `ManagerReconcile`.

On load the fingerprint is recomputed. A mismatch never drops the file: it is marked `ManagerReconcile`, because an unresolved capture must never vanish.

### 4.2 How each outcome is handled

| Reply | Class | Outbox |
|---|---|---|
| `success:true` | Settled | remove |
| `rev2_rejected`, `collection_revision_conflict`, `action_not_allowed`, `invalid_envelope`, `password_field_forbidden`, `client_upgrade_required`, `receipt_sealed` | Settled (definite; not executed, or executed as a rejection) | remove |
| No reply (timeout), publish failure after persisting, malformed reply, `outcome_unconfirmed` | Unresolved → retry identical bytes | keep |
| `operator_session_invalid` | Unresolved → sign in, then `recover` | keep |
| `message_id_conflict` | Unresolved → `recover` | keep |
| `receipt_owner_mismatch`, `receipt_recovery_unavailable` | Manager must reconcile | keep, mark `ManagerReconcile` |

If the device is not connected before publishing, nothing is persisted and the result is `NotConnected`: the command was never sent.

### 4.3 Recovery

- A new session for the **same operatorId** automatically sends `recover` for each of that operator's `Unresolved` commands, one at a time. Each `recover` has a fresh messageId, so repeating a recovery is safe.
- `committed`, `rejected` and `not_executed` remove the command and produce a notice for the UI. A `not_executed` notice tells the operator to re-read before doing the action again.
- Commands owned by a different operator are never recovered. Home shows them as "signed in as someone else — ask {operatorId} to sign in or a manager to reconcile".
- Sessions are in memory only, so a process restart always goes through login and then recover. That is the contract's restart path.

### 4.4 `nextAction`

Branching uses the `error` code alone (§4.2). `nextAction` is kept for logs. That satisfies "guidance tokens, not actions the client should execute blindly".

### 4.5 Contract revision

Every parsed reply records `contractRevision` in `MqttRepository.serverContractRevision`. A value other than `2026-10-01` is logged as a warning. It does not block anything: the wire version `rev2.1` is what the server enforces.

### 4.6 Quantities

Phase 1 keeps `Double` for display-only quantities, as today. Phase 2 moves outbound `quantity` and `targetQuantity`, and the comparisons against `remaining`, to `BigDecimal`, because §2 requires exact decimal comparison.

## 5. Out of scope for all phases

- Scanner review or clearing of exceptions, and approvals.
- Configuration or catalog maintenance (forbidden by §6).
- SAP mutations.
- Pallet or holding messages (not in the active contract).
- Editing the sibling repo beyond `RFID_MQTT_CONTRACT.md`.
