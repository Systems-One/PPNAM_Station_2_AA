# Station 2 UI Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every Station 2 finding (S2-01..S2-16 and the S2 rows of the static audit) from the 2026-10-02 PPNAM handheld UI audit without touching the rev2.1 wire protocol, broker defaults or session-restart semantics.

**Architecture:** All changes stay inside the existing Compose M3 + Hilt + Navigation-Compose app at `C:\Dev\Clients\PPNAM\Station 2\PPNAM_Station_2_AA` (branch `feat/strip-to-job-lookup` → new branch `fix/ui-audit-2026-10-02`). Tier 1 is manifest/theme one-liners; Tier 2 is per-screen Compose and ViewModel fixes (login validation, PIN lockout persistence, settings drafts/validation, connection-pill seeding); Tier 3 adds the single-attempt 10 s request timeout with Retry, the dismissable upgrade gate, and an S1-style inactivity auto sign-out with reason text. State that must survive the screen (PIN lockout, auto sign-out minutes) is persisted in SharedPreferences/DataStore; everything else stays in ViewModels.

**Tech Stack:** Kotlin 2.0.0, AGP 8.4.2, Compose BOM 2024.12.01 (Material3 1.3.x, foundation 1.7.x), Hilt 2.51.1, Navigation-Compose 2.7.7, DataStore Preferences 1.1.1, HiveMQ 1.3.3, JUnit4 + mockito-kotlin 5.1.0 + kotlinx-coroutines-test 1.8.1 for JVM unit tests. No new dependencies (in particular no `lifecycle-runtime-compose`; keep `collectAsState()`).

**Spec:** `C:\Users\Jonathan\AppData\Local\Temp\claude\C--Dev-Clients-PPNAM\ba7a1680-4205-4b04-bcb6-1b1f23c94914\scratchpad\audit\CONSOLIDATED_REPORT.md` (§3 root causes, §4 findings, §5 consistency matrix "Recommended standard", §6 keyboard matrix, §7 fix order), with `station2.md` (dynamic audit) and `static_consistency.md` (static audit) in the same folder, and the planning brief `PLANNING_BRIEF.md`.

## Global Constraints

- Repo: `C:\Dev\Clients\PPNAM\Station 2\PPNAM_Station_2_AA`; package `com.mitas.ppnam.station2aa`; minSdk 26, targetSdk/compileSdk 35. Ignore `.claude\worktrees\`.
- **Pre-existing, untouched** (dirty before this work started — never stage it): `tools/test-harness/__pycache__/sweep2.cpython-314.pyc`.
- **Do not change** broker/credential defaults (`AppSettings.mqttHost = "mqtt.sysone.co.za"`, `mqttPort = 443`, WebSocket on, TLS on, blank username/password), MQTT topics, payloads, `MqttSchema.VERSION = "rev2.1"`, SCRAM, or the in-memory-only `OperatorSessionHolder` restart semantics (session is still forgotten on process restart — S2-15 is out of scope by the brief). Badge login (static-03) is out of scope.
- Supervisor PIN stays `079545`, 5 attempts, 30 s lockout. Request timeout default stays `AppSettings.requestTimeoutMs = 10_000L`.
- Strings glossary (verbatim): pill `Connected` / `Reconnecting` / `Offline` / `Station 2 offline` / `Clock out of sync`; Diagnostics broker row uses the same three words as the pill; station row `Online` / `Offline` / `Unknown`; timeout `Station 2 did not respond. Check the station and retry.` with an explicit `Retry` button; login empty check `Please fill in all fields`; wrong password `Incorrect username or password`; dialog confirm `Log out` (never "Log Out"); exit dialog `Close the app?` / `You'll leave PPNAM Station 2 and return to the home screen.` [Stay | Close]; auto sign-out reason plural `Signed out after %1$d minute(s) of inactivity.`; PIN copy `Incorrect PIN. N attempt(s) left before lockout.` / `Too many attempts. Try again in Ns.`
- Colour: primary = launcher-icon green `#1D6B45` (README app2 direction); errors in `DangerRed #E25C5C`; orange only for warnings/pending.
- Every `<activity>` is portrait-locked and declares `android:windowSoftInputMode="stateHidden|adjustResize"`.
- Build: `.\gradlew.bat :app:assembleDebug --offline` from the repo root (drop `--offline` if a dependency is missing). APK: `app\build\outputs\apk\debug\app-debug.apk`. Unit tests: `.\gradlew.bat :app:testDebugUnitTest --offline` (add `--tests "<fqcn>"` for one class). There is no Robolectric and the only `androidTest` is a hover-crash regression test; Compose UI is therefore verified **manually on the emulator** plus the compile check, as stated in each task.
- Emulator: `emulator-5556`; adb `C:\Users\Jonathan\AppData\Local\Android\Sdk\platform-tools\adb.exe`. The emulator currently has the **debug-signed v1.2.0 (2)** build, so `adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk` works without uninstalling. Never target `HC720DE260100322` (the real C72). Fake backend settings: host `10.0.2.2`, port `9001`, WebSocket on, TLS off, user/pass `test`/`test`; PIN `079545`; logins `operator1`/`pass`, `manager1`/`secret`; job cards `510019068` (held), `510018531` (closed). Timeout mode: `python <SP>\fake_stations\set_mode.py --device scanner_40db7f6eef44 --mode timeout` (`--mode clear` to reset; `error` for the upgrade gate). Scan broadcast: `adb -s emulator-5556 shell am broadcast -a com.mitas.ppnam.station2aa.ACTION_SCAN -p com.mitas.ppnam.station2aa --es com.symbol.datawedge.data_string 510019068 --es com.symbol.datawedge.source scanner --es com.symbol.datawedge.label_type LABEL-TYPE-CODE128`.
- Keyboard check recipe: tap the field, `adb -s emulator-5556 shell dumpsys window | findstr ITYPE_IME` gives the IME top (≈1023 px text keyboard, ≈1155 px numeric); `adb -s emulator-5556 shell uiautomator dump /sdcard/ui.xml && adb -s emulator-5556 pull /sdcard/ui.xml` and confirm the primary button's bottom bound < IME top. Crash check after every manual step: `adb -s emulator-5556 shell logcat -d -s AndroidRuntime:E`.
- Git: commit after every task, only the task's files (`git add <paths>`, never `git add -A`). Every commit message ends with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q
  ```
- Deployment: `C:\Dev\Clients\PPNAM\PPNAM_Provisioning\apks\PPNAM_Station_2_AA.apk` is a **stale v1.0 (1) schema-4.1 build** the backend rejects with `invalid_envelope`. The provisioning bundle must be rebuilt from this branch after Task 16 (the emulators were audited on the v1.2.0 source build for the same reason).
- After the last task, run `graphify update .` (repo CLAUDE.md rule) — do not commit `graphify-out/` changes with code tasks.

## Review Focus

1. **Device clock moved backwards during a PIN lockout** — `lockedOutUntilMs` is wall-clock and persisted; a clock correction could make the remaining time exceed 30 s or never end. Expected: a lockout never lasts longer than 30 s of real time. Pinned by `a lockout whose deadline is implausibly far ahead is treated as expired` in Task 11.
2. **Port text `0`, `65536` or empty, Request timeout `0`, Auto sign-out `1441`** — must show an inline error and never reach `reconnectWith`. Pinned by `invalid port, timeout and auto sign-out are rejected inline and nothing is sent` in Task 11.
3. **Enter on an empty login form** — must show `Please fill in all fields` and must not send a `scram_start_requested` with a blank username (S2-07's raw backend text). Pinned by `blank username or password shows the fill-all-fields message and sends nothing` in Task 7.
4. **A reply arriving after the single 10 s timeout** — must be dropped silently (no crash, no late navigation). Pinned by `a reply arriving after the timeout is ignored` in Task 14.
5. **Manual logout vs inactivity sign-out** — only the inactivity path may show a reason on Login; a manual "Log out" must not show "Signed out after … inactivity", and the reason must show once. Pinned by `a manual logout leaves no signed-out reason` (Task 15, holder test) and `a signed-out reason is shown once and then consumed` (Task 15, LoginViewModel test).

---

### Task 1: Branch and baseline

**Files:**
- none modified

- [x] **Step 1: Record the dirty working tree**

Run (PowerShell, repo root):
```powershell
git status --porcelain
```
Expected: exactly one line, ` M tools/test-harness/__pycache__/sweep2.cpython-314.pyc`. Leave it alone for the whole plan.

- [x] **Step 2: Create the fix branch from the current branch**

```powershell
git branch --show-current     # expect feat/strip-to-job-lookup
git switch -c fix/ui-audit-2026-10-02
```

- [x] **Step 3: Baseline build and tests (must be green before any change)**

```powershell
.\gradlew.bat :app:assembleDebug --offline
.\gradlew.bat :app:testDebugUnitTest --offline
```
Expected: `BUILD SUCCESSFUL` for both. If `--offline` fails on a missing artifact, rerun without it once and then keep `--offline`.

No commit for this task.

---

### Task 2: Portrait lock + `stateHidden|adjustResize` (Tier 1)

Closes: S2-02 (Settings pops to Login on rotation), S2-11 (landscape list squeeze), §7 item 1 and 2 for S2.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:19-29`

- [x] **Step 1: Edit the activity element**

Replace lines 19–24 of `app/src/main/AndroidManifest.xml`:
```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@style/Theme.PPNAMStation2AA"
            android:windowSoftInputMode="adjustResize">
```
with:
```xml
        <!-- Portrait only: the C72 has auto-rotate on, and a knocked handheld used to recreate
             the Activity mid-typing — login fields wiped, Settings draft lost (audit S2-02/S2-11).
             A scanner gains nothing from landscape. stateHidden keeps the keyboard closed on
             first entry so the login card is seen whole before a field takes focus. -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.PPNAMStation2AA"
            android:windowSoftInputMode="stateHidden|adjustResize"
            tools:ignore="DiscouragedApi">
```
(`xmlns:tools` is already declared on line 3.)

- [x] **Step 2: Compile and verify on the emulator** (emulator check deferred)

```powershell
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 shell settings put system accelerometer_rotation 0
adb -s emulator-5556 shell settings put system user_rotation 1
```
Expected: the app stays portrait (screenshot `cmd /c "adb -s emulator-5556 exec-out screencap -p > rot.png"` is 1080 wide). Open Login → gear → Settings, rotate (user_rotation 1 then 0): the app stays on Settings. Restore: `adb -s emulator-5556 shell settings put system user_rotation 0`.

- [x] **Step 3: Commit**

```powershell
git add app/src/main/AndroidManifest.xml
git commit -m "fix(ui): lock Station 2 to portrait and hide the keyboard on entry

Rotation recreated the Activity mid-typing: login fields were wiped and
Settings popped back to Login (audit S2-02, S2-11).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 3: Brand colour = icon green, rename the misnamed token (Tier 1)

Closes: S2-16, static-02 (S2 half), §5 "Accent vs icon direction".

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/theme/Color.kt:15-17,23-24`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/theme/Theme.kt:11-12`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/components/StatusCard.kt:43,84`
- Modify (mechanical rename): `ui/components/AppScaffold.kt`, `ui/joblookup/JobLookupScreen.kt`, `ui/login/LoginScreen.kt`, `ui/settings/SettingsScreen.kt`

**Interfaces:**
- Produces: `BrandPrimary: Color` (`0xFF1D6B45`), `OnBrandPrimary: Color` (white). `AmberPrimary`/`AmberDark` no longer exist. `InfoBlue` (`0xFF2E77F5`) stays and is now distinct.

- [x] **Step 1: Replace the token definitions in `Color.kt`**

Replace lines 15–17:
```kotlin
// Primary accent — matches WPF BlueColor / BlueDarkColor
val AmberPrimary           = Color(0xFF2E77F5)
val AmberDark              = Color(0xFFFFFFFF)   // on-primary (white text on blue buttons)
```
with:
```kotlin
// Primary accent — the Station 2 launcher-icon green (UI_Design README: app2 #1D6B45), so the
// app carries its icon identity on screen the way Stations 1/3/5 do. Was a blue misnamed
// "AmberPrimary" that made S2 and S4 look like the same app (audit S2-16 / static-02).
val BrandPrimary           = Color(0xFF1D6B45)
val OnBrandPrimary         = Color(0xFFFFFFFF)   // on-primary (white text on green buttons)
```
Leave `InfoBlue = Color(0xFF2E77F5)` (line 24) as is — it is the "running" tone and no longer duplicates the primary.

- [x] **Step 2: Mechanical rename across the UI sources**

Run in Git Bash from the repo root (sed is available there; PowerShell users: `Get-ChildItem -Recurse app/src/main/java -Filter *.kt | ForEach-Object { (Get-Content $_.FullName -Raw) -replace '\bAmberPrimary\b','BrandPrimary' -replace '\bAmberDark\b','OnBrandPrimary' | Set-Content -Encoding utf8 -NoNewline $_.FullName }`):
```bash
grep -rl 'AmberPrimary\|AmberDark' app/src/main/java | xargs sed -i 's/\bAmberPrimary\b/BrandPrimary/g; s/\bAmberDark\b/OnBrandPrimary/g'
grep -rn 'AmberPrimary\|AmberDark' app/src   # expect no output
```
`Theme.kt:11-12` now reads `primary = BrandPrimary, onPrimary = OnBrandPrimary`.

- [x] **Step 3: Keep the "running" job tone blue in `StatusCard.kt`**

Two greens (Ready `SuccessGreen` and Running `BrandPrimary`) would be indistinguishable on a job card. Change line 43 from `Running -> BrandPrimary` to:
```kotlin
        Running -> InfoBlue
```
and add `import com.mitas.ppnam.station2aa.ui.theme.InfoBlue` next to the other theme imports (line 25 area). The `highlighted -> BrandPrimary` border on line 84 stays (it is the "act on this" signal, 3 dp, and reads as brand).

- [x] **Step 4: Compile, install, eyeball**

```powershell
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
Expected: Log In button, focused field outline, back arrow, logout icon and section headers are green `#1D6B45`; the "8 mixes" running caption on job cards is still blue; white text on the green button is legible.

- [x] **Step 5: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/ui
git commit -m "fix(theme): primary accent is the Station 2 icon green, token renamed BrandPrimary

AmberPrimary was #2E77F5 blue — S2 and S4 looked identical (audit S2-16,
static-02). Running job tone stays InfoBlue so it is not a second green.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 4: Seed the connection pill with the current status (Tier 2, §7 item 14)

Closes: S2-09 (red "Offline" flash on every screen entry).

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/components/ConnectionStatus.kt` (append)
- Modify: `ui/login/LoginViewModel.kt:35-41`, `ui/home/HomeViewModel.kt:30-34`, `ui/settings/SettingsViewModel.kt:109-113`, `ui/joblookup/JobLookupViewModel.kt:60-64`
- Test: `app/src/test/java/com/mitas/ppnam/station2aa/ui/components/ConnectionStatusTest.kt`, `app/src/test/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModelTest.kt`

**Interfaces:**
- Produces: `fun MqttRepository.currentConnectionStatus(): ConnectionStatus` and `fun MqttRepository.connectionStatusIn(scope: CoroutineScope): StateFlow<ConnectionStatus>` in `com.mitas.ppnam.station2aa.ui.components`. Every ViewModel's `connectionStatus` is built with the second one.

- [x] **Step 1: Write the failing tests**

Append to `ConnectionStatusTest.kt` (inside the class; add imports `com.mitas.ppnam.station2aa.domain.repository.MqttRepository`, `kotlinx.coroutines.flow.MutableStateFlow`, `org.mockito.kotlin.mock`, `org.mockito.kotlin.whenever`):
```kotlin
    @Test
    fun `currentConnectionStatus reads the repository's present values, not a placeholder`() {
        val repo = mock<MqttRepository>()
        whenever(repo.connectionState).thenReturn(MutableStateFlow(MqttConnectionState.CONNECTED))
        whenever(repo.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(repo.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(0L))
        assertEquals(ConnectionStatus.Connected, repo.currentConnectionStatus())
    }
```
Append to `LoginViewModelTest.kt` (add imports `com.mitas.ppnam.station2aa.ui.components.ConnectionStatus`):
```kotlin
    @Test
    fun `the pill starts from the live connection state instead of flashing Offline`() = runTest {
        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.CONNECTED))
        val connectedVm = LoginViewModel(mockAuthUseCase, mockMqttRepository)
        // The debounced flow has not emitted yet (1.5 s away); the seed must already be right.
        assertEquals(ConnectionStatus.Connected, connectedVm.connectionStatus.value)
    }
```
(Task 15 later adds a third constructor argument to `LoginViewModel`; when it does, update this call too.)

- [x] **Step 2: Run them to see them fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.ui.components.ConnectionStatusTest" --tests "com.mitas.ppnam.station2aa.ui.login.LoginViewModelTest"
```
Expected: compilation error `Unresolved reference: currentConnectionStatus`; the Login test would assert `Offline`.

- [x] **Step 3: Add the helpers to `ConnectionStatus.kt`**

Append after `connectionStatusFlow` (add imports `com.mitas.ppnam.station2aa.domain.repository.MqttRepository`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.StateFlow`, `kotlinx.coroutines.flow.stateIn`):
```kotlin
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
```

- [x] **Step 4: Use it in the four ViewModels**

`LoginViewModel.kt` lines 37–41 become:
```kotlin
    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)
```
and replace `import com.mitas.ppnam.station2aa.ui.components.connectionStatusFlow` with `import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn`.

`HomeViewModel.kt` lines 30–34 → the same one-liner; swap the `connectionStatusFlow` import for `connectionStatusIn` and drop the now-unused `SharingStarted`/`stateIn` imports.

`SettingsViewModel.kt` lines 109–113 → the same one-liner (keep `SharingStarted`/`stateIn` imports only if still used; Task 11 rewrites this file anyway).

`JobLookupViewModel.kt` lines 60–64 → `val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)` (`mqttRepository` is a plain constructor parameter there, which is fine in a property initializer); swap the import; remove unused `SharingStarted`/`stateIn` imports.

- [x] **Step 5: Run the whole unit suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
```
Expected: BUILD SUCCESSFUL, the two new tests pass.

- [x] **Step 6: Manual check**

Install, log in, tap Job Cards and Settings repeatedly: the pill must never show red "Offline" on entry while the backend is connected.

- [x] **Step 7: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/ui/components/ConnectionStatus.kt app/src/main/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModel.kt app/src/main/java/com/mitas/ppnam/station2aa/ui/home/HomeViewModel.kt app/src/main/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModel.kt app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupViewModel.kt app/src/test/java/com/mitas/ppnam/station2aa/ui/components/ConnectionStatusTest.kt app/src/test/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModelTest.kt
git commit -m "fix(ui): seed the connection pill with the live status instead of Offline

Every screen flashed a red Offline pill for ~1 s on entry (audit S2-09).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 5: Top bar — no keyboard focus on icons, no role truncation (Tier 2, §7 item 9 / 22)

Closes: S2-08 (focus halo on gear/back after Enter), S2-12 (operator chip truncation), static-26 (S2 part).

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/components/AppScaffold.kt:113-121,134-149,151-159,186-206`

- [x] **Step 1: Make the toolbar controls unreachable by keyboard focus traversal**

Add `import androidx.compose.ui.focus.focusProperties`. Then:

Line 114 `IconButton(onClick = onBack) {` → `IconButton(onClick = onBack, modifier = Modifier.focusProperties { canFocus = false }) {`
Line 134 `TextButton(onClick = { showLogoutDialog = true }) {` → `TextButton(onClick = { showLogoutDialog = true }, modifier = Modifier.focusProperties { canFocus = false }) {`
Line 152 `IconButton(onClick = onSettings) {` → `IconButton(onClick = onSettings, modifier = Modifier.focusProperties { canFocus = false }) {`
Line 188 and line 199: the same two `IconButton` edits in the single-row `TopAppBar` branch.

Add this comment above the first edited `IconButton` (line 113):
```kotlin
                        // Toolbar icons are tap targets, not keyboard stops: after an Enter-submit
                        // the IME closed and focus landed on the gear, so a second Enter (a
                        // scanner-wedge suffix, the C72 keypad) opened Settings (audit S2-08).
```

- [x] **Step 2: Drop the role when a back arrow is present**

Line 143:
```kotlin
                                    text = if (!operatorRole.isNullOrBlank()) "$operatorName · $operatorRole" else operatorName,
```
becomes:
```kotlin
                                    // With a back arrow, logout icon, gear and pill on one row only
                                    // ~330 px are left for this label and "Operator One · Operator"
                                    // ellipsised mid-role (audit S2-12). The role is informational;
                                    // the name is what matters, so the role goes on the Home bar only.
                                    text = if (!operatorRole.isNullOrBlank() && onBack == null) "$operatorName · $operatorRole" else operatorName,
```

- [x] **Step 3: Compile and verify**

```powershell
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
Manual: Login → type `operator1` / `pass`, press Enter on the password → after login no halo on the gear; press Enter again (`adb -s emulator-5556 shell input keyevent 66`) — Settings must NOT open. Job Cards: top bar reads "Operator One" (no ellipsis); Home still reads "Operator One · Operator". `uiautomator dump` must show no `focused="true"` on the Settings/Back nodes.

- [x] **Step 4: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/ui/components/AppScaffold.kt
git commit -m "fix(ui): toolbar icons are not keyboard focus stops; drop the role on sub-screens

A second Enter after login opened Settings; the operator chip truncated
mid-role on Job Cards (audit S2-08, S2-12).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 6: Proportional value column with tabular numerals (Tier 3, §7 item 24)

Closes: S2-14.

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/components/LabelValueRow.kt:28-33`

- [x] **Step 1: Edit the value style**

Replace lines 28–33:
```kotlin
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = TextPrimary,
            modifier = Modifier.weight(0.6f)
        )
```
with:
```kotlin
        // Body face, not monospace: "50  each" and "v1.2.0 (2)" read as typos in the wide
        // monospace column (audit S2-14). Tabular numerals keep digits aligned across rows.
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = TextPrimary,
            modifier = Modifier.weight(0.6f)
        )
```
Remove the now-unused `import androidx.compose.ui.text.font.FontFamily`.

- [x] **Step 2: Compile, verify, commit**

```powershell
.\gradlew.bat :app:assembleDebug --offline
```
Manual: Job detail `510019068` → "OUTPUT PER MIX 50 each" in Roboto with single spaces.
```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/ui/components/LabelValueRow.kt
git commit -m "fix(ui): job detail values in the body face with tabular numerals

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---
### Task 7: Login — saveable fields, error above the fields, password toggle, fill-all-fields, button kept above the keyboard (Tier 2, §7 items 9/11/16)

Closes: S2-01, S2-02 (login fields), S2-07 (client check), S2-08 (clearFocus on submit), static-25, §5 "Login layout" (password visibility toggle, "Please fill in all fields", error line above the fields). §7 item 4 asks for `imePadding()` on this column — **not added**: `AppScaffold` already pads content with `WindowInsets.safeDrawing` (which includes the IME), so a second `imePadding()` would double the gap. The real defect is that nothing scrolls the button into view once the error line grows the form; `BringIntoViewRequester` fixes that.

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModel.kt:47-62`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/login/LoginScreen.kt` (whole file)
- Test: `app/src/test/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModelTest.kt`

**Interfaces:**
- Produces: `LoginViewModel.FILL_ALL_FIELDS = "Please fill in all fields"`; `submitCredentials(username, password)` sets `LoginUiState.Error(FILL_ALL_FIELDS)` and does not call `AuthUseCase.login` when `username.isBlank() || password.isEmpty()`.

- [x] **Step 1: Write the failing test**

Append to `LoginViewModelTest.kt`:
```kotlin
    @Test
    fun `blank username or password shows the fill-all-fields message and sends nothing`() = runTest {
        viewModel.submitCredentials("", "pass")
        advanceUntilIdle()
        assertEquals(LoginUiState.Error(LoginViewModel.FILL_ALL_FIELDS), viewModel.uiState.value)

        viewModel.submitCredentials("   ", "pass")
        viewModel.submitCredentials("operator1", "")
        advanceUntilIdle()
        assertEquals(LoginUiState.Error(LoginViewModel.FILL_ALL_FIELDS), viewModel.uiState.value)
        verify(mockAuthUseCase, never()).login(any(), any())
    }
```

- [x] **Step 2: Run it to see it fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.ui.login.LoginViewModelTest"
```
Expected: `Unresolved reference: FILL_ALL_FIELDS`.

- [x] **Step 3: Add the client-side check to `LoginViewModel.submitCredentials`**

Replace lines 47–62 of `LoginViewModel.kt` with:
```kotlin
    fun submitCredentials(username: String, password: String) {
        // Blocks re-entry for the whole LoggingIn -> LoggedIn span: a second tap arriving after
        // success but before Compose has navigated away must not start a second, concurrent login.
        if (_uiState.value != LoginUiState.Idle && _uiState.value !is LoginUiState.Error) return
        // S1's rule, applied here too: a blank username used to go on the wire and come back as
        // the raw protocol text "username and clientNonce are required." (audit S2-07).
        if (username.isBlank() || password.isEmpty()) {
            _uiState.value = LoginUiState.Error(FILL_ALL_FIELDS)
            return
        }
        viewModelScope.launch {
            _uiState.value = LoginUiState.LoggingIn
            authUseCase.login(username, password)
                .onSuccess {
                    _uiState.value = LoginUiState.LoggedIn
                    _navigationEvent.send("home")
                }
                .onFailure { e ->
                    _uiState.value = LoginUiState.Error(e.message ?: "Login failed")
                }
        }
    }

    companion object {
        const val FILL_ALL_FIELDS = "Please fill in all fields"
    }
```

- [x] **Step 4: Run the test to see it pass**

Same command as Step 2. Expected: PASS.

- [x] **Step 5: Rewrite `LoginScreen.kt`**

Replace the whole file with:
```kotlin
package com.mitas.ppnam.station2aa.ui.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onNavigateSettings: () -> Unit,
    onExitApp: () -> Unit = {},
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    // rememberSaveable, not remember: a configuration change (font scale, multi-window — rotation
    // is locked now) used to wipe both fields mid-typing (audit S2-02).
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val buttonIntoView = remember { BringIntoViewRequester() }

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { destination ->
            if (destination == "home") onLoggedIn()
        }
    }

    // Login is the start destination, so Back here used to drop straight to the Android launcher
    // — without even dismissing the IME first. On a shared handheld that is easy to hit by
    // accident. Back now behaves in two stages, the way Back does everywhere else on Android:
    // with the keyboard up it just closes the keyboard, and only from a settled screen does it
    // ask whether to leave the app.
    val imeVisible = WindowInsets.isImeVisible
    BackHandler {
        if (imeVisible) {
            keyboard?.hide()
            focusManager.clearFocus()
        } else {
            showExitDialog = true
        }
    }

    // Once the keyboard is up (or an error line has grown the form under it), scroll the Log In
    // button into view. Without this the button sat 38 px above the IME edge and a tap at its
    // centre typed into the password field instead (audit S2-01).
    LaunchedEffect(imeVisible, uiState) {
        if (imeVisible) buttonIntoView.bringIntoView()
    }

    // Clearing focus closes the IME and, more importantly, stops focus hopping onto the gear
    // icon after an Enter-submit (audit S2-08).
    val submit = {
        focusManager.clearFocus()
        keyboard?.hide()
        viewModel.submitCredentials(username, password)
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Close the app?", color = TextPrimary) },
            text = { Text("You'll leave PPNAM Station 2 and return to the home screen.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    onExitApp()
                }) { Text("Close", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Stay") }
            },
            containerColor = GraphiteSurface
        )
    }

    AppScaffold(
        title = "Log In",
        status = connectionStatus,
        onSettings = onNavigateSettings
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // padding(padding) carries the IME inset (AppScaffold sets safeDrawing), so the
                // form's own space shrinks when the keyboard opens; verticalScroll then keeps the
                // Log In button reachable instead of stranded below the keys.
                .padding(padding)
                .verticalScroll(rememberScrollState())
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
                    // Above the fields, not below: an error that appears under the fields grows
                    // the form downwards and pushes the button under the keyboard (audit S2-01).
                    if (uiState is LoginUiState.Error) {
                        Text(
                            text = (uiState as LoginUiState.Error).message,
                            color = DangerRed,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        enabled = uiState !is LoginUiState.LoggingIn,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPrimary,
                            focusedLabelColor = BrandPrimary,
                            cursorColor = BrandPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = uiState !is LoginUiState.LoggingIn,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            // Gloved operators mistype; S1's Settings has a toggle, logins did not.
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.focusProperties { canFocus = false }
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = TextMuted
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPrimary,
                            focusedLabelColor = BrandPrimary,
                            cursorColor = BrandPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = submit,
                        enabled = uiState !is LoginUiState.LoggingIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .bringIntoViewRequester(buttonIntoView)
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

- [x] **Step 6: Compile and verify on the emulator**

```powershell
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
Manual (Login, backend in happy mode):
1. Tap Log In with both fields empty → red "Please fill in all fields" **above** the Username field; `logcat -s MqttLog` shows no `scram_start_requested` publish.
2. Type `operator1` / `wrong`, Enter → "Incorrect username or password" after Task 8 (for now the backend's "Invalid credentials."); fields keep their text; no halo on the gear.
3. With the error showing, tap Password: run the keyboard recipe — the Log In button's bottom bound in `ui.xml` is < the IME top (≈1023), i.e. the button is fully visible.
4. Tap the eye icon: password text becomes visible; tap again: masked.
5. Enter on Username moves focus to Password; Enter on Password submits.
6. Check `adb shell logcat -d -s AndroidRuntime:E` is empty.

- [x] **Step 7: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/ui/login app/src/test/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModelTest.kt
git commit -m "fix(login): keep the button above the keyboard, validate empty fields, add password toggle

Error line moves above the fields and the button is scrolled into view when
the IME opens (audit S2-01); fields survive config changes (S2-02); a blank
username is caught client-side instead of echoing protocol text (S2-07);
focus is cleared on submit (S2-08).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 8: Operator-facing auth error text (Tier 2, §7 item 13)

Closes: S2-07 (raw backend text), group (f) for login.

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/auth/ScramExchange.kt:116-127`
- Create: `app/src/test/java/com/mitas/ppnam/station2aa/data/auth/ScramExchangeMessagesTest.kt`

**Interfaces:**
- Produces: `authFailureMessage()` maps `ErrorCode.AUTHENTICATION_FAILED` → `"Incorrect username or password"` and `ErrorCode.INVALID_ENVELOPE` → `"Station 2 rejected the sign-in request as malformed. Update the app if this keeps happening."`; other codes keep `operatorMessage ?: "Authentication failed"`.

- [x] **Step 1: Write the failing test**

Create `ScramExchangeMessagesTest.kt`:
```kotlin
package com.mitas.ppnam.station2aa.data.auth

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.dto.ScramChallengeResponse
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Backend reason codes become operator wording; raw protocol text never reaches the screen. */
class ScramExchangeMessagesTest {

    private suspend fun failingWith(code: ErrorCode, operatorMessage: String?): Result<*> {
        val mqtt = mock<MqttRepository>()
        whenever(mqtt.request(any(), any(), any(), eq(ScramChallengeResponse::class.java)))
            .thenReturn(MqttOutcome.Rejected(body = null, error = code, operatorMessage = operatorMessage))
        return ScramExchange(mqtt).authenticate("operator1", "pass")
    }

    @Test
    fun `a wrong password reads as incorrect username or password`() = runTest {
        val result = failingWith(ErrorCode.AUTHENTICATION_FAILED, "SCRAM proof rejected.")
        assertEquals("Incorrect username or password", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a malformed envelope does not echo the protocol text`() = runTest {
        val result = failingWith(ErrorCode.INVALID_ENVELOPE, "username and clientNonce are required.")
        assertEquals(
            "Station 2 rejected the sign-in request as malformed. Update the app if this keeps happening.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `an unknown code still shows the station's own operator message`() = runTest {
        val result = failingWith(ErrorCode("some_future_code"), "Try again in a minute.")
        assertEquals("Try again in a minute.", result.exceptionOrNull()?.message)
    }
}
```

- [x] **Step 2: Run it to see it fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.auth.ScramExchangeMessagesTest"
```
Expected: the first two tests fail (`SCRAM proof rejected.` / `username and clientNonce are required.` come through).

- [x] **Step 3: Extend the mapping in `ScramExchange.kt`**

Replace lines 121–127:
```kotlin
private fun <T> MqttOutcome.Rejected<T>.authFailureMessage(): String = when (error) {
    ErrorCode.PASSWORD_FIELD_FORBIDDEN ->
        "This app build sent credentials in a form Station 2 no longer accepts. Update the app."
    ErrorCode.PURPOSE_NOT_ENABLED ->
        "Station 2 does not accept this kind of sign-in. Update the app."
    else -> operatorMessage ?: "Authentication failed"
}
```
with:
```kotlin
private fun <T> MqttOutcome.Rejected<T>.authFailureMessage(): String = when (error) {
    ErrorCode.PASSWORD_FIELD_FORBIDDEN ->
        "This app build sent credentials in a form Station 2 no longer accepts. Update the app."
    ErrorCode.PURPOSE_NOT_ENABLED ->
        "Station 2 does not accept this kind of sign-in. Update the app."
    // The SCRAM service's own wording ("SCRAM proof rejected.") is protocol text, not something
    // an operator can act on (audit group f).
    ErrorCode.AUTHENTICATION_FAILED -> "Incorrect username or password"
    // Seen as "username and clientNonce are required." when a blank username went on the wire,
    // and on a stale schema-4.1 build. The blank case is now caught client-side (Task 7).
    ErrorCode.INVALID_ENVELOPE ->
        "Station 2 rejected the sign-in request as malformed. Update the app if this keeps happening."
    else -> operatorMessage ?: "Authentication failed"
}
```

- [x] **Step 4: Run the whole suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
```
Expected: BUILD SUCCESSFUL (ScramExchangeWireShapeTest still passes — it does not assert on these strings).

- [x] **Step 5: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/data/auth/ScramExchange.kt app/src/test/java/com/mitas/ppnam/station2aa/data/auth/ScramExchangeMessagesTest.kt
git commit -m "fix(auth): map authentication_failed and invalid_envelope to operator wording

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 9: `SessionWatcher` reacts only to a session that *ends* (Tier 2, §7 item 16)

Closes: S2-02 (Settings pop — belt and braces after the portrait lock; still needed for font-scale/multi-window recreation).

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/session/SessionWatcher.kt:34-50`

- [x] **Step 1: Rewrite the composable**

Replace lines 34–50 with (add imports `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.saveable.rememberSaveable`, `androidx.compose.runtime.setValue`):
```kotlin
@Composable
fun SessionWatcher(
    navController: NavHostController,
    viewModel: SessionWatcherViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    // Only a non-null -> null TRANSITION sends the operator to Login. The effect used to fire on
    // every Activity recreation while session was simply null (nobody logged in yet) and popped a
    // supervisor out of Settings, draft and all (audit S2-02). Saveable so the "had a session"
    // fact itself survives recreation.
    var hadSession by rememberSaveable { mutableStateOf(session != null) }

    LaunchedEffect(session) {
        if (session != null) {
            hadSession = true
            return@LaunchedEffect
        }
        if (!hadSession) return@LaunchedEffect
        hadSession = false
        val current = navController.currentDestination?.route ?: return@LaunchedEffect
        if (current == NavRoutes.LOGIN) return@LaunchedEffect
        navController.navigate(NavRoutes.LOGIN) {
            // Nothing behind us is usable without a session.
            popUpTo(0)
        }
    }
}
```

- [x] **Step 2: Compile and verify**

```powershell
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
Manual: (a) Login → gear → Settings → change the display size (`adb -s emulator-5556 shell wm density 560` then `adb -s emulator-5556 shell wm density reset`) — the app stays on Settings. (b) Log in → Home → logout icon → "Log out" → lands on Login (transition still works). (c) Log in, then `python <SP>\fake_stations\set_mode.py --device scanner_40db7f6eef44 --mode error` and look up a job → when the backend answers `operator_session_invalid` the app returns to Login (then `--mode clear`).

- [x] **Step 3: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/ui/session/SessionWatcher.kt
git commit -m "fix(session): navigate to Login only when a session ends, not on every recreation

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---
### Task 10: Settings model — auto sign-out minutes, port parsing, persisted PIN lockout store (Tier 2, §7 items 10/15/20)

Closes the data half of: group (c) lockout persistence, S2-03/S2-13 (port validation), §5 "Settings action & field set" (auto sign-out minutes on every station).

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/AppSettings.kt:25-37`
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/domain/model/AutoLogout.kt` (copied from S1 `AutoLogout.kt`)
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/settings/SettingsRepository.kt:33-62,80-97`
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/settings/PinLockoutStore.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/di/AppModule.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station2aa/domain/model/AppSettingsTest.kt`, Create `app/src/test/java/com/mitas/ppnam/station2aa/domain/model/AutoLogoutTest.kt`

**Interfaces:**
- Produces: `AppSettings.autoLogoutMinutes: Int = AutoLogout.DEFAULT_MINUTES` (15); `AppSettings.parsePort(text: String): Int?` (1..65535 or null); `object AutoLogout { DEFAULT_MINUTES = 15; MAX_MINUTES = 1440; parseMinutes(text): Int?; timeoutMs(minutes): Long }`; `interface PinLockoutStore { var failedAttempts: Int; var lockedOutUntilMs: Long }` bound to `PrefsPinLockoutStore` by Hilt; `SettingsRepository` persists `autoLogoutMinutes` under key `auto_logout_minutes`.

- [x] **Step 1: Write the failing tests**

Append to `AppSettingsTest.kt`:
```kotlin
    @Test
    fun `auto sign-out defaults to S1's 15 minutes`() {
        assertEquals(15, AppSettings().autoLogoutMinutes)
    }

    @Test
    fun `parsePort accepts 1 to 65535 and nothing else`() {
        assertEquals(9001, AppSettings.parsePort(" 9001 "))
        assertEquals(1, AppSettings.parsePort("1"))
        assertEquals(65535, AppSettings.parsePort("65535"))
        assertNull(AppSettings.parsePort("0"))
        assertNull(AppSettings.parsePort("65536"))
        assertNull(AppSettings.parsePort("90019"))
        assertNull(AppSettings.parsePort(""))
        assertNull(AppSettings.parsePort("90a"))
    }
```
Create `AutoLogoutTest.kt`:
```kotlin
package com.mitas.ppnam.station2aa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoLogoutTest {
    @Test
    fun `whole minutes from 0 to 1440 parse, anything else is rejected`() {
        assertEquals(0, AutoLogout.parseMinutes("0"))
        assertEquals(15, AutoLogout.parseMinutes(" 15 "))
        assertEquals(1440, AutoLogout.parseMinutes("1440"))
        assertNull(AutoLogout.parseMinutes("1441"))
        assertNull(AutoLogout.parseMinutes("-1"))
        assertNull(AutoLogout.parseMinutes(""))
        assertNull(AutoLogout.parseMinutes("1.5"))
    }

    @Test
    fun `zero means never, otherwise minutes become milliseconds`() {
        assertEquals(0L, AutoLogout.timeoutMs(0))
        assertEquals(60_000L, AutoLogout.timeoutMs(1))
        assertEquals(900_000L, AutoLogout.timeoutMs(15))
    }
}
```

- [x] **Step 2: Run them to see them fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.domain.model.*"
```
Expected: `Unresolved reference: autoLogoutMinutes` / `AutoLogout`.

- [x] **Step 3: Create `AutoLogout.kt`** (S1's file verbatim, package changed)

```kotlin
package com.mitas.ppnam.station2aa.domain.model

/** Inactivity auto-logout setting rules (S1 spec §3): whole minutes, 0 = never, max one day. */
object AutoLogout {
    const val DEFAULT_MINUTES = 15
    const val MAX_MINUTES = 1440

    fun parseMinutes(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 0..MAX_MINUTES }

    fun timeoutMs(minutes: Int): Long = if (minutes <= 0) 0L else minutes * 60_000L
}
```

- [x] **Step 4: Extend `AppSettings.kt`**

Replace lines 25–37 with:
```kotlin
data class AppSettings(
    val mqttHost: String = "mqtt.sysone.co.za",
    val mqttPort: Int = 443,
    val mqttUseWebSocket: Boolean = true,
    val mqttUseTls: Boolean = true,
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    val requestTimeoutMs: Long = 10_000L,
    /** Inactivity auto sign-out in whole minutes, 0 = never (S1's rule, applied fleet-wide). */
    val autoLogoutMinutes: Int = AutoLogout.DEFAULT_MINUTES,
) {
    /** True once this handheld has been provisioned with its own broker credential. */
    val hasBrokerCredential: Boolean
        get() = mqttUsername.isNotBlank() && mqttPassword.isNotBlank()

    companion object {
        /** Parses a port field, or null when it is not a valid TCP port (1–65535). From S1 `BrokerSettings`. */
        fun parsePort(text: String): Int? =
            text.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    }
}
```

- [x] **Step 5: Persist the new field in `SettingsRepository.kt`**

In `Keys` (after line 47) add:
```kotlin
        val AUTO_LOGOUT_MINUTES     = intPreferencesKey("auto_logout_minutes")
```
In `settingsFlow` (after line 60 `requestTimeoutMs = …`) add:
```kotlin
            ,
            autoLogoutMinutes    = prefs[Keys.AUTO_LOGOUT_MINUTES]   ?: AppSettings().autoLogoutMinutes
```
(i.e. the `requestTimeoutMs` line gets a trailing comma and the new line follows it.)
In `save` (after line 92 `prefs[Keys.REQUEST_TIMEOUT_MS] = …`) add:
```kotlin
            prefs[Keys.AUTO_LOGOUT_MINUTES] = settings.autoLogoutMinutes
```

- [x] **Step 6: Create `PinLockoutStore.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the supervisor-PIN gate keeps its failed-attempt count and lockout deadline.
 *
 * It used to live in `SettingsViewModel`, which is scoped to the Settings route — so Back and
 * reopen, or a process restart, reset the counter and made the 5-attempt / 30 s lockout a no-op
 * (audit group c). An interface so unit tests can use an in-memory store.
 */
interface PinLockoutStore {
    var failedAttempts: Int
    /** Wall-clock millis (System.currentTimeMillis) until which Unlock is disabled; 0 = not locked. */
    var lockedOutUntilMs: Long
}

@Singleton
class PrefsPinLockoutStore @Inject constructor(
    @ApplicationContext context: Context,
) : PinLockoutStore {
    private val prefs = context.getSharedPreferences("settings_pin_gate", Context.MODE_PRIVATE)

    override var failedAttempts: Int
        get() = prefs.getInt(KEY_ATTEMPTS, 0)
        set(value) { prefs.edit().putInt(KEY_ATTEMPTS, value).apply() }

    override var lockedOutUntilMs: Long
        get() = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_LOCKED_UNTIL, value).apply() }

    private companion object {
        const val KEY_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_UNTIL = "locked_out_until_ms"
    }
}
```

- [x] **Step 7: Bind it in `AppModule.kt`**

Replace the file with:
```kotlin
package com.mitas.ppnam.station2aa.di

import com.mitas.ppnam.station2aa.data.mqtt.MqttRepositoryImpl
import com.mitas.ppnam.station2aa.data.settings.PinLockoutStore
import com.mitas.ppnam.station2aa.data.settings.PrefsPinLockoutStore
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideMqttRepository(impl: MqttRepositoryImpl): MqttRepository = impl

    @Provides
    @Singleton
    fun providePinLockoutStore(impl: PrefsPinLockoutStore): PinLockoutStore = impl
}
```

- [x] **Step 8: Run the tests and the build**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
.\gradlew.bat :app:assembleDebug --offline
```
Expected: both green (nothing consumes the new pieces yet; `SettingsViewModelTest` still compiles because the constructor is unchanged until Task 11).

- [x] **Step 9: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/domain/model/AppSettings.kt app/src/main/java/com/mitas/ppnam/station2aa/domain/model/AutoLogout.kt app/src/main/java/com/mitas/ppnam/station2aa/data/settings/SettingsRepository.kt app/src/main/java/com/mitas/ppnam/station2aa/data/settings/PinLockoutStore.kt app/src/main/java/com/mitas/ppnam/station2aa/di/AppModule.kt app/src/test/java/com/mitas/ppnam/station2aa/domain/model/AppSettingsTest.kt app/src/test/java/com/mitas/ppnam/station2aa/domain/model/AutoLogoutTest.kt
git commit -m "feat(settings): auto sign-out minutes, port parsing and a persisted PIN lockout store

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 11: `SettingsViewModel` — persisted lockout with ticker and blank guard, string drafts with validation, blank-password-keeps, device id as StateFlow (Tier 2, §7 items 10/13/15/20)

Closes: group (c) for S2 (lockout bypass, empty Unlock counts, static countdown, Unlock enabled during lockout), S2-03, S2-04, S2-13 (port), static-06 (validation), static-20 (password prefilled), group (f) for the Test & Apply failure text ("Timed out waiting for 15000 ms").

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModel.kt` (whole file)
- Test: `app/src/test/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModelTest.kt` (whole file)

**Interfaces:**
- Consumes: Task 10's `PinLockoutStore`, `AutoLogout`, `AppSettings.parsePort`, `AppSettings.autoLogoutMinutes`; Task 4's `connectionStatusIn`.
- Produces (read by Task 12's screen): `pinInput`, `pinState`, `pinError`, `pinErrorMessage`, `pinLockoutMessage`, `pinLockedOut: State<Boolean>`; `draftSettings` (host/ws/tls/username), `portText`, `timeoutText`, `autoLogoutText`, `passwordText` and `hostError`/`portError`/`timeoutError`/`autoLogoutError: State<String?>`; `deviceId: StateFlow<String>`; functions `onPinChange`, `submitPin`, `updateDraft`, `onPortChange`, `onTimeoutChange`, `onAutoLogoutChange`, `onPasswordChange`, `testAndApply`, `logout`; `internal fun validatedSettings(): AppSettings?`; `@VisibleForTesting internal var nowMs: () -> Long`. Constructor gains a sixth parameter `pinLockoutStore: PinLockoutStore` (Task 15 adds a seventh, `sessionGuard`).

- [x] **Step 1: Replace `SettingsViewModelTest.kt` with the failing tests**

```kotlin
package com.mitas.ppnam.station2aa.ui.settings

import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.PinLockoutStore
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private class InMemoryPinLockoutStore : PinLockoutStore {
        override var failedAttempts = 0
        override var lockedOutUntilMs = 0L
    }

    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var mockDeviceIdentity: DeviceIdentity
    private lateinit var store: InMemoryPinLockoutStore
    private lateinit var viewModel: SettingsViewModel

    private val stored = AppSettings(
        mqttHost = "10.0.2.2", mqttPort = 9001, mqttUseTls = false,
        mqttUsername = "test", mqttPassword = "stored-secret", requestTimeoutMs = 10_000L, autoLogoutMinutes = 15,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockSettingsRepository = mock()
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()
        mockDeviceIdentity = mock()
        store = InMemoryPinLockoutStore()
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(null))
        whenever(mockDeviceIdentity.deviceId()).thenReturn("scanner_5c64df8d86a8")

        whenever(mockSettingsRepository.settingsFlow).thenReturn(flowOf(stored))
        runBlocking { whenever(mockSettingsRepository.current()).thenReturn(stored) }
        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))

        viewModel = newViewModel()
    }

    private fun newViewModel() = SettingsViewModel(
        mockSettingsRepository, mockMqttRepository, mockAuthUseCase, mockSessionHolder,
        mockDeviceIdentity, store,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun wrongPin(vm: SettingsViewModel = viewModel) {
        vm.onPinChange("000000")
        vm.submitPin()
    }

    // ---- PIN gate ------------------------------------------------------------------------------

    @Test
    fun `initial pin state is Locked`() {
        assertTrue(viewModel.pinState.value is PinState.Locked)
    }

    @Test
    fun `correct PIN unlocks settings`() = runTest {
        viewModel.onPinChange("079545")
        viewModel.submitPin()
        assertTrue(viewModel.pinState.value is PinState.Unlocked)
        assertFalse(viewModel.pinError.value)
    }

    @Test
    fun `wrong PIN stays Locked, sets pinError and says how many attempts are left`() = runTest {
        wrongPin()
        assertTrue(viewModel.pinState.value is PinState.Locked)
        assertTrue(viewModel.pinError.value)
        assertEquals("", viewModel.pinInput.value)
        assertEquals("Incorrect PIN. 4 attempts left before lockout.", viewModel.pinErrorMessage.value)
    }

    @Test
    fun `onPinChange does not accept more than 6 digits or non-digits`() {
        viewModel.onPinChange("1234567")
        assertEquals("", viewModel.pinInput.value)
        viewModel.onPinChange("12a4")
        assertEquals("", viewModel.pinInput.value)
    }

    @Test
    fun `an empty Unlock is not counted as an attempt`() = runTest {
        viewModel.submitPin()
        assertNull(viewModel.pinErrorMessage.value)
        assertFalse(viewModel.pinError.value)
        assertEquals(0, store.failedAttempts)
    }

    @Test
    fun `failed attempts survive leaving the screen`() = runTest {
        repeat(2) { wrongPin() }
        val reopened = newViewModel()
        repeat(2) { wrongPin(reopened) }
        assertEquals("Incorrect PIN. 1 attempt left before lockout.", reopened.pinErrorMessage.value)
    }

    @Test
    fun `five wrong PINs lock the gate, disable Unlock and count down every second`() = runTest {
        viewModel.nowMs = { testScheduler.currentTime }
        repeat(5) { wrongPin() }
        assertTrue(viewModel.pinLockedOut.value)
        assertEquals("Too many attempts. Try again in 30s.", viewModel.pinLockoutMessage.value)
        assertNull(viewModel.pinErrorMessage.value)

        advanceTimeBy(1_001)
        assertEquals("Too many attempts. Try again in 29s.", viewModel.pinLockoutMessage.value)

        // The correct PIN is refused while locked out.
        viewModel.onPinChange("079545")
        viewModel.submitPin()
        assertTrue(viewModel.pinState.value is PinState.Locked)

        advanceTimeBy(30_000)
        assertFalse(viewModel.pinLockedOut.value)
        assertNull(viewModel.pinLockoutMessage.value)
        assertFalse(viewModel.pinError.value)

        viewModel.onPinChange("079545")
        viewModel.submitPin()
        assertTrue(viewModel.pinState.value is PinState.Unlocked)
    }

    @Test
    fun `a lockout survives a new ViewModel (screen left and reopened)`() = runTest {
        viewModel.nowMs = { testScheduler.currentTime }
        repeat(5) { wrongPin() }
        val reopened = newViewModel()
        reopened.nowMs = { testScheduler.currentTime }
        reopened.onPinChange("079545")
        reopened.submitPin()
        assertTrue(reopened.pinState.value is PinState.Locked)
        assertTrue(reopened.pinLockedOut.value)
    }

    @Test
    fun `a lockout whose deadline is implausibly far ahead is treated as expired`() = runTest {
        // Device clock stepped backwards after a lockout was written: the deadline would now be
        // minutes away. A lockout may never outlast its 30 s of real time.
        viewModel.nowMs = { testScheduler.currentTime }
        store.lockedOutUntilMs = testScheduler.currentTime + 10 * 60_000L
        viewModel.onPinChange("079545")
        viewModel.submitPin()
        assertTrue(viewModel.pinState.value is PinState.Unlocked)
        assertEquals(0L, store.lockedOutUntilMs)
    }

    // ---- Drafts and validation -----------------------------------------------------------------

    @Test
    fun `the draft is loaded from the stored settings with the password field left blank`() = runTest {
        assertEquals("10.0.2.2", viewModel.draftSettings.value.mqttHost)
        assertEquals("9001", viewModel.portText.value)
        assertEquals("10000", viewModel.timeoutText.value)
        assertEquals("15", viewModel.autoLogoutText.value)
        assertEquals("", viewModel.passwordText.value)
    }

    @Test
    fun `port text can be emptied and only takes up to five digits`() {
        viewModel.onPortChange("")
        assertEquals("", viewModel.portText.value)
        viewModel.onPortChange("9001")
        viewModel.onPortChange("900199")
        assertEquals("9001", viewModel.portText.value)
        viewModel.onPortChange("90a1")
        assertEquals("9001", viewModel.portText.value)
    }

    @Test
    fun `invalid port, timeout and auto sign-out are rejected inline and nothing is sent`() = runTest {
        viewModel.onPortChange("65536")
        viewModel.onTimeoutChange("0")
        viewModel.onAutoLogoutChange("1441")
        viewModel.updateDraft(viewModel.draftSettings.value.copy(mqttHost = "  "))

        viewModel.testAndApply()
        advanceUntilIdle()

        assertEquals("Host required", viewModel.hostError.value)
        assertEquals("Invalid port (1–65535)", viewModel.portError.value)
        assertEquals("Enter 1000–60000 ms", viewModel.timeoutError.value)
        assertEquals("Enter 0–1440", viewModel.autoLogoutError.value)
        assertTrue(viewModel.applyState.value is ApplyState.Idle)
        verify(mockMqttRepository, never()).reconnectWith(any())
        verify(mockSettingsRepository, never()).save(any())
    }

    @Test
    fun `a blank password keeps the stored one and a typed password replaces it`() = runTest {
        assertEquals("stored-secret", viewModel.validatedSettings()!!.mqttPassword)
        viewModel.onPasswordChange("new-secret")
        assertEquals("new-secret", viewModel.validatedSettings()!!.mqttPassword)
    }

    @Test
    fun `testAndApply on success saves the validated settings and resets to Locked`() = runTest {
        whenever(mockMqttRepository.reconnectWith(any())).thenReturn(Result.success(Unit))
        viewModel.onPinChange("079545")
        viewModel.submitPin()
        viewModel.onPortChange("1884")
        viewModel.onAutoLogoutChange("1")

        viewModel.testAndApply()
        advanceUntilIdle()

        val saved = argumentCaptor<AppSettings>()
        verify(mockSettingsRepository).save(saved.capture())
        assertEquals(1884, saved.firstValue.mqttPort)
        assertEquals(1, saved.firstValue.autoLogoutMinutes)
        assertEquals("stored-secret", saved.firstValue.mqttPassword)
        assertEquals(ApplyState.Success("Connected — settings saved"), viewModel.applyState.value)
        advanceTimeBy(2_100)
        assertTrue(viewModel.pinState.value is PinState.Locked)
    }

    @Test
    fun `testAndApply on failure shows operator wording, not the library message`() = runTest {
        whenever(mockMqttRepository.reconnectWith(any()))
            .thenReturn(Result.failure(RuntimeException("Connection refused")))
        viewModel.testAndApply()
        advanceUntilIdle()
        verify(mockSettingsRepository, never()).save(any())
        assertEquals(
            ApplyState.Failure("Could not connect to the broker. Check the host, port, username, password and TLS setting."),
            viewModel.applyState.value,
        )
    }

    @Test
    fun `a connect timeout names the 15 s wait`() = runTest {
        val timeout = runCatching { withTimeout(1) { kotlinx.coroutines.delay(10) } }.exceptionOrNull()
        assertTrue(timeout is TimeoutCancellationException)
        whenever(mockMqttRepository.reconnectWith(any())).thenReturn(Result.failure(timeout!!))
        viewModel.testAndApply()
        advanceUntilIdle()
        assertEquals(
            ApplyState.Failure("No answer from the broker within 15 s. Check the host, port and TLS setting."),
            viewModel.applyState.value,
        )
    }

    // ---- Diagnostics ---------------------------------------------------------------------------

    @Test
    fun `the device id reaches the UI as a StateFlow`() {
        // runBlocking, not runTest: the id is derived on Dispatchers.IO (a real thread), and
        // runTest's virtual clock would skip a withTimeout while that thread is still working.
        val id = runBlocking { withTimeout(5_000) { viewModel.deviceId.first { it.isNotBlank() } } }
        assertEquals("scanner_5c64df8d86a8", id)
    }
}
```

- [x] **Step 2: Run them to see them fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.ui.settings.SettingsViewModelTest"
```
Expected: compilation failure (constructor arity, `portText`, `pinLockedOut`, …).

- [x] **Step 3: Replace `SettingsViewModel.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.settings

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.PinLockoutStore
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.model.AutoLogout
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PinState {
    object Locked : PinState
    object Unlocked : PinState
}

sealed interface ApplyState {
    object Idle : ApplyState
    object Testing : ApplyState
    data class Success(val message: String) : ApplyState
    data class Failure(val message: String) : ApplyState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder,
    deviceIdentity: DeviceIdentity,
    private val pinLockoutStore: PinLockoutStore,
) : ViewModel() {

    /**
     * Non-null once an operator is logged in. Settings is reachable from the login screen too
     * (broker config has to be editable before anyone can log in), so the logout affordance below
     * is conditional on this.
     */
    val session: StateFlow<OperatorSession?> = sessionHolder.session

    /**
     * A second route to switching operator. SessionWatcher handles the navigation once the
     * session goes null — this just clears it.
     */
    fun logout() {
        viewModelScope.launch { authUseCase.logout() }
    }

    // ---- Supervisor PIN gate -------------------------------------------------------------------

    private val correctPin = "079545"

    /** Wall-clock, because the lockout deadline is persisted across process restarts. Test seam. */
    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    var pinInput = mutableStateOf("")
        private set
    var pinState = mutableStateOf<PinState>(PinState.Locked)
        private set
    var pinError = mutableStateOf(false)
        private set

    /**
     * Why the last PIN attempt failed, or null. Deliberately says nothing about the correct PIN's
     * length or shape.
     */
    var pinErrorMessage = mutableStateOf<String?>(null)
        private set
    var pinLockoutMessage = mutableStateOf<String?>(null)
        private set

    /** True while the cooldown runs: the field AND Unlock are disabled, not merely painted red. */
    var pinLockedOut = mutableStateOf(false)
        private set
    private var lockoutTicker: Job? = null

    // ---- Draft -----------------------------------------------------------------------------------

    var applyState = mutableStateOf<ApplyState>(ApplyState.Idle)
        private set

    /** Host, WebSocket, TLS and username drafts, plus the stored password (never shown). */
    var draftSettings = mutableStateOf(AppSettings())
        private set

    // Numeric fields are kept as the text the operator typed. Parsing on every keystroke
    // (`toIntOrNull() ?: old`) made the Port field impossible to empty and threw the caret to
    // position 0 — "9001" became "90019" (audit S2-03). They are parsed once, on Test & Apply.
    var portText = mutableStateOf("")
        private set
    var timeoutText = mutableStateOf("")
        private set
    var autoLogoutText = mutableStateOf("")
        private set

    /** What was typed into Password. Blank means "keep the provisioned password" (S1's rule). */
    var passwordText = mutableStateOf("")
        private set

    var hostError = mutableStateOf<String?>(null)
        private set
    var portError = mutableStateOf<String?>(null)
        private set
    var timeoutError = mutableStateOf<String?>(null)
        private set
    var autoLogoutError = mutableStateOf<String?>(null)
        private set

    // ---- Diagnostics -----------------------------------------------------------------------------

    private val _deviceId = MutableStateFlow("")

    /**
     * The derived scanner identity (fleet MQTT base standard §2) — read-only diagnostics, shown so
     * it can be read off the device for enrolment. A StateFlow, not a snapshot state written from
     * Dispatchers.IO: that write never reached the composable and the row stayed blank (audit S2-04).
     */
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    /**
     * Surfaced separately from [connectionStatus] so Diagnostics can show the broker link and
     * Station 2's presence on their own lines.
     */
    val stationOnline: StateFlow<Boolean> = mqttRepository.stationOnline

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    init {
        viewModelScope.launch {
            val current = settingsRepository.current()
            draftSettings.value = current
            portText.value = current.mqttPort.toString()
            timeoutText.value = current.requestTimeoutMs.toString()
            autoLogoutText.value = current.autoLogoutMinutes.toString()
        }
        // Deriving the id can touch SharedPreferences and NetworkInterface — off Main.
        viewModelScope.launch(Dispatchers.IO) {
            _deviceId.value = deviceIdentity.deviceId()
        }
        if (remainingLockoutMs() > 0) startLockoutTicker()
    }

    fun onPinChange(value: String) {
        if (pinLockedOut.value) return
        if (value.length <= 6 && value.all { it in '0'..'9' }) {
            pinInput.value = value
            pinError.value = false
            pinErrorMessage.value = null
        }
    }

    /**
     * Milliseconds of lockout left, clamped: a deadline more than one lockout away can only come
     * from a device clock that was stepped backwards after it was written, and a lockout may never
     * outlast its 30 s of real time — such a deadline is discarded.
     */
    private fun remainingLockoutMs(): Long {
        val remaining = pinLockoutStore.lockedOutUntilMs - nowMs()
        if (remaining > PIN_LOCKOUT_MS) {
            pinLockoutStore.lockedOutUntilMs = 0L
            return 0L
        }
        return remaining
    }

    fun submitPin() {
        if (remainingLockoutMs() > 0) {
            startLockoutTicker()
            pinInput.value = ""
            return
        }
        // An empty Unlock is a mis-tap, not a guess: it must not burn an attempt (audit group c).
        if (pinInput.value.isBlank()) return
        if (pinInput.value == correctPin) {
            pinLockoutStore.failedAttempts = 0
            pinLockoutMessage.value = null
            pinState.value = PinState.Unlocked
            pinError.value = false
            pinErrorMessage.value = null
            return
        }
        pinInput.value = ""
        pinError.value = true
        val failed = pinLockoutStore.failedAttempts + 1
        if (failed >= MAX_PIN_ATTEMPTS) {
            pinLockoutStore.lockedOutUntilMs = nowMs() + PIN_LOCKOUT_MS
            pinLockoutStore.failedAttempts = 0
            pinErrorMessage.value = null
            startLockoutTicker()
        } else {
            pinLockoutStore.failedAttempts = failed
            // The remaining-attempt count is the useful half: it tells the operator a lockout is
            // coming without revealing anything about the PIN itself.
            val left = MAX_PIN_ATTEMPTS - failed
            pinErrorMessage.value =
                "Incorrect PIN. $left attempt${if (left == 1) "" else "s"} left before lockout."
            pinLockoutMessage.value = null
        }
    }

    /** Re-renders the countdown every second and re-enables the gate when it reaches zero. */
    private fun startLockoutTicker() {
        lockoutTicker?.cancel()
        lockoutTicker = viewModelScope.launch {
            while (true) {
                val remaining = remainingLockoutMs()
                if (remaining <= 0) break
                pinLockedOut.value = true
                pinError.value = true
                pinLockoutMessage.value = "Too many attempts. Try again in ${(remaining + 999) / 1_000}s."
                delay(1_000)
            }
            pinLockedOut.value = false
            pinError.value = false
            pinLockoutMessage.value = null
        }
    }

    private companion object {
        const val MAX_PIN_ATTEMPTS = 5
        const val PIN_LOCKOUT_MS = 30_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 60_000L
        const val CONNECT_TIMEOUT_SECONDS = 15
    }

    // ---- Draft editing ---------------------------------------------------------------------------

    fun updateDraft(settings: AppSettings) {
        draftSettings.value = settings
        hostError.value = null
    }

    private fun digitsOnly(text: String, maxLength: Int): Boolean =
        text.length <= maxLength && text.all { it in '0'..'9' }

    fun onPortChange(text: String) {
        if (digitsOnly(text, 5)) { portText.value = text; portError.value = null }
    }

    fun onTimeoutChange(text: String) {
        if (digitsOnly(text, 6)) { timeoutText.value = text; timeoutError.value = null }
    }

    fun onAutoLogoutChange(text: String) {
        if (digitsOnly(text, 4)) { autoLogoutText.value = text; autoLogoutError.value = null }
    }

    fun onPasswordChange(text: String) {
        passwordText.value = text
    }

    /**
     * The settings to apply, or null with the inline errors set. S1's host/port rules, plus the
     * two numeric extras this app has.
     */
    @VisibleForTesting
    internal fun validatedSettings(): AppSettings? {
        val draft = draftSettings.value
        val host = draft.mqttHost.trim()
        val port = AppSettings.parsePort(portText.value)
        val timeout = timeoutText.value.toLongOrNull()?.takeIf { it in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS }
        val autoLogout = AutoLogout.parseMinutes(autoLogoutText.value)
        hostError.value = if (host.isBlank()) "Host required" else null
        portError.value = if (port == null) "Invalid port (1–65535)" else null
        timeoutError.value = if (timeout == null) "Enter $MIN_TIMEOUT_MS–$MAX_TIMEOUT_MS ms" else null
        autoLogoutError.value = if (autoLogout == null) "Enter 0–${AutoLogout.MAX_MINUTES}" else null
        if (host.isBlank() || port == null || timeout == null || autoLogout == null) return null
        return draft.copy(
            mqttHost = host,
            mqttPort = port,
            mqttUsername = draft.mqttUsername.trim(),
            // The field never echoes the stored password; blank keeps it (S1's rule, static-20).
            mqttPassword = passwordText.value.ifBlank { draft.mqttPassword },
            requestTimeoutMs = timeout,
            autoLogoutMinutes = autoLogout,
        )
    }

    fun testAndApply() {
        val settings = validatedSettings() ?: return
        applyState.value = ApplyState.Testing
        viewModelScope.launch {
            val result = mqttRepository.reconnectWith(settings)
            if (result.isSuccess) {
                settingsRepository.save(settings)
                draftSettings.value = settings
                passwordText.value = ""
                applyState.value = ApplyState.Success("Connected — settings saved")
                delay(2_000)
                pinState.value = PinState.Locked
                pinInput.value = ""
            } else {
                applyState.value = ApplyState.Failure(result.exceptionOrNull().toOperatorMessage())
            }
        }
    }

    /** Library text ("Timed out waiting for 15000 ms", "Connection refused") never reaches the screen. */
    private fun Throwable?.toOperatorMessage(): String = when (this) {
        is TimeoutCancellationException ->
            "No answer from the broker within $CONNECT_TIMEOUT_SECONDS s. Check the host, port and TLS setting."
        else -> "Could not connect to the broker. Check the host, port, username, password and TLS setting."
    }
}
```

- [x] **Step 4: Run the ViewModel tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.ui.settings.SettingsViewModelTest"
```
Expected on the first run: a **compile error in `SettingsScreen.kt`** (`draft.mqttPort`, `viewModel.deviceId.value`, the old `updateDraft(draft.copy(mqttPort = …))` call) — `testDebugUnitTest` compiles main sources too and the screen is only rewritten in Task 12. Apply Task 12 Step 1 (the `SettingsScreen.kt` rewrite) now, re-run this command, and expect all `SettingsViewModelTest` tests to PASS. Then commit Task 11's two files alone (Step 5) and continue with Task 12 Step 2.

- [x] **Step 5: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModel.kt app/src/test/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModelTest.kt
git commit -m "fix(settings): persisted PIN lockout with countdown, text drafts with validation, device id StateFlow

Lockout survives leaving the screen, empty Unlock is not an attempt, the
countdown ticks and the gate is disabled while locked (audit group c);
Port/Timeout can be emptied and are clamped (S2-03, S2-13); Device ID
shows (S2-04); blank password keeps the stored one (static-20).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---
### Task 12: `SettingsScreen` — PIN supporting text, validated fields, password toggle, S1 diagnostics order and pill vocabulary, persistent confirmation, "Log out" (Tier 2/3, §7 items 11/20/21)

Closes: S2-10, S2-03/S2-13 (UI half), S2-04 (row), S2-09 (Diagnostics wording "Reconnecting" in blue vs pill), static-17 (row order), static-06 (confirmation stays visible after the re-lock), static-20 (toggle), §5 "Diagnostics rows", "Pill vocabulary", "Dialog style" ("Log out" casing), §6 S2 Settings form (Done closes the keyboard so Test & Apply is reachable).

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/settings/SettingsScreen.kt` (whole file)

**Interfaces:**
- Consumes: everything listed under Task 11 "Produces".

- [x] **Step 1: Replace `SettingsScreen.kt`**

```kotlin
package com.mitas.ppnam.station2aa.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mitas.ppnam.station2aa.BuildConfig
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.ui.components.AppScaffold
import com.mitas.ppnam.station2aa.ui.theme.*

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val stationOnline by viewModel.stationOnline.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val pinState = viewModel.pinState.value
    val pinInput = viewModel.pinInput.value
    val pinError = viewModel.pinError.value
    val pinErrorMessage = viewModel.pinErrorMessage.value
    val pinLockoutMessage = viewModel.pinLockoutMessage.value
    val pinLockedOut = viewModel.pinLockedOut.value
    val applyState = viewModel.applyState.value
    val draft = viewModel.draftSettings.value
    val session by viewModel.session.collectAsState()
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val submitPin = {
        // Clearing focus closes the IME so a hardware Enter cannot hop to the toolbar (S2-08).
        focusManager.clearFocus()
        viewModel.submitPin()
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Log out?", color = TextPrimary) },
            text = { Text("You'll need to log in again to continue.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout()
                }) { Text("Log out", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            },
            containerColor = GraphiteSurface
        )
    }

    AppScaffold(
        title = "Settings",
        status = connectionStatus,
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionLabel("Diagnostics")

            Card(
                colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                border = BorderStroke(1.dp, GraphiteBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Broker link and Station 2 presence are separate failures with separate
                    // remedies, and the composite status can only name one of them at a time.
                    // Diagnostics shows both. Same three words and colours as the top-bar pill:
                    // the card used to say "Reconnecting" in blue while the pill said "Offline"
                    // in red at the same moment (audit S2-09, static-17).
                    val (brokerColor, brokerLabel) = when (connectionState) {
                        MqttConnectionState.CONNECTED    -> SuccessGreen to "Connected"
                        MqttConnectionState.RECONNECTING -> WarningOrange to "Reconnecting"
                        MqttConnectionState.DISCONNECTED -> DangerRed to "Offline"
                    }
                    DiagnosticRow("MQTT BROKER", brokerColor, brokerLabel)

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 10.dp))

                    // With the broker down, the retained presence value is stale rather than
                    // false — saying "offline" there would blame Station 2 for the broker's fault.
                    val (stationColor, stationLabel) = when {
                        connectionState != MqttConnectionState.CONNECTED -> TextMuted to "Unknown"
                        stationOnline -> SuccessGreen to "Online"
                        else -> WarningOrange to "Offline"
                    }
                    DiagnosticRow("STATION 2", stationColor, stationLabel)

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 10.dp))

                    // S1's order: broker, station, version, device id (static-17).
                    DiagnosticValueRow("VERSION", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

                    HorizontalDivider(color = GraphiteBorder, modifier = Modifier.padding(vertical = 10.dp))

                    // Read-only by design (fleet MQTT base standard §2): the id is derived from
                    // hardware once and persisted, never configured. Shown here so it can be
                    // read off the device for enrolment at the station.
                    DiagnosticValueRow("DEVICE ID", deviceId.ifBlank { "…" })
                }
            }

            HorizontalDivider(color = GraphiteBorder)

            SectionLabel("Configuration")

            when (pinState) {
                PinState.Locked -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                        border = BorderStroke(1.dp, GraphiteBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "Enter supervisor PIN to edit settings",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                val message = pinLockoutMessage ?: pinErrorMessage
                                OutlinedTextField(
                                    value = pinInput,
                                    onValueChange = viewModel::onPinChange,
                                    label = { Text("PIN") },
                                    singleLine = true,
                                    enabled = !pinLockedOut,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.NumberPassword,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { submitPin() }),
                                    isError = pinError,
                                    // Supporting text is part of the field's own bounds, so the
                                    // keyboard's bring-into-view scroll includes it. As a separate
                                    // Text below the row it sat exactly under the IME edge and the
                                    // only feedback while typing was a red outline (audit S2-10).
                                    supportingText = message?.let { { Text(it, color = DangerRed) } },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = BrandPrimary,
                                        focusedLabelColor = BrandPrimary,
                                        cursorColor = BrandPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = submitPin,
                                    enabled = !pinLockedOut,
                                    modifier = Modifier.height(56.dp)
                                ) { Text("Unlock") }
                            }
                        }
                    }
                }

                PinState.Unlocked -> {
                    ConfigSection(title = "Connection") {
                        SettingsTextField(
                            value = draft.mqttHost,
                            label = "Host",
                            error = viewModel.hostError.value,
                            keyboardType = KeyboardType.Uri,
                            onValueChange = { viewModel.updateDraft(draft.copy(mqttHost = it)) }
                        )
                        SettingsTextField(
                            value = viewModel.portText.value,
                            label = "Port",
                            error = viewModel.portError.value,
                            keyboardType = KeyboardType.Number,
                            onValueChange = viewModel::onPortChange
                        )
                        SettingsToggleRow(
                            label = "WebSocket",
                            checked = draft.mqttUseWebSocket,
                            onCheckedChange = { viewModel.updateDraft(draft.copy(mqttUseWebSocket = it)) }
                        )
                        SettingsToggleRow(
                            label = "TLS",
                            checked = draft.mqttUseTls,
                            onCheckedChange = { viewModel.updateDraft(draft.copy(mqttUseTls = it)) }
                        )
                        SettingsTextField(
                            value = draft.mqttUsername,
                            label = "Username",
                            onValueChange = { viewModel.updateDraft(draft.copy(mqttUsername = it)) }
                        )
                        SettingsTextField(
                            value = viewModel.passwordText.value,
                            label = "Password (blank keeps the current one)",
                            keyboardType = KeyboardType.Password,
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    modifier = Modifier.focusProperties { canFocus = false }
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                        tint = TextMuted
                                    )
                                }
                            },
                            onValueChange = viewModel::onPasswordChange
                        )
                    }

                    ConfigSection(title = "Session") {
                        SettingsTextField(
                            value = viewModel.autoLogoutText.value,
                            label = "Auto sign-out after (minutes, 0 = never)",
                            error = viewModel.autoLogoutError.value,
                            keyboardType = KeyboardType.Number,
                            onValueChange = viewModel::onAutoLogoutChange
                        )
                    }

                    ConfigSection(title = "Advanced") {
                        SettingsTextField(
                            value = viewModel.timeoutText.value,
                            label = "Request timeout (ms)",
                            error = viewModel.timeoutError.value,
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                            onValueChange = viewModel::onTimeoutChange
                        )
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.testAndApply()
                        },
                        enabled = applyState !is ApplyState.Testing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("Test & Apply")
                    }
                }
            }

            // Outside the Locked/Unlocked switch on purpose: the gate re-locks 2 s after a
            // successful apply, and the confirmation used to vanish with the form — "silently",
            // the audit said (static-06). It stays until the next apply or leaving the screen.
            when (val state = applyState) {
                ApplyState.Testing -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = BrandPrimary,
                            strokeWidth = 2.dp
                        )
                        Text("Testing connection…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
                is ApplyState.Success -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = SuccessGreen)
                    }
                }
                is ApplyState.Failure -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Error, null, tint = DangerRed, modifier = Modifier.size(18.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = DangerRed)
                    }
                }
                ApplyState.Idle -> {}
            }

            // The top bar's operator label was the ONLY way to switch users, and it read as a
            // caption rather than a control. Settings is the obvious second home for it — and the
            // one place still reachable when a keyboard is covering the bar.
            session?.let { operator ->
                HorizontalDivider(color = GraphiteBorder)
                SectionLabel("Session")
                Card(
                    colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                    border = BorderStroke(1.dp, GraphiteBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "SIGNED IN AS",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                                color = TextMuted
                            )
                            Text(
                                if (operator.role.isNotBlank()) "${operator.operatorName} · ${operator.role}"
                                else operator.operatorName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary
                            )
                        }
                        OutlinedButton(
                            onClick = { showLogoutDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) { Text("Log out") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One labelled line of the Diagnostics card, with its own dot-and-text status badge. */
@Composable
private fun DiagnosticRow(label: String, dotColor: Color, statusLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = TextMuted
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(dotColor.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(6.dp)) {
                    drawCircle(dotColor, center = Offset(size.width / 2, size.height / 2))
                }
                Spacer(Modifier.width(5.dp))
                Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = dotColor)
            }
        }
    }
}

/** A plain label/value Diagnostics line (version, device id). Body face with tabular digits (S2-14). */
@Composable
private fun DiagnosticValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = TextMuted
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = TextPrimary
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
        color = TextMuted
    )
}

@Composable
private fun ConfigSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(1.dp, GraphiteBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                color = BrandPrimary
            )
            content()
        }
    }
}

/**
 * One form field. Next moves down the form; Done (the last field) just closes the keyboard so
 * Test & Apply is reachable without a swipe — the audit's keyboard matrix had "Done no-op" here.
 * A validation [error] is supporting text, inside the field's bounds, for the same reason as the
 * PIN message (S2-10).
 */
@Composable
private fun SettingsTextField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it, color = DangerRed) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
            onDone = { focusManager.clearFocus() },
        ),
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandPrimary,
            focusedLabelColor = BrandPrimary,
            cursorColor = BrandPrimary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BrandPrimary,
                checkedTrackColor = BrandPrimary.copy(alpha = 0.4f)
            )
        )
    }
}
```

- [x] **Step 2: Build, run the suite, install**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
Expected: both green.

- [x] **Step 3: Manual verification (Settings)**

1. **Diagnostics** reads top to bottom: MQTT BROKER, STATION 2, VERSION, DEVICE ID — and DEVICE ID shows `scanner_40db7f6eef44` (the id the backend logs for this emulator) within a second of opening.
2. Disconnect the broker (stop mosquitto): pill says "Offline" (red) and the broker row says **"Offline"** (red), station row "Unknown". Restart mosquitto: both say "Reconnecting" (orange) then "Connected".
3. **PIN gate**: tap PIN, keyboard up — type `1` then Unlock: "Incorrect PIN. 4 attempts left before lockout." is visible **above the IME edge** (uiautomator: its bounds y2 < 1155). Tap Unlock with an empty field: no message, still 4 left. Press Back, reopen Settings, 4 more wrong → "Too many attempts. Try again in 30s." — the field and Unlock are **disabled** and the text counts down 29s, 28s… Press Back, reopen Settings within 30 s: still locked with the countdown. `adb -s emulator-5556 shell am force-stop com.mitas.ppnam.station2aa`, relaunch, open Settings within 30 s: still locked. After 30 s: `079545` unlocks.
4. **Port**: Backspace to empty — the field empties and stays empty (no "9" left behind, caret does not jump); type `9001` → `9001`. Type `90019` (five digits, so it is accepted as text) then Test & Apply → inline "Invalid port (1–65535)" under Port and **no** "Testing connection…" row. A sixth digit is refused by the field.
5. **Password** field is empty on unlock; eye toggle shows/hides; leaving it blank and applying keeps the broker connected with the stored `test` password.
6. **Session** card "Auto sign-out after (minutes, 0 = never)" prefilled `15`; `1441` → inline "Enter 0–1440".
7. **Done** on Request timeout closes the keyboard; Test & Apply visible without a swipe. Keyboard recipe on Host: the field and its label are above the IME edge.
8. Test & Apply with valid values: "Testing connection…" → green "Connected — settings saved", the gate relocks after 2 s and the green line **stays** visible.
9. Set host to `10.0.2.3` (nothing there), Test & Apply → red "No answer from the broker within 15 s. Check the host, port and TLS setting." (not "Timed out waiting for 15000 ms"). Restore `10.0.2.2`.
10. Logged in: Session card button reads "Log out"; dialog confirm reads "Log out".
11. `logcat -d -s AndroidRuntime:E` empty.

- [x] **Step 4: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/ui/settings/SettingsScreen.kt
git commit -m "fix(settings): PIN message inside the field, validated form with password toggle, S1 diagnostics order

PIN/validation text is supporting text so it stays above the keyboard
(audit S2-10); Diagnostics rows are Broker, Station 2, Version, Device ID
with the pill's vocabulary (static-17, S2-09); the apply result persists
past the re-lock (static-06); Log out casing.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---
### Task 13: Upgrade gate with a Close-app action and the installed version (Tier 3, §7 item 18)

Closes: S2-06, group (f) ("4.0 reader build" literal).

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/components/UpgradeGate.kt:25-49`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/navigation/AppNavGraph.kt:41-43,124`

**Interfaces:**
- Produces: `UpgradeRequiredGate(onCloseApp: () -> Unit, viewModel: UpgradeGateViewModel = hiltViewModel())`.

- [x] **Step 1: Rewrite the composable in `UpgradeGate.kt`**

Replace lines 25–49 with (add imports `androidx.compose.material3.TextButton`, `com.mitas.ppnam.station2aa.BuildConfig`, `com.mitas.ppnam.station2aa.ui.theme.DangerRed`):
```kotlin
/**
 * The app-level `client_upgrade_required` gate. Rendered once above the NavHost so it blocks
 * EVERY screen — the transport's latch never resets, so neither does this dialog; only a new
 * build clears the condition.
 *
 * It is blocking, but not a trap: with no button at all the operator's only way out was Home +
 * kill the app (audit S2-06). "Close app" finishes the Activity. The text names the installed
 * version instead of a hard-coded "4.0 reader build" that was already wrong for v1.2.0.
 */
@Composable
fun UpgradeRequiredGate(
    onCloseApp: () -> Unit,
    viewModel: UpgradeGateViewModel = hiltViewModel(),
) {
    val upgradeRequired by viewModel.upgradeRequired.collectAsState()
    if (upgradeRequired) {
        AlertDialog(
            onDismissRequest = { /* blocking: only a new build clears this */ },
            title = { Text("App update required", color = TextPrimary) },
            text = {
                Text(
                    "This version of Station 2 (v${BuildConfig.VERSION_NAME}) is too old for the " +
                        "station. Ask a supervisor to install the latest version, then log in again.",
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = onCloseApp) { Text("Close app", color = DangerRed) }
            },
            containerColor = GraphiteSurface,
        )
    }
}
```

- [x] **Step 2: Pass the Activity finish from `AppNavGraph.kt`**

After line 43 (`SessionWatcher(navController)`) add:
```kotlin
    // LocalActivity only exists from activity-compose 1.10; this project is on 1.9.0.
    val hostActivity = LocalContext.current.findActivity()
```
and change line 124 `UpgradeRequiredGate()` to:
```kotlin
    UpgradeRequiredGate(onCloseApp = { hostActivity?.finish() })
```

- [x] **Step 3: Compile, verify, commit**

```powershell
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
python <SP>\fake_stations\set_mode.py --device scanner_40db7f6eef44 --mode error
```
Manual: log in, open Job Cards → the gate appears with "This version of Station 2 (v1.2.0) is too old…" and a red "Close app"; Back and scrim tap do nothing; "Close app" leaves the app. `set_mode.py … --mode clear`, relaunch: login works.
```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/ui/components/UpgradeGate.kt app/src/main/java/com/mitas/ppnam/station2aa/navigation/AppNavGraph.kt
git commit -m "fix(ui): upgrade gate offers Close app and names the installed version

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 14: 10 s single-attempt request timeout, S3 wording, explicit Retry, capped lookup input (Tier 3, §7 items 18/21)

Closes: S2-05, S2-13 (lookup digits), S2-11 (list `weight`, belt and braces), static-08, §5 "Timeout seconds / wording". The replay-safe byte-identical retry stays *possible* in the contract (same `messageId`), but the app no longer retries on its own: three silent attempts made "Request timeout" lie by a factor of three and left the spinner running for 90–105 s. Retry is now an operator action.

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt:46-51,456-499`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/AuthUseCase.kt:60-64`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupViewModel.kt:148-176,221-223`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupScreen.kt:83-159`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup/JobDetailScreen.kt:89-91`
- Test: rename `app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRequestRetryTest.kt` → `MqttRequestTimeoutTest.kt` (rewritten); `domain/usecase/JobLookupUseCaseTest.kt:195`; `ui/joblookup/JobLookupViewModelTest.kt`

**Interfaces:**
- Produces: `FailureKind.Timeout.message() == "Station 2 did not respond. Check the station and retry."`; `JobLookupViewModel.retryLookup()` repeats the last `lookup()` input (typed or scanned); `JobLookupScreen` constant `JOB_CARD_MAX_DIGITS = 12`. `MqttRepositoryImpl.REQUEST_MAX_ATTEMPTS` is removed.

- [x] **Step 1: Replace the retry test with the timeout test**

```powershell
git mv app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRequestRetryTest.kt app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRequestTimeoutTest.kt
```
Then replace its content with (`TestBody` lives in `Rev2TransportTest.kt`, same package; `EmptyPayload` is in main `RequestEnvelope.kt`):
```kotlin
package com.mitas.ppnam.station2aa.data.mqtt

import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * One publish, one wait of exactly the configured timeout, then NoResponse. The transport used
 * to republish three times, each waiting the full timeout, so a 20 s setting meant ~60–105 s of
 * spinner with no feedback (audit S2-05). Retry is the operator's call now (a Retry button).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttRequestTimeoutTest {

    private lateinit var repo: MqttRepositoryImpl
    private val published = mutableListOf<Pair<String, ByteArray>>()

    @Before
    fun setup() {
        val deviceIdentity = mock<DeviceIdentity>()
        whenever(deviceIdentity.deviceId()).thenReturn("scanner_5c64df8d86a8")
        repo = MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = OperatorSessionHolder(),
            deviceIdentity = deviceIdentity,
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

    private fun idOf(index: Int): String = com.google.gson.JsonParser
        .parseString(String(published[index].second)).asJsonObject.get("messageId").asString

    @Test
    fun `an unanswered request is published once and times out`() = runTest {
        val outcome = repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)

        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), outcome)
        assertEquals(1, published.size)
        assertEquals("PPNAM/station_2/scanner_5c64df8d86a8/req/a_requested", published[0].first)
    }

    @Test
    fun `a request waits exactly the configured timeout and no longer`() = runTest {
        setTimeout(10_000L)
        val call = async { repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java) }
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertFalse(call.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(call.isCompleted)
        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), call.await())
        assertEquals(1, published.size)
    }

    @Test
    fun `a response stops the wait`() = runTest {
        val call = async { repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java) }
        while (published.isEmpty()) yield()

        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_5c64df8d86a8/res/test_result",
            """{"inResponseToMessageId":"${idOf(0)}","success":true,"data":{"value":"ok"}}""".toByteArray()
        )

        val outcome = call.await()
        assertTrue(outcome is MqttOutcome.Accepted)
        assertEquals("ok", (outcome as MqttOutcome.Accepted).body.value)
    }

    @Test
    fun `a reply arriving after the timeout is ignored`() = runTest {
        val outcome = repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)
        assertEquals(MqttOutcome.NoResponse(FailureKind.Timeout), outcome)

        // Late reply: no waiter left, nothing to complete, and it must not throw.
        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_5c64df8d86a8/res/test_result",
            """{"inResponseToMessageId":"${idOf(0)}","success":true,"data":{"value":"late"}}""".toByteArray()
        )
        assertEquals(1, published.size)
    }

    @Test
    fun `a publish failure is reported as not connected, not retried`() = runTest {
        var attempts = 0
        repo.publishFn = { _, _ ->
            attempts++
            throw IllegalStateException("transient publish failure")
        }

        val outcome = repo.request("a_requested", "test_result", EmptyPayload, TestBody::class.java)

        assertEquals(MqttOutcome.NoResponse(FailureKind.NotConnected), outcome)
        assertEquals(1, attempts)
    }
}
```
Also change `JobLookupUseCaseTest.kt:195` to:
```kotlin
        assertEquals("Station 2 did not respond. Check the station and retry.", (useCase.read() as JobLookupResult.Failed).message)
```
Append to `JobLookupViewModelTest.kt`:
```kotlin
    @Test
    fun `retryLookup repeats the last lookup, including one that came from a scan`() = runTest {
        whenever(useCase.lookup("510019068")).thenReturn(JobLookupResult.Failed("Station 2 did not respond. Check the station and retry."))
        vm.setLookupScreenActive(true)
        scans.emit(ScanEvent.Barcode("510019068", "CODE128", Instant.now()))
        assertEquals("Station 2 did not respond. Check the station and retry.", vm.uiState.value.lookupError)

        whenever(useCase.lookup("510019068")).thenReturn(JobLookupResult.Loaded(withDetail))
        vm.retryLookup()

        verify(useCase, times(2)).lookup("510019068")
        assertEquals(detail, vm.uiState.value.detail)
        assertNull(vm.uiState.value.lookupError)
    }

    @Test
    fun `retryLookup with nothing to retry does nothing`() = runTest {
        vm.retryLookup()
        verify(useCase, never()).lookup(any())
    }
```

- [x] **Step 2: Run to see them fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.mqtt.MqttRequestTimeoutTest" --tests "com.mitas.ppnam.station2aa.ui.joblookup.JobLookupViewModelTest" --tests "com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCaseTest"
```
Expected: `retryLookup` unresolved; the timeout tests report 3 publishes / 3 attempts; the use-case test gets the old wording.

- [x] **Step 3: Single attempt in `MqttRepositoryImpl.request`**

Delete lines 46–51 (the `REQUEST_MAX_ATTEMPTS` constant and its comment) and replace lines 456–499 with:
```kotlin
        val action = (gson.toJsonTree(payload) as? com.google.gson.JsonObject)
            ?.get("action")?.takeIf { it.isJsonPrimitive }?.asString
        val startedAt = System.currentTimeMillis()
        val bytes = json.toByteArray()
        val waiter = CompletableDeferred<String>()
        pending[messageId] = PendingRequest(waiter, sessionId)
        // One attempt. The contract's replay identity (deviceId + requestType + messageId) would
        // allow a byte-identical republish, but three silent attempts each waiting the full
        // timeout left the operator with a 90 s spinner and a "Request timeout" setting that was
        // off by a factor of three (audit S2-05). Retrying is now an explicit operator action.
        try {
            try {
                publishFn(topic, bytes)
                MqttLog.message(Direction.OUT, topic, 1, false, deviceId, requestType, action, "published", payload = json)
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
```

- [x] **Step 4: Timeout wording in `AuthUseCase.kt`**

Line 62 `FailureKind.Timeout -> "Station 2 did not respond"` becomes:
```kotlin
    FailureKind.Timeout -> "Station 2 did not respond. Check the station and retry."
```

- [x] **Step 5: `retryLookup()` in `JobLookupViewModel.kt`**

After line 85 (`private var lookupScreenActive = false`) add:
```kotlin
    /** What the last [lookup] was asked for — typed or scanned — so Retry can repeat it. */
    private var lastLookupInput: String? = null
```
In `lookup()` (line 148) insert `lastLookupInput = input` as the first line after the in-flight guard:
```kotlin
    fun lookup(input: String) {
        if (_uiState.value.lookupInFlight) return
        lastLookupInput = input
        viewModelScope.launch {
```
After `lookup()` (before `openDetail`) add:
```kotlin
    /** The Retry button next to a lookup error. A no-op until something has been looked up. */
    fun retryLookup() {
        lastLookupInput?.let { lookup(it) }
    }
```

- [x] **Step 6: Retry buttons and input cap in `JobLookupScreen.kt`**

Replace lines 83–160 (from `) { padding ->` through the file's closing brace) with:
```kotlin
    ) { padding ->
        val submit = {
            focusManager.clearFocus()
            keyboard?.hide()
            viewModel.lookup(input)
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            OutlinedTextField(
                value = input,
                // Digits only, capped: a 60-digit value was accepted and sent to the station
                // (audit S2-13). SAP production order numbers are at most 12 digits.
                onValueChange = { v -> if (v.length <= JOB_CARD_MAX_DIGITS && v.all { it in '0'..'9' }) input = v },
                label = { Text("Production order number") },
                supportingText = { Text("Scan the job card or type the number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrandPrimary, focusedLabelColor = BrandPrimary, cursorColor = BrandPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = submit,
                enabled = input.isNotBlank() && !state.lookupInFlight,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (state.lookupInFlight) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Look up")
                }
            }
            state.lookupError?.let {
                Spacer(Modifier.height(8.dp))
                ErrorWithRetry(message = it, enabled = !state.lookupInFlight, onRetry = viewModel::retryLookup)
            }

            Spacer(Modifier.height(24.dp))
            Text("Jobs on Station 2", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Spacer(Modifier.height(8.dp))
            // With jobs showing, a failed refresh would otherwise be invisible: the list is the
            // last good one. One muted line says so without displacing it.
            if (state.jobs.isNotEmpty() && state.listError != null) {
                ErrorWithRetry(
                    message = state.listError!!, enabled = !state.listLoading,
                    onRetry = viewModel::refreshList, color = TextMuted,
                )
                Spacer(Modifier.height(8.dp))
            }
            when {
                state.jobs.isNotEmpty() -> LazyColumn(
                    // weight(fill = false): the list takes what is left, never squeezes to a strip.
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.jobs, key = { it.jobCard }) { job ->
                        StatusCard(
                            tone = if (job.closed) StatusTone.Idle else StatusTone.Running,
                            onClick = { onJobFound(job.jobCard) },
                            enabled = !state.lookupInFlight,
                        ) { accent ->
                            Text(job.jobCard, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            Text(
                                buildString {
                                    append("${job.requiredMixes} ${if (job.requiredMixes == 1) "mix" else "mixes"}")
                                    if (job.closed) append(" · closed")
                                },
                                style = MaterialTheme.typography.labelMedium, color = accent,
                            )
                            if (job.product.isNotBlank()) {
                                Text(
                                    job.product, style = MaterialTheme.typography.bodySmall, color = TextMuted,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                state.listError != null ->
                    ErrorWithRetry(message = state.listError!!, enabled = !state.listLoading, onRetry = viewModel::refreshList)
                !state.listLoading ->
                    Text("No jobs yet. Look one up to load it.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Job cards are SAP production order numbers: never more than 12 digits. */
internal const val JOB_CARD_MAX_DIGITS = 12

/**
 * An error line with the S3-style explicit Retry. Timeouts used to leave only "re-tap the
 * button" as a hint, and after a scan there was nothing to re-tap (audit S2-05, static-08).
 */
@Composable
private fun ErrorWithRetry(
    message: String,
    enabled: Boolean,
    onRetry: () -> Unit,
    color: androidx.compose.ui.graphics.Color = DangerRed,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            message, color = color, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onRetry, enabled = enabled) { Text("Retry") }
    }
}
```
Add `import androidx.compose.ui.Alignment` to the imports; the `AmberPrimary` import was already renamed to `BrandPrimary` in Task 3.

- [x] **Step 7: Retry on the detail screen in `JobDetailScreen.kt`**

Replace lines 89–91:
```kotlin
            state.detailError != null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(state.detailError!!, color = TextMuted)
            }
```
with:
```kotlin
            state.detailError != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.detailError!!, color = DangerRed, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                // openDetail re-reads the target (freshFromLookup is already consumed).
                OutlinedButton(onClick = { viewModel.openDetail(jobCard) }) { Text("Retry") }
            }
```

- [x] **Step 8: Run the whole suite and build**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
Expected: green. (`Rev2TransportTest` and the wire-shape tests reply to the first publish and are unaffected.)

- [x] **Step 9: Manual verification**

```powershell
python <SP>\fake_stations\set_mode.py --device scanner_40db7f6eef44 --mode timeout
```
1. Job Cards → the list read shows the progress bar for ~10 s then "Station 2 did not respond. Check the station and retry." with a **Retry** button (red text when the list is empty, muted when a previous list is still showing).
2. Type `510019068`, Look up: the button spinner stops after **≈10 s** (time it with `date`), the same message + Retry appears under the button; `logcat -s MqttLog` shows exactly **one** `rev2_general_requested` publish for the lookup.
3. `set_mode.py … --mode clear`, tap Retry → detail opens. Back; send the scan broadcast with the backend in timeout mode → error + Retry; clear the mode, tap Retry → detail opens (the scanned number is retried although the field is empty).
4. Field refuses letters and a 13th digit.
5. Detail screen: with timeout mode on, open `510018531` from the list → red message + Retry; clear the mode, Retry → detail loads.
6. `logcat -d -s AndroidRuntime:E` empty; `set_mode.py … --mode clear`.

- [x] **Step 10: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRepositoryImpl.kt app/src/main/java/com/mitas/ppnam/station2aa/domain/usecase/AuthUseCase.kt app/src/main/java/com/mitas/ppnam/station2aa/ui/joblookup app/src/test/java/com/mitas/ppnam/station2aa/data/mqtt/MqttRequestTimeoutTest.kt app/src/test/java/com/mitas/ppnam/station2aa/domain/usecase/JobLookupUseCaseTest.kt app/src/test/java/com/mitas/ppnam/station2aa/ui/joblookup/JobLookupViewModelTest.kt
git commit -m "fix(mqtt): single-attempt request timeout with an explicit Retry

Three silent republishes each waiting the full timeout left a 90 s spinner
(audit S2-05). One attempt, S3's wording, Retry buttons on lookup, list
and detail; job-card input capped at 12 digits (S2-13).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---
### Task 15: Inactivity auto sign-out with reason text on Login (Tier 3, §7 item 19)

Closes: static-05 (S2 part: inactivity timer + reason text), group (j) "reason text on Login whenever a session was dropped". Session persistence across process restart is **deliberately unchanged** (brief: S2-15 out of scope).

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/session/InactivityMonitor.kt` (S1's file, package changed)
- Create: `app/src/main/java/com/mitas/ppnam/station2aa/data/session/SessionGuard.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/data/session/OperatorSessionHolder.kt:22-34`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/PpnamApplication.kt:15-31`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/MainActivity.kt:22-25,55-57`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModel.kt:23-45`
- Modify: `app/src/main/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModel.kt` (constructor + `testAndApply`)
- Test: Create `app/src/test/java/com/mitas/ppnam/station2aa/data/session/InactivityMonitorTest.kt`; modify `data/session/OperatorSessionHolderTest.kt`, `ui/login/LoginViewModelTest.kt`, `ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Produces: `class InactivityMonitor(now, schedule, cancel, onExpired)` with `start(timeoutMs)`, `touch()`, `checkNow()`, `stop()`, `isRunning`; `@Singleton class SessionGuard` with `install()`, `touch()`, `checkNow()`, `applyTimeout()`; `OperatorSessionHolder.clear(reason: String? = null)`, `signedOutReason: StateFlow<String?>`, `consumeSignedOutReason(): String?`; `LoginViewModel(authUseCase, mqttRepository, sessionHolder)`; `SettingsViewModel(…, pinLockoutStore, sessionGuard)`; `R.plurals.signed_out_inactivity`.

- [x] **Step 1: Write the failing tests**

Create `InactivityMonitorTest.kt` (S1's test verbatim, package changed):
```kotlin
package com.mitas.ppnam.station2aa.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inactivity auto-logout timer. Time and scheduling are injected so the tests are
 * deterministic: `scheduled` holds the pending runnable (at most one) and `fireScheduled()`
 * advances the clock to its due time and runs it.
 */
class InactivityMonitorTest {

    private var now = 1_000_000L
    private var scheduled: Pair<Long, Runnable>? = null
    private var expired = 0

    private val monitor = InactivityMonitor(
        now = { now },
        schedule = { delay, r -> scheduled = (now + delay) to r },
        cancel = { r -> if (scheduled?.second === r) scheduled = null },
        onExpired = { expired++ },
    )

    private fun fireScheduled() {
        val (due, r) = scheduled ?: error("nothing scheduled")
        scheduled = null
        now = maxOf(now, due)
        r.run()
    }

    @Test
    fun `expires once the timeout elapses without activity`() {
        monitor.start(60_000)
        assertTrue(monitor.isRunning)
        fireScheduled()
        assertEquals(1, expired)
        assertFalse(monitor.isRunning)
    }

    @Test
    fun `touch defers the deadline`() {
        monitor.start(60_000)
        now += 40_000
        monitor.touch()
        // The original deadline arrives: only 20s since the touch, so no expiry yet.
        fireScheduled()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
        assertEquals(now + 40_000, scheduled!!.first)
        fireScheduled()
        assertEquals(1, expired)
    }

    @Test
    fun `stop cancels the pending deadline and never fires`() {
        monitor.start(60_000)
        monitor.stop()
        assertFalse(monitor.isRunning)
        assertNull(scheduled)
        assertEquals(0, expired)
    }

    @Test
    fun `checkNow after a long gap fires immediately`() {
        monitor.start(60_000)
        now += 3_600_000 // app was in the background for an hour
        monitor.checkNow()
        assertEquals(1, expired)
        assertNull(scheduled)
    }

    @Test
    fun `checkNow before the deadline does nothing`() {
        monitor.start(60_000)
        now += 10_000
        monitor.checkNow()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
    }

    @Test
    fun `zero or negative timeout disables the monitor`() {
        monitor.start(0)
        assertFalse(monitor.isRunning)
        assertNull(scheduled)
        monitor.touch()
        monitor.checkNow()
        assertEquals(0, expired)
    }

    @Test
    fun `touch and checkNow are no-ops when stopped`() {
        monitor.touch()
        monitor.checkNow()
        assertEquals(0, expired)
        assertNull(scheduled)
    }

    @Test
    fun `restart replaces the previous timeout`() {
        monitor.start(60_000)
        monitor.start(5_000)
        assertEquals(now + 5_000, scheduled!!.first)
        fireScheduled()
        assertEquals(1, expired)
    }
}
```
Append to `OperatorSessionHolderTest.kt`:
```kotlin
    @Test
    fun `clear with a reason records it and set forgets it`() {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        holder.clear("Signed out after 15 minutes of inactivity.")
        assertNull(holder.session.value)
        assertEquals("Signed out after 15 minutes of inactivity.", holder.signedOutReason.value)
        holder.set(OperatorSession("sess-2", "OP-1", "Jane Smith", "Operator"))
        assertNull(holder.signedOutReason.value)
    }

    @Test
    fun `a manual logout leaves no signed-out reason`() {
        val holder = OperatorSessionHolder()
        holder.set(OperatorSession("sess-1", "OP-1", "Jane Smith", "Operator"))
        holder.clear()
        assertNull(holder.signedOutReason.value)
    }

    @Test
    fun `consumeSignedOutReason hands the reason over exactly once`() {
        val holder = OperatorSessionHolder()
        holder.clear("Signed out after 1 minute of inactivity.")
        assertEquals("Signed out after 1 minute of inactivity.", holder.consumeSignedOutReason())
        assertNull(holder.consumeSignedOutReason())
        assertNull(holder.signedOutReason.value)
    }
```
In `LoginViewModelTest.kt`: add `private lateinit var sessionHolder: OperatorSessionHolder` (import `com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder`), in `setup()` add `sessionHolder = OperatorSessionHolder()` and change the construction (and the one in the Task 4 test) to `LoginViewModel(mockAuthUseCase, mockMqttRepository, sessionHolder)`. Append:
```kotlin
    @Test
    fun `a signed-out reason is shown once and then consumed`() = runTest {
        sessionHolder.clear("Signed out after 15 minutes of inactivity.")
        val first = LoginViewModel(mockAuthUseCase, mockMqttRepository, sessionHolder)
        assertEquals(LoginUiState.Error("Signed out after 15 minutes of inactivity."), first.uiState.value)
        val second = LoginViewModel(mockAuthUseCase, mockMqttRepository, sessionHolder)
        assertTrue(second.uiState.value is LoginUiState.Idle)
    }
```
In `SettingsViewModelTest.kt`: add `private lateinit var mockSessionGuard: SessionGuard` (import `com.mitas.ppnam.station2aa.data.session.SessionGuard`), `mockSessionGuard = mock()` in `setup()`, and `newViewModel()` passes it as the seventh argument. Append:
```kotlin
    @Test
    fun `a successful apply re-arms the inactivity timer with the saved minutes`() = runTest {
        whenever(mockMqttRepository.reconnectWith(any())).thenReturn(Result.success(Unit))
        viewModel.onAutoLogoutChange("1")
        viewModel.testAndApply()
        advanceUntilIdle()
        verify(mockSessionGuard).applyTimeout()
    }
```

- [x] **Step 2: Run them to see them fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.mitas.ppnam.station2aa.data.session.*" --tests "com.mitas.ppnam.station2aa.ui.login.LoginViewModelTest" --tests "com.mitas.ppnam.station2aa.ui.settings.SettingsViewModelTest"
```
Expected: unresolved `InactivityMonitor`, `SessionGuard`, `signedOutReason`, constructor arity errors.

- [x] **Step 3: Create `InactivityMonitor.kt`** (S1's file verbatim, package changed)

```kotlin
package com.mitas.ppnam.station2aa.data.session

/**
 * Inactivity auto-logout timer (S1 spec §3, ported). Pure Kotlin: the caller supplies a
 * monotonic clock and a scheduler, so production uses SystemClock.elapsedRealtime plus a
 * main-thread Handler while tests drive time by hand.
 *
 * The deadline is wall-clock from the last activity, so time spent in the background
 * still counts; hosts call [checkNow] on resume to catch a deadline that passed while
 * no Handler was running. [onExpired] fires at most once per [start].
 */
class InactivityMonitor(
    private val now: () -> Long,
    private val schedule: (Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onExpired: () -> Unit,
) {
    private var timeoutMs = 0L
    private var lastActivity = 0L
    private var pending: Runnable? = null

    val isRunning: Boolean get() = timeoutMs > 0

    fun start(timeoutMs: Long) {
        stop()
        if (timeoutMs <= 0) return
        this.timeoutMs = timeoutMs
        lastActivity = now()
        scheduleCheck(timeoutMs)
    }

    fun touch() {
        if (!isRunning) return
        lastActivity = now()
    }

    fun checkNow() {
        if (!isRunning) return
        val remaining = timeoutMs - (now() - lastActivity)
        if (remaining <= 0) {
            stop()
            onExpired()
        } else {
            scheduleCheck(remaining)
        }
    }

    fun stop() {
        timeoutMs = 0
        pending?.let(cancel)
        pending = null
    }

    private fun scheduleCheck(delayMs: Long) {
        pending?.let(cancel)
        val r = Runnable {
            pending = null
            checkNow()
        }
        pending = r
        schedule(delayMs, r)
    }
}
```

- [x] **Step 4: Reason plumbing in `OperatorSessionHolder.kt`**

Replace lines 22–34 with (add imports `kotlinx.coroutines.flow.getAndUpdate`):
```kotlin
@Singleton
class OperatorSessionHolder @Inject constructor() {
    private val _session = MutableStateFlow<OperatorSession?>(null)
    val session: StateFlow<OperatorSession?> = _session.asStateFlow()

    private val _signedOutReason = MutableStateFlow<String?>(null)

    /**
     * Why the app itself ended the last session (inactivity), for Login to show once. Null after
     * a manual logout, a server `operator_session_invalid`, or a new login. In memory only, like
     * the session: a process restart forgets both (restart semantics are unchanged on purpose).
     */
    val signedOutReason: StateFlow<String?> = _signedOutReason.asStateFlow()

    fun set(session: OperatorSession) {
        _session.value = session
        _signedOutReason.value = null
    }

    /** Ends the session; [reason] is operator wording shown on Login, or null for a silent end. */
    fun clear(reason: String? = null) {
        _session.value = null
        _signedOutReason.value = reason
    }

    /** Hands the pending reason to Login exactly once. */
    fun consumeSignedOutReason(): String? = _signedOutReason.getAndUpdate { null }
```
(`clearIf` and `currentSessionIdOrEmpty` below stay as they are.)

- [x] **Step 5: The plural string**

`app/src/main/res/values/strings.xml` becomes:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Station 2</string>

    <!-- Forced sign-out (S1 spec §3, applied fleet-wide). A plural, not "1 minutes". -->
    <plurals name="signed_out_inactivity">
        <item quantity="one">Signed out after %1$d minute of inactivity.</item>
        <item quantity="other">Signed out after %1$d minutes of inactivity.</item>
    </plurals>
</resources>
```

- [x] **Step 6: Create `SessionGuard.kt`**

```kotlin
package com.mitas.ppnam.station2aa.data.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.mitas.ppnam.station2aa.R
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AutoLogout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide owner of the inactivity auto sign-out (S1's SessionGuard, without the
 * station-offline trigger — S2 keeps its pill-only policy for that).
 *
 * Ends in [OperatorSessionHolder.clear] with a reason; `SessionWatcher` then navigates to Login,
 * which shows the reason once. Installed once from `PpnamApplication`; the Activity calls [touch]
 * on every interaction and [checkNow] on resume; scans count as activity via [ScanEventBus].
 * Everything runs on the main thread, where the monitor's Handler lives.
 */
@Singleton
class SessionGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionHolder: OperatorSessionHolder,
    private val settingsRepository: SettingsRepository,
    private val scanEventBus: ScanEventBus,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var minutes = AutoLogout.DEFAULT_MINUTES

    private val monitor = InactivityMonitor(
        now = { SystemClock.elapsedRealtime() },
        schedule = { delay, r -> mainHandler.postDelayed(r, delay) },
        cancel = { r -> mainHandler.removeCallbacks(r) },
        onExpired = ::expire,
    )

    fun install() {
        // Start/stop the timer with the session itself.
        scope.launch {
            sessionHolder.session.collect { session ->
                if (session == null) monitor.stop() else applyTimeout()
            }
        }
        // Scanner broadcasts never pass through onUserInteraction.
        scope.launch { scanEventBus.events.collect { touch() } }
    }

    /** Any operator interaction or scanner read. Safe from any thread. */
    fun touch() {
        mainHandler.post { monitor.touch() }
    }

    /** A deadline that passed while the app was backgrounded is caught on the next resume. */
    fun checkNow() {
        mainHandler.post { monitor.checkNow() }
    }

    /** (Re)reads the configured timeout; called when a session starts and after Settings saves. */
    fun applyTimeout() {
        scope.launch {
            if (sessionHolder.session.value == null) return@launch
            minutes = settingsRepository.current().autoLogoutMinutes
            monitor.start(AutoLogout.timeoutMs(minutes))
        }
    }

    /** Idempotent: a second trigger racing the first finds no session and does nothing. */
    private fun expire() {
        if (sessionHolder.session.value == null) return
        Log.i(TAG, "Operator inactive for $minutes min — signing out")
        sessionHolder.clear(
            context.resources.getQuantityString(R.plurals.signed_out_inactivity, minutes, minutes)
        )
    }

    private companion object {
        const val TAG = "SessionGuard"
    }
}
```

- [x] **Step 7: Install it in `PpnamApplication.kt`**

Add `import com.mitas.ppnam.station2aa.data.session.SessionGuard`, the field `@Inject lateinit var sessionGuard: SessionGuard` after line 16, and `sessionGuard.install()` as the last line of `onCreate()`.

- [x] **Step 8: Touch and resume hooks in `MainActivity.kt`**

Add `import com.mitas.ppnam.station2aa.data.session.SessionGuard` and `import javax.inject.Inject`. Inside the class, before `onCreate`:
```kotlin
    @Inject lateinit var sessionGuard: SessionGuard
```
After `onCreate` (before the class's closing brace):
```kotlin
    /** Every touch or key press is activity for the inactivity auto sign-out (S1's SessionActivity). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        sessionGuard.touch()
    }

    override fun onResume() {
        super.onResume()
        sessionGuard.checkNow()
    }
```

- [x] **Step 9: Show the reason in `LoginViewModel.kt`**

Change the constructor and `init` (lines 23–45) to:
```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authUseCase: AuthUseCase,
    private val mqttRepository: MqttRepository,
    sessionHolder: OperatorSessionHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    init {
        // "Signed out after N minutes of inactivity." — shown once in the error slot, the way S1
        // does; a manual logout or a server-side session end carries no reason and shows nothing.
        sessionHolder.consumeSignedOutReason()?.let { _uiState.value = LoginUiState.Error(it) }
        viewModelScope.launch { mqttRepository.connect() }
    }
```
Add `import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder`.

- [x] **Step 10: Re-arm after a save in `SettingsViewModel.kt`**

Add `import com.mitas.ppnam.station2aa.data.session.SessionGuard`; add the constructor parameter `private val sessionGuard: SessionGuard,` after `pinLockoutStore`; in `testAndApply()` insert `sessionGuard.applyTimeout()` immediately after `settingsRepository.save(settings)`.

- [x] **Step 11: Run the suite, build, install**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline
.\gradlew.bat :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
```
Expected: green.

- [x] **Step 12: Manual verification**

1. Settings → unlock → Auto sign-out `1` → Test & Apply. Log in as `operator1`. Do not touch the emulator for 65 s → the app returns to Login showing red "Signed out after 1 minute of inactivity." above the fields; `logcat -s SessionGuard` shows "Operator inactive for 1 min — signing out".
2. Log in again; every 30 s tap the screen somewhere harmless for 2 minutes → no sign-out. Then send a scan broadcast at 50 s and wait another 50 s → still signed in (the scan counted).
3. Log in, press Home at 20 s, wait 60 s, reopen the app → Login with the reason (caught on resume).
4. Log in, logout icon → "Log out" → Login shows **no** reason.
5. Set Auto sign-out back to `15`.

- [x] **Step 13: Commit**

```powershell
git add app/src/main/java/com/mitas/ppnam/station2aa/data/session app/src/main/res/values/strings.xml app/src/main/java/com/mitas/ppnam/station2aa/PpnamApplication.kt app/src/main/java/com/mitas/ppnam/station2aa/MainActivity.kt app/src/main/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModel.kt app/src/main/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModel.kt app/src/test/java/com/mitas/ppnam/station2aa/data/session app/src/test/java/com/mitas/ppnam/station2aa/ui/login/LoginViewModelTest.kt app/src/test/java/com/mitas/ppnam/station2aa/ui/settings/SettingsViewModelTest.kt
git commit -m "feat(session): inactivity auto sign-out with a reason shown on Login

S1's InactivityMonitor/SessionGuard ported: configurable minutes
(0 = never), reset on touch and scan, checked on resume, plural reason
text (audit static-05, group j). Restart semantics unchanged.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
```

---

### Task 16: Version bump, full regression on the emulator, provisioning hand-off

Closes: the §7 deployment finding (stale provisioning APK) by producing an identifiable build; the final cross-check of every S2 finding.

**Files:**
- Modify: `app/build.gradle.kts:17-18`

- [x] **Step 1: Bump the version so the fleet can tell this build from v1.2.0**

Lines 17–18 of `app/build.gradle.kts`:
```kotlin
        versionCode = 3
        versionName = "1.3.0"
```

- [x] **Step 2: Clean build, full unit suite, install**

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleDebug --offline
adb -s emulator-5556 install -r -g app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5556 shell dumpsys package com.mitas.ppnam.station2aa | findstr versionName
```
Expected: `versionName=1.3.0`; Settings → Diagnostics VERSION reads `v1.3.0 (3)`.

- [ ] **Step 3: Regression pass (tick each)** — delegated to the verification agent (emulator in use by Station 3 during implementation)

Backend in happy mode, settings `10.0.2.2:9001` WS on TLS off `test`/`test`.
- [ ] Login: empty submit → "Please fill in all fields" above the fields; wrong password → "Incorrect username or password"; with the error shown and the keyboard up, Log In's bottom bound < IME top; eye toggle works; Enter submits; no gear halo; Back → "Close the app?" [Stay | Close]. (S2-01, S2-07, S2-08)
- [ ] Rotation requested via `user_rotation 1` → app stays portrait on Login, Settings, Job Cards. (S2-02, S2-11)
- [ ] Settings: Diagnostics order Broker / Station 2 / Version / Device ID, device id visible; PIN error above the IME; empty Unlock not counted; lockout persists across Back/reopen and force-stop, counts down, disables the gate; Port can be emptied, `90019` rejected inline; password blank keeps; auto sign-out field validates 0–1440; Done closes the keyboard; "Connected — settings saved" persists after the re-lock; broker-unreachable failure is operator wording. (S2-03, S2-04, S2-10, S2-13, group c, static-06/17/20)
- [ ] Pill never flashes Offline on screen entry; pill and Diagnostics agree ("Offline"/"Reconnecting"/"Connected"). (S2-09)
- [ ] Job Cards: top bar shows "Operator One" without ellipsis; timeout mode → ≈10 s then "Station 2 did not respond. Check the station and retry." + Retry on list, lookup and detail; one publish per lookup in `MqttLog`; field refuses letters and a 13th digit. (S2-05, S2-12, S2-13)
- [ ] Error mode → upgrade gate with "(v1.3.0)" and a working "Close app". (S2-06)
- [ ] Home: Back → "Close the app?"; logout dialog confirm "Log out". (§5 Back / Dialog rows)
- [ ] Auto sign-out `1` min → reason on Login; manual logout → no reason. (static-05)
- [ ] Detail values in the body face ("50 each"). (S2-14)
- [ ] Theme green `#1D6B45` everywhere the accent used to be blue. (S2-16)
- [ ] `adb -s emulator-5556 shell logcat -d -s AndroidRuntime:E` is empty after the whole pass.

- [x] **Step 4: Commit and hand off** (graphify update skipped per caller override; no push)

```powershell
git add app/build.gradle.kts
git commit -m "chore(release): v1.3.0 (3) — UI audit fixes

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01ExhLEukYAu1CqjJUWPR64Q"
graphify update .
git status --porcelain   # expect only the pre-existing .pyc and graphify-out changes
```
Hand-off note for whoever provisions: **rebuild `C:\Dev\Clients\PPNAM\PPNAM_Provisioning\apks\PPNAM_Station_2_AA.apk` from this branch** (a release build signed with the provisioning key) — the bundle currently carries v1.0 (1) on schema 4.1, which the rev2.1 backend rejects on every login — and check which Station 2 version each C72 in the fleet is running before rollout. Do not push or merge from this plan; that is the human partner's call after review.

---

## Coverage summary

| Finding | Task |
|---|---|
| S2-01 | 7 |
| S2-02 | 2, 7, 9 |
| S2-03 | 11, 12 |
| S2-04 | 11, 12 |
| S2-05 | 14 |
| S2-06 | 13 |
| S2-07 | 7, 8 |
| S2-08 | 5, 7, 12 |
| S2-09 | 4, 12 |
| S2-10 | 12 |
| S2-11 | 2, 14 |
| S2-12 | 5 |
| S2-13 | 11, 12, 14 |
| S2-14 | 6, 12 |
| S2-15 | **not covered** — brief: session persistence across restart is out of scope (restart semantics unchanged) |
| S2-16 / static-02 | 3 |
| static-03 (badge login) | **not covered** — brief: no contract, product decision |
| static-05 (session policy) | 15 (inactivity + reason only; persistence excluded as above) |
| static-06 (Settings standard) | 11, 12 |
| static-08 (timeout) | 14 |
| static-17 (Diagnostics order) | 12 |
| static-18 (pill vocabulary) | 12 (S2's "Clock out of sync" kept as a red/orange pill state per §5) |
| static-20 (password toggle / blank keeps) | 7, 12 |
| static-25 (empty login check) | 7 |
| static-26 (chip) | 5 |
| §5 gear icon, Back on Login/Home, M3 dialog style, danger red | already the S2 behaviour; verified in Task 16 |
| §7 deployment (stale provisioning APK) | 16 (hand-off note) |

## Self-review notes

- Spec coverage: every S2 row of §4, every S2 item in §3 (a, b, c, d, e, f, g, h, i, j, m) and §7, and every §5 "Recommended standard" row is either implemented above or listed as excluded by the brief (S2-15 persistence, static-03 badge login, static-07 station-offline overlay, broker defaults static-20/k). §7 item 4's `imePadding()` for S2 is intentionally replaced by the bring-into-view fix (explained in Task 7).
- Type consistency: `BrandPrimary`/`OnBrandPrimary` (Task 3) are what Tasks 7, 12 and 14 use; `connectionStatusIn` (Task 4) is what Tasks 11 and 15 use; `PinLockoutStore`/`AutoLogout`/`AppSettings.parsePort`/`autoLogoutMinutes` (Task 10) are what Task 11 consumes; `SessionGuard.applyTimeout()` (Task 15) is the only method Task 15's `SettingsViewModel` edit calls; `LoginViewModel`'s third constructor argument is added in Task 15 and the Task 4 test call is updated there.
- Placeholders: none — every step carries the code or the exact command.
