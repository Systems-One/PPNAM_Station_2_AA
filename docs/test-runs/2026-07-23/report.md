# Test run — 2026-07-23

**Device:** Chainway C72 `HC720DE260100322`, 1080×1920 @ 480 dpi
**App:** `com.mitas.ppnam.station2aa` v1.0 (1), branch `master` + this session's fixes
**Broker:** `mqtt.sysone.co.za:443` WSS/TLS
**Backend:** **live Station 2** (`PPNAM/station_2/status` retained `online`)
**Sim blocks:** not run — deferred by request until the backend fixes land

Verdicts: PASS · FAIL(app) · FAIL(backend) · BLOCKED · N/A

**Headline: 0 app defects outstanding.** Six were found and fixed during the run; everything
still failing is backend-side or blocked on backend data.

---

## Credentials

The live backend carries the **same seeded accounts as the simulator**. `Jono` / `Avi` no
longer exist on it.

| Username | Password | Role | allowedActions |
|---|---|---|---|
| `operator1` | `pass` | Operator | 3 — `scan_ingredient`, `start_machine_cycle`, `finish_machine_cycle` |
| `manager1` | `secret` | Manager | 7 — the above plus `ingredient_approve_override`, `ingredient_approve_short_bag`, `ingredient_collection_cancel`, `machine_force_close` |

---

## Gates 1–3

| ID | Verdict | Evidence |
|---|---|---|
| G1.1 build | **PASS** | `assembleDebug` exit 0 |
| G1.2 lint | **PASS** | 0 errors, 68 warnings, 1 info |
| G1.3 warnings | **PASS** | 57 of 68 are `GradleDependency`; none new |
| G2.1 unit suite | **PASS** | **634 → 646 tests, 0 failures** (12 added this session) |
| G2.2 no skips | **PASS** | skipped = 0 |
| G3.1 sim selftest | **PASS** | `selftest.py --direct` — **109/109** |
| G3.2–G3.5 | **PASS** | covered by the selftest run |

**Lint note — not a defect.** `CheckResult` on `MqttClientFactory.kt:32`. HiveMQ's builder
mutates in place and returns `self`, and the app connects over WSS:443 with its retained
presence on the broker, so TLS is applied. Style warning only.

---

## §4.1 Connection & transport

| ID | Verdict | Evidence |
|---|---|---|
| A1 cold start | **PASS** | Reconnecting → Connected in 14.3 s |
| A4 mid-session drop | **PASS** | airplane on → **Reconnecting**, presence cleared |
| A5 auto-reconnect | **PASS** | airplane off → Connected in ~30 s (HiveMQ max backoff) |
| A6 LWT on kill | **PASS** *(caveat)* | clean cycle: will fired **0.1 s** after `force-stop` |
| A8 presence announce | **PASS** | retained `PPNAM/handheld_1/status` = `online` |
| A13 offline request | **PASS** | "Not connected to Station 2", **no wire frame** |
| A21 topic validation | **PASS** | via G2.1 |
| A2, A7, A22 | BLOCKED | pair with §4.9, not run |
| A3 strand bug | **BLOCKED** | `mosquitto-denysub.conf` does not exist |
| A9–A11, A14–A20 | **N/A** | simulator blocks, deferred |
| A12 clock skew | BLOCKED | deferred |

**A4 — the plan was wrong, not the app.** It expected "Reconnecting, then Offline".
`handleTransportDisconnected` sets `RECONNECTING` and stays there while HiveMQ retries;
`DISCONNECTED` is only reached via explicit `disconnect()` or a failed connect. **Plan
corrected.**

**A6 caveat.** A first attempt after repeated airplane-mode cycling produced no will within
115 s; a clean launch→kill cycle produced it in 0.1 s. Not explained — re-observe next run.

---

## §4.2 Auth, session & roles

| ID | Verdict | Evidence |
|---|---|---|
| B1 login | **PASS** | `operator1` → Job Lookup, Login popped off the stack |
| B2 wrong password | **PASS** | "Invalid credentials.", username retained, fields usable |
| B6 barcode ≠ login | **PASS** | barcode broadcast → **no wire frame at all** |
| B6 RFID = login | **PASS** | RFID broadcast → `login_requested{badgeTag}` |
| B8 retry from Error | **PASS** | second attempt reached the wire |
| B10 top-bar logout | **PASS** | confirm dialog → `reader_logout_requested` → Login |
| B15 role gating | **PASS** | Operator hides Cancel + Short bags; Manager shows both |
| B7, B9, B11, B12, B14, B17 | not run | time; unblocked, carry to next run |
| B3–B5, B13, B16 | **N/A** | simulator blocks |

---

## §4.3 Job lookup

| ID | Verdict | Evidence |
|---|---|---|
| C1 list on entry | **PASS** | 3 collections listed |
| C1b back-nav refresh | **PASS** | refetch on return |
| C1c **resume refresh** | **PASS** | background 6 s → resume → `active_job_cards_requested` 3→4 *(fix made this session)* |
| C2 row content | **FIXED** | was "0%" for a ready collection; now `COL_000002 · Ready to mix` |
| C3 duplicate job cards | **PASS** | three collections render distinctly, no crash |
| C5 numeric keypad | **PASS** | editor inputType `0x2` = TYPE_CLASS_NUMBER |
| C6 Look Up gating | **PASS** | blank tap → no wire frame |
| C7 valid order | **PASS** | → Scan Ingredients |
| C8 unknown order | **FIXED** | raw SAP jargon reached the operator |
| C10 tap resumes | **PASS** | `collection_resume_requested` +1, load unchanged |
| C13 arming resets | **PASS** | no line armed on a fresh load |

---

## §4.4 Ingredient collection

| ID | Verdict | Evidence |
|---|---|---|
| D2 unarmed scan | **PASS** | no dialog, **no wire frame** (snackbar expired before capture) |
| D5 tap to arm | **PASS** | ARMED badge, hint clears |
| D7 dialog routing | **PASS** | bagged line → "Bag size & count" |
| D8 round-up pre-fill | **PASS** | needs 2.79 bags → pre-filled **3** |
| D10 dialog context | **PASS** | line, material, "Still required: 2.79 bags of 25.000 kg", pallet |
| D11 short warning | **PASS** | "2.00 bags is short of the 2.79 required…" |
| D12 zero refused | **PASS** | Confirm inert, dialog stays open |
| D13 fractions | **PASS** | exactly 0 / ¼ / ½ / ¾ |
| D19 non-blocking | **PASS** | line cards still rendered during the request |
| D33 rejection clean | **PASS** | returns to the list, nothing lost |
| D21–D32, D34–D42, D-INT | **BLOCKED(backend)** | no pallet exists — see B-1 |

**The collection path cannot be driven end-to-end.** All 114 generator tags plus the 3 DEMO
tags return `found: false`, so every scan is rejected `not_found` and nothing downstream of
an accepted scan is reachable.

Bag arithmetic on the one job card inspected was **internally consistent** (69.63 kg ÷
25 kg/bag = 2.79 bags ✓ across all 7 lines), consistent with the earlier finding that the
20 kg/bag discrepancy is one bad pallet row rather than a systemic conversion fault.

---

## §4.5 Mixing board

| ID | Verdict | Evidence |
|---|---|---|
| E2 five areas | **PASS** | DOLCI 6 · Main 29 · JANDI 5 · Mackie 2 · Rajoo 5 = 47 machines |
| E2b no empty area | **PASS** | Mackie returns 2 — the earlier null-collections issue is gone |
| E5 both load calls | **PASS** | overview + ready collections; `COL_000002` listed |
| E9 selection highlight | **PASS** | "Selected: COL_000002", Clear appears |
| E18 Rajoo dose rows | **PASS** | 7 rows from collected lines, each capped at its quantity |
| E19 dose validation | **FIXED** | all three rules refuse client-side; the reason was invisible |
| E20 dose counter | **PASS** | "0 / 5 doses entered" |
| E21–E26 | **BLOCKED(backend)** | need a collected collection |

---

## §4.6 RFID pallet lookup

| ID | Verdict | Evidence |
|---|---|---|
| F1 RFID triggers | **PASS** | lookup fires on RFID broadcast |
| F3 not found | **FIXED** | **crashed the app**; now "Pallet Not Found" + Station 1 guidance |
| F9 exits | **PASS** | Scan Another / Done |
| F4–F8 | **BLOCKED(backend)** | no pallet on this backend is `found` |

---

## §4.8 Layout

| ID | Verdict | Evidence |
|---|---|---|
| I1 top-bar clearance | **PASS** | title y1=136, pill y1=149, both ≥ 72 px status bar |
| I2 bar variant | **PASS** | operator row y144–192 above title row y264–324 |
| I3 IME shrinks content | **PASS** | top bar y1 = 136 with keyboard closed **and** open |
| I5 pill not squeezed | **PASS** | pill 160 px, operator label untruncated |
| I4, I6 | not run | carry to next run |

---

## §4.9 Settings

| ID | Verdict | Evidence |
|---|---|---|
| S1 pre-login access | **PASS** | Diagnostics + Configuration, **no** Session card |
| S2 PIN length cap | **PASS** | 10 typed, 6 retained |
| S3 attempt feedback | **PASS** | "4 / 3 / 2 / **1 attempt** left" — correct singular |
| S4 lockout | **PASS** | 5th → "Try again in 30s"; correct PIN refused mid-window; unlocked after |
| S5 counter reset | **PASS** | re-entry → locked, first failure reads "4 attempts left" |
| S6 fields editable | **PASS** | all 9 controls present and reachable |
| S7 bad numeric input | **PASS** | `abc` into Port → stays `443`, no crash |
| S8–S10 | not run | would mutate device config; deferred to a full run |

**No device configuration was changed.**

---

# Findings

## Fixed this session — app

| # | Severity | Defect |
|---|---|---|
| **1** | **Critical** | **Crash on any unknown pallet.** A not-found `pallet_lookup_result` omits `palletState`; Gson writes that null into the non-null DTO field (it does not honour Kotlin nullability — a data-class default applies only when the key is *absent*), and `PalletState.fromWire` threw NPE. `MixingArea.fromWire` and `SessionState.fromWire` already took `String?`; this was the lone outlier. **+4 regression tests** |
| 2 | High | **Ready collections displayed as "0%".** All four progress fields arrive null; `progressPercent: Double = 0.0` rendered that as a confident 0%, so a `ReadyForMixing` collection looked untouched. Now nullable, and the row leads with `status` — the one field the backend fills reliably |
| 3 | Medium | **Dose validation refused silently.** The reason rendered *below* seven scrollable fields while Start stayed pinned in view, so Start appeared to do nothing. Moved above the fields |
| 4 | Medium | **Raw SAP jargon reached the operator.** The backend reworded unknown-job-card rejections; the humaniser no longer matched. Now covers all three known phrasings, verbatim pass-through preserved. **+2 tests** |
| 5 | Medium | **Stale active-jobs list after backgrounding** (C1c) — `ON_RESUME` refresh |
| 6 | Low | **Latent NPE** on `unit.ifBlank` in the BOM mapper — same Gson null hole, on every job card load |

## Outstanding — backend

| # | Issue |
|---|---|
| **B-1** | **No pallets exist.** All 117 known tags return `found: false`. Blocks the whole collection path, the RFID detail tests and every mixing-cycle test — roughly 40 tests |
| B-2 | `active_job_cards_list` sends `progressPercent`, `requiredIngredientCount`, `completedIngredientCount`, `pendingApprovalCount` as **null** for every collection |
| B-3 | BOM lines report `availableQuantity: 9999.00` uniformly — a stub, not real stock |

**Latency is dramatically better:** job card load **70 ms**, mixing overview **17 ms**, scan
**11 ms** — against 1 382–9 771 ms previously. The old finding B2 looks resolved.

## Systemic exposure — needs your decision

**73 non-nullable `String`/`List` fields across the wire DTOs share the mechanism behind
finding 1.** Gson cannot see Kotlin nullability, so any of them arriving as JSON null is
written in as null and detonates at first use. This run found two by testing (`palletState`,
`unit`). A blanket Gson adapter is **not** safe — it would coerce legitimately-nullable
fields (`reason: String?`) to `""` and break every `?:` fallback. Options:

1. Audit the 73 against what Station 2 actually sends, and make the honest ones nullable.
2. Add a `TypeAdapterFactory` that reads Kotlin reflection metadata and coerces null to the
   declared default only for fields Kotlin marks non-null.
3. Leave it and fix crashes as they surface.

---

## Harness defects found and fixed

These produced false failures before being corrected.

| # | Defect | Fix |
|---|---|---|
| H-1 | `find("Log In")` matched the **top-bar title**, so taps hit the app bar and did nothing — B8 and A13 both reported false failures | `ui.py` — `TOP_BAR_MAX_Y`, content-first resolution |
| H-2 | With the IME open a Compose button is clipped to ~23 px and its Text drops out of the a11y tree, so it is unfindable | `close_ime()` before every tap; reject targets under 40 px |
| H-3 | uiautomator XML-escapes labels: "Test & Apply" arrives as `Test &amp; Apply` and never matched | `html.unescape()` on every attribute |
| H-4 | Compose buttons are unlabelled `View`s wrapping a `Text` | `tappable_for()` walks to the smallest clickable ancestor |
| H-5 | Row labels compose several fields (`COL_000001 · 0%`), so exact matching missed them | `tap(..., exact=False)` |

`tools/test-harness/ui.py` is new and is now the mandated entry point for driving the app.

---

## Still outstanding

1. **Pallet data on the live backend** — blocks ~40 tests. Backend-side.
2. **`mosquitto-denysub.conf`** — blocks A3.
3. **Simulator window** — blocks fault injection, recovery, E24 (deferred by request).
4. **Not run for time:** B7, B9, B11, B12, B14, B17, I4, I6, S8–S10, H1–H9. All unblocked.
