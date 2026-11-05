# Test cases: (area, guide refs, priority, title, preconditions, test data, steps, expected, code ref)
JA = "JC 510023064 (1 mix; LF 2220 100 kg/mix, dummytag_51)"
JB = "JC 510023052 (3 mixes; C4LL 1018.BU 50 kg dummytag_47, LD 07523.AC 50 kg dummytag_52, ANTI BLOCK 0.5 kg dummytag_23)"
JC = "JC 510023069 (9 mixes; PW60 conditional 3 kg dummytag_20)"
JD = "JC 510023059 (2 mixes; LD 07523.AC dummytag_52 and LD CLEAR dummytag_57 are 'Not in General recipe')"
JE = "JC 510022015 (9 mixes; ambiguous full-order line for 1600000003, Each levy line, 4 off-recipe lines)"
JR = "JC 510023047 on Rajoo (any JC can run on Rajoo; full order C4LL 1018.BU 203.146 kg dummytag_47, LD 07523.AC 67.715 kg dummytag_52) + the commissioned Rajoo machine code"
JBIG = "JC 510022852 (78 mixes)"
LOGIN = "Signed in as test operator"
C = []
def c(*a): C.append(a)

# ---------------- Environment ----------------
A = "ENV Environment"
c(A,"Read first","P1","Station 2 build carries the 27 Oct collection rules","Station 2 desktop/server updated","-","1. Confirm the Station 2 build/version with the station owner.\n2. Note contract revision 2026-10-27 / schema rev2.1.","Build confirmed and recorded in the run header. Block the run if not.","-")
c(A,"Read first","P1","SAP posting state recorded before finish tests","Station 2 workstation access","-","1. Check the active station's SAP posting state (guide: enabled, not paused).\n2. Agree with the owner whether finish tests may queue a real Issue for Production.","State recorded. Finish tests (MIX, RAJ end/close, DEC SAP) run only with explicit agreement.","-")
c(A,"Seq 10","P1","Sniffer and logcat capture running","Laptop on the same broker","-","1. Start the passive MQTT sniffer on PPNAM/station_2/#.\n2. Start adb logcat capture.","Every request/reply in the run is captured with messageId, fingerprint and server time (guide step 10).","tools/test-harness")
c(A,"Read first","P2","Scanner app version and device ID recorded","App installed","-","1. Open Settings.\n2. Read VERSION and DEVICE ID.","Version shows 'vX (code)'; DEVICE ID matches the deviceId in published topics.","ui/settings/SettingsScreen.kt:95")

# ---------------- Transport ----------------
A = "MQ Transport & presence"
c(A,"Seq 1-2; T01","P1","Device presence online is retained QoS 2 on its own topic",LOGIN,"-","1. Launch the app with the broker reachable.\n2. In the sniffer, inspect PPNAM/station_2/{deviceId}.","Retained 'online' at QoS 2. Requests use /{deviceId}/req/*; the app subscribed to /{deviceId}/res/+ before its first request.","data/mqtt/MqttTopics.kt")
c(A,"Seq 2","P1","Last Will publishes retained offline on abrupt loss","App connected","-","1. Put the scanner in airplane mode (no clean disconnect).\n2. Wait for broker keepalive expiry.","Broker publishes retained 'offline' on PPNAM/station_2/{deviceId}.","data/mqtt/MqttRepositoryImpl.kt")
c(A,"Seq 2","P1","Reconnect re-establishes fresh online presence","After the previous test","-","1. Turn airplane mode off.\n2. Watch the pill and the sniffer.","Pill goes Reconnecting → Connected; a fresh retained 'online' is published. Station 2 shows Online only after it is heard again.","MqttRepositoryImpl.kt:243-252")
c(A,"Seq 2","P2","Own presence overwritten to offline is corrected","App connected","-","1. From the sniffer laptop publish retained 'offline' on the device presence topic.","App re-publishes 'online' while connected.","MqttRepositoryImpl.kt:325-347")
c(A,"-","P2","Clean close publishes offline","App connected","-","1. Back on Home → 'Close the app?' → Close.","Retained 'offline' published.","MqttRepositoryImpl.kt:841")
c(A,"-","P1","Connection pill priority and debounce","App open","-","1. Toggle Wi-Fi off/on quickly (<1.5 s).\n2. Toggle off and hold.\n3. Stop Station 2 (or its presence).","Short blips do not flash. Held states show Offline > Reconnecting > Station 2 offline > Clock out of sync > Connected. The first value shown is the true state.","ui/components/ConnectionStatus.kt:33-78")
c(A,"-","P2","Clock skew >30 s shows 'Clock out of sync'","App connected","-","1. Set the device clock 2 min wrong (auto time off).\n2. Make any request.\n3. Restore the clock.","Pill shows 'Clock out of sync', then returns to Connected.","MqttRepositoryImpl.kt:739-756")
c(A,"Topics","P1","Workflow requests QoS 1, retain false, exact envelope",LOGIN,JA,"1. Do a lookup.\n2. Inspect the request in the sniffer.","QoS 1, retain false; schemaVersion rev2.1; deviceId = topic device; new messageId; timestampUtc yyyy-MM-ddTHH:mm:ss.ffffffZ; current sessionId; quantities/counts/revisions are JSON numbers.","data/mqtt/RequestEnvelope.kt")
c(A,"-","P2","Request timeout from Settings honoured, no auto-retry",LOGIN,"-","1. Set Request timeout 3000 ms.\n2. Pause Station 2 processing.\n3. Do a lookup.","Error after ~3 s with Retry; the sniffer shows exactly one request.","MqttRepositoryImpl.kt:547,564")
c(A,"-","P2","Not connected: reads fail fast, commands send nothing",LOGIN,JB,"1. Go offline.\n2. Try a lookup.\n3. On a Prep screen try a capture.","Lookup: 'Not connected'. Capture: 'Not connected — nothing was sent'; nothing in the outbox; no request in the sniffer.","MqttRepositoryImpl.kt")
c(A,"-","P2","Duplicate-key JSON reply is dropped","Sniffer can publish","-","1. Publish a correlated reply with duplicate field names (case-insensitive).","App ignores it and times out normally. No crash.","data/mqtt/StrictJson.kt")
c(A,"-","P3","Explicit JSON nulls tolerated","Simulator","-","1. Return a snapshot with null optional fields.","Screen renders with defaults; no crash.","data/mqtt/WireJson.kt")
c(A,"Seq 9","P2","Uncorrelated messages ignored except the invalidation hint",LOGIN,"-","1. Publish a reply with an unknown inResponseToMessageId.\n2. Publish active_job_cards_invalidated.\n3. Re-publish (2) with the same id.","(1) ignored. (2) refreshes the relevant screens. (3) suppressed.","MqttRepositoryImpl.kt")
c(A,"Seq 3","P2","No password field ever published; >64 KiB refused","Sniffer running","-","1. Log in by password and badge.\n2. Search the capture for 'password'.\n3. Check OutboundGuardTest in CI.","No JSON field containing 'password' is published; oversized requests are refused before sending.","data/mqtt/OutboundGuard.kt")
c(A,"-","P3","Logs redact secrets",LOGIN,"-","1. Log in and work.\n2. Search logcat for the sessionId value, SCRAM proof and 'password'.","Values appear as ***.","data/mqtt/Redact.kt:19-38")
c(A,"Seq 1","P2","Commissioned broker: WSS 443 and plant LAN","Supervisor PIN","-","1. Configure mqtt.sysone.co.za:443 WebSocket + TLS → Test & Apply.\n2. Configure the plant LAN host/port as commissioned → Test & Apply.","Both connect and save.","ui/settings/SettingsViewModel.kt:303")

# ---------------- Auth ----------------
A = "AUTH Login"
c(A,"Seq 3; T01","P1","SCRAM password login succeeds","Valid operator account","-","1. Pick the username from the dropdown.\n2. Enter the password, tap Log in.","scram_start_requested → scram_challenge → scram_proof_requested → scram_proof_result. Home opens; Login removed from the back stack.","data/auth/ScramExchange.kt")
c(A,"T01","P1","Session is device-bound","Signed in on scanner A","-","1. Note A's sessionId (sniffer).\n2. Publish a request on scanner B's topic (or with B's deviceId) carrying A's sessionId.","Station 2 refuses it; scanner A is unaffected.","-")
c(A,"T01","P1","Expired session cannot work",LOGIN,JA,"1. Expire the session on Station 2 (or wait for server expiry).\n2. Do a lookup.","'Your session has ended — sign in again'; app returns to Login; nothing executed.","MqttRepositoryImpl.kt:826-834")
c(A,"-","P1","Wrong password","Valid username","-","1. Enter a wrong password.","authentication_failed → 'Incorrect username or password'. Stays on Login.","ScramExchange.kt")
c(A,"-","P2","Blank fields","-","-","1. Tap Log in with a blank username or password.","'Please fill in all fields'; nothing sent.","ui/login/LoginViewModel.kt:118")
c(A,"-","P2","Double submit ignored","-","-","1. Tap Log in twice quickly, or scan a badge during sign-in.","One exchange only.","LoginViewModel.kt:125")
c(A,"-","P2","Station 2 not responding / offline","Station 2 stopped or broker offline","-","1. Try to log in.","'Station 2 did not respond. Check the station and retry.' / 'Not connected to Station 2'.","domain/usecase/AuthUseCase.kt:121")
c(A,"Seq 3","P1","Operator card (badge) login","Registered badge (EPC 505055…)","Operator badge","1. On Login, tap the badge on the RFID reader.","badge_login_requested → badge_login_result; Home opens. Barcode scans are ignored on Login.","AuthUseCase.kt:40-119")
c(A,"-","P1","Unregistered or inactive badge","Unregistered badge","-","1. Tap an unregistered card.","badge_rejected → 'This badge is not registered or is no longer active.'","AuthUseCase.kt")
c(A,"Topics","P1","Dummy stock tag is not a login card","On Login","dummytag_44","1. RFID-scan dummytag_44 on Login.","Refused; no session issued.","-")
c(A,"Retries","P2","Badge retry resends identical bytes and id","Badge; Station 2 paused","-","1. Tap the badge; no reply.\n2. Resume Station 2; tap the same badge.","Second publish has identical bytes and messageId. If answered operator_session_invalid, one automatic re-send with a new id.","MqttRepositoryImpl.kt:491-524")
c(A,"-","P2","Operator dropdown cached and refreshed","Logged out","-","1. Open the dropdown offline.\n2. Go online on Login.","Cached list offline; refreshed on connect. Selecting fills the username and focuses Password. A typed username still works.","domain/usecase/OperatorDirectoryUseCase.kt")
c(A,"-","P3","Password show/hide; password not kept on configuration change","-","-","1. Toggle the eye.\n2. Trigger a configuration change.","Toggle works; username kept, password cleared.","ui/login/LoginScreen.kt:56")
c(A,"-","P2","Signed-out reason after inactivity","Auto sign-out 1 min","-","1. Sign in, idle >1 min.\n2. Separately, log out manually.","'Signed out after 1 minute of inactivity.' shown once (plural for >1). Manual logout shows no reason.","OperatorSessionHolder.kt:42")
c(A,"-","P2","Login accepted with no session / closed session","Simulator","-","1. Return success with no session.\n2. Return a closed session.","'Station 2 accepted the login but issued no session' / 'Station 2 closed this session immediately'.","AuthUseCase.kt:64-93")
c(A,"-","P3","Untrusted SCRAM server signature","Simulator","-","1. Return a bad server signature.","'Station 2 failed authentication verification — this response is not trusted'.","ScramExchange.kt:106")

# ---------------- Settings ----------------
A = "SET Settings"
c(A,"-","P1","Diagnostics visible without PIN","-","-","1. Open Settings from Login, Home and Job Lookup.","MQTT BROKER, STATION 2, VERSION and DEVICE ID shown; edit fields locked.","SettingsScreen.kt:95-136")
c(A,"-","P1","Supervisor PIN unlock","-","Supervisor PIN","1. Enter the correct PIN → Unlock.","Fields editable.","SettingsViewModel.kt:69")
c(A,"-","P1","Wrong PIN countdown; 30 s lockout survives app kill","-","-","1. Enter a wrong PIN 5 times.\n2. Back, reopen; force-stop and relaunch.","'N attempts left' (singular at 1); 'Too many attempts. Try again in Ns.' counts down and survives reopen and kill.","data/settings/PinLockoutStore.kt")
c(A,"-","P2","Field validation","Unlocked","-","1. Blank host.\n2. Port 0 / 70000.\n3. Timeout 500.\n4. Auto sign-out 2000.","'Host required' / 'Invalid port (1–65535)' / 'Enter 1000–60000 ms' / 'Enter 0–1440'. Fields can be fully cleared.","SettingsViewModel.kt:281-301")
c(A,"-","P1","Test & Apply success","Unlocked","-","1. Valid settings → Test & Apply.","'Testing connection…' then 'Connected — settings saved'; password field clears; PIN gate relocks after 2 s.","SettingsViewModel.kt:303-328")
c(A,"-","P1","Test & Apply failure keeps the old connection","Unlocked","-","1. Wrong host → Test & Apply.\n2. Wrong TLS setting → Test & Apply.","Timeout or connect-failure message; existing connection stays in use.","SettingsViewModel.kt")
c(A,"-","P2","Blank broker password keeps the stored one","Unlocked","-","1. Leave password blank → Test & Apply.","Connects with the stored password.","SettingsScreen.kt")
c(A,"-","P2","Log out from the Settings session card",LOGIN,"-","1. Settings → Log out → confirm.","Returns to Login; nothing is published (logout is local only).","SettingsScreen.kt:332")

# ---------------- Navigation ----------------
A = "NAV App shell"
c(A,"-","P2","Back on Login/Home asks to close","-","-","1. Press Back on Home, and on Login with the keyboard closed and open.","'Close the app?' with Close / Stay. With the keyboard open, Back only hides it.","ui/home/HomeScreen.kt:78")
c(A,"-","P2","Top-bar logout confirmation",LOGIN,"-","1. Tap the operator label → Log out.","Confirmation; Log out returns to Login and clears the back stack.","ui/components/AppScaffold.kt:51")
c(A,"Errors","P1","client_upgrade_required gate","Simulator/server returns the code","-","1. Trigger any reply with client_upgrade_required.\n2. Close app, relaunch.","Blocking 'App update required' dialog on every screen; outside tap ignored; Close app closes; relaunch does not show it again.","ui/components/UpgradeGate.kt")
c(A,"-","P3","Portrait lock; dialog buttons not under the keyboard","-","-","1. Rotate.\n2. Open dialogs with text fields.","Stays portrait; action buttons visible above the keyboard.","MainActivity.kt")
c(A,"-","P2","Home greeting and tiles",LOGIN,"-","1. View Home morning / afternoon / evening.\n2. Tap each tile.","Correct greeting with operator name; Job Cards and Rajoo open the right screens.","HomeScreen.kt")

# ---------------- Session ----------------
A = "SES Session lifecycle"
c(A,"-","P1","Inactivity auto sign-out","Auto sign-out 2 min",LOGIN,"1. Leave idle 2 min (also with the app in background).","Signed out; reason on Login. Touch, key and scan reset the timer.","data/session/InactivityMonitor.kt")
c(A,"-","P2","Auto sign-out 0 = never","Auto sign-out 0",LOGIN,"1. Leave idle 20 min.","Still signed in (until server expiry).","InactivityMonitor.kt")
c(A,"Seq 9","P1","Session restored after force-stop mid-preparation",LOGIN+"; Prep open",JB,"1. Force-stop the app.\n2. Relaunch within the timeout.","Straight to Home with the same session; the Prep reopens and re-reads; no new login request.","data/session/SessionPersistence.kt")
c(A,"-","P1","Idle time carries across restart (no timeout bypass)","Auto sign-out 2 min",LOGIN,"1. Idle 90 s, force-stop.\n2. Relaunch, wait 40 s.\n3. Repeat with relaunch after >2 min.","Signed out at ~2 min total idle; relaunch does not reset the clock. Relaunch after >2 min goes to Login.","SessionPersistence.kt:141-146")
c(A,"-","P2","Restore refused when the clock moved back","-",LOGIN,"1. Force-stop, set the clock back 1 h, relaunch.","Login screen; session not restored.","SessionPersistence.kt")
c(A,"-","P2","Timeout change applies immediately",LOGIN,"-","1. Set auto sign-out to 1 min in Settings.","The next idle minute signs out.","SessionGuard.kt")
c(A,"T01","P2","Stale operator_session_invalid does not end the current session",LOGIN,"-","1. Have an old-session command answered operator_session_invalid after re-login.","Current session is not cleared.","MqttRepositoryImpl.kt:826")

# ---------------- Identify / stock ----------------
A = "STK Identify & stock"
c(A,"Seq 4; T02","P1","Identify a dummy tag — no deduction",LOGIN+"; Prep collecting",JB,"1. Note dummytag_47 balance on Station 2.\n2. Scan dummytag_47.\n3. Cancel the collect sheet.","Sheet shows 1600000039, kg, Bag size 25, On pallet = current balance. Balance unchanged afterwards.","GeneralCollectionUseCase.kt")
c(A,"T02","P2","Identify every register tag (sweep)",LOGIN,"Stock Register sheet","1. For each of the 63 tags, scan on a collecting Prep; record what the sheet shows.","Each tag shows its register material/code, 25 kg bag and current balance; no deduction.","-")
c(A,"Register","P1","Shared tag balance is shared across jobs",LOGIN,"dummytag_47 on JC 510023052 and JC 510023030","1. Capture 25 kg of dummytag_47 on 510023052.\n2. Identify dummytag_47 from a PREP of 510023030.","On pallet is 25 kg lower — one balance across jobs.","-")
c(A,"Errors","P1","Unknown tag","Collecting Prep","An EPC not in the register","1. Scan it.","'Pallet not recognised' (pallet_not_found). No stock created.","CollectState.kt")
c(A,"-","P2","Identify timeout / unsupported fallback picker","Slow Station 2 or simulator",JB,"1. Make identify time out.","'Station 2 didn't answer in time — pick the ingredient'; picking a row pre-fills its remaining.","GeneralCollectionUseCase.kt:182-215")

# ---------------- Lookup / detail ----------------
A = "LKP Job lookup & detail"
c(A,"Seq 5; T03","P1","Look up a qualified General JC by scanning the job card",LOGIN,JB,"1. Home → Job Cards.\n2. Scan the JC barcode (or type 510023052) → Look up.","Job Detail: product, Required / Active / Available to prepare / Finished, materials matching exact order membership; Prepare mixes enabled.","ui/joblookup/JobLookupScreen.kt")
c(A,"-","P2","Input validation","Job Lookup","-","1. Type letters / full-width digits / >12 digits.\n2. Leave blank.","'Scan or enter the production order number (digits only)'; Look up disabled when blank.","JobLookupUseCase.kt")
c(A,"-","P2","PREP id opens the Prep directly","Known PREP id","PREP_…","1. Type a PREP_ id → Look up.","Prep opens; no lookup request sent.","JobLookupUseCase.kt:55")
c(A,"-","P2","Badge scanned on Job Lookup","-","Operator badge","1. Scan a badge.","'That is a user badge, not a job card. Use the login screen to sign in.'","JobLookupViewModel.kt")
c(A,"T14","P1","Unknown JC","-","JC 999999999","1. Look up.","Readable refusal; nothing created.","JobLookupUseCase.kt")
c(A,"Seq 9","P2","Job list refreshes on return, reconnect and hint",LOGIN,"-","1. Look up two jobs.\n2. Leave and return.\n3. Trigger an invalidation hint.","'Jobs on Station 2' re-read each time; empty-state text when none.","JobLookupScreen.kt:137-181")
c(A,"Seq 6; T10","P1","Job Detail shows job scope; PREP keeps its own targets",LOGIN,JB,"1. Prepare 2 mixes.\n2. Compare Job Detail materials with the Prep screen.","Job Detail follows data.job (materialQuantityScopeLabel); the Prep uses data.preparation.materials. Neither replaces the other.","JobDetailScreen.kt")
c(A,"T07","P1","Order-excluded lines shown as Excluded",LOGIN,JD,"1. Look up 510023059.","LD 07523.AC / LD CLEAR absent or dimmed 'Excluded'; not collectable.","JobDetailScreen.kt")
c(A,"T14","P1","Each / levy / unsupported-unit lines not converted to kg",LOGIN,JE,"1. Look up 510022015.","No kg target for the Each levy line; no collectable row for it.","-")
c(A,"-","P2","Prepare disabled when closed or nothing available","Closed or fully prepared job","-","1. Open such a job.","Prepare mixes disabled; 'Closed' shown when closed.","JobDetailScreen.kt:95-100")
c(A,"-","P2","Active preparations listed and openable",LOGIN,JB,"1. After a prepare, open Job Detail.\n2. Tap the PREP.","PREP listed under Active preparations; tap opens it.","JobDetailScreen.kt")
c(A,"-","P2","Lookup errors and Retry","Station 2 paused","-","1. Look up while Station 2 is paused / offline.","Error; Retry only for timeout / not connected.","JobLookupViewModel.kt:77")

# ---------------- Prepare ----------------
A = "PRE Prepare"
c(A,"Seq 5; T03","P1","Create a 1-mix PREP",LOGIN,JB,"1. Job Detail → Prepare mixes.\n2. Mixes = 1 → Prepare → confirm.","One new PREP id; Prep opens; targets = per mix × 1 (50 / 50 / 0.5 kg). One prepare request in the sniffer.","PrepareViewModel.kt")
c(A,"T03","P1","Create an N-mix PREP multiplies targets",LOGIN,JB,"1. Prepare 2 mixes.","Targets 100 / 100 / 1.0 kg.","PrepareForm.kt")
c(A,"Read first","P1","Mix count bounded by availableToPrepareMixes",LOGIN,JBIG,"1. Try 0, 79 and the maximum shown.\n2. Use −/+ at the bounds.","'N must be a whole number from 1 to {available}'; − stops at 1, + stops at available. Count is not derived from the shortened table.","PrepareForm.kt:29-45")
c(A,"Register","P1","Conditional PW60 requires explicit Include/Exclude",LOGIN,JC,"1. Try to prepare without choosing.\n2. Include, prepare 1 mix.\n3. New PREP with Exclude.","(1) 'Choose Include or Exclude for 1500000306', button disabled. (2) PW60 3 kg target. (3) PW60 row excluded; confirm lists 'Excluded: …'.","PrepareForm.kt")
c(A,"-","P2","Excluding every material refused",LOGIN,JC,"1. Exclude all choosable lines.","'Excluding every material leaves nothing to prepare'.","PrepareForm.kt")
c(A,"-","P3","Reason length limit","-",JC,"1. Paste >1000 chars into Reason.","'Reason is longer than 1000 characters'; counter n/1000.","PrepareForm.kt")
c(A,"-","P2","Warning when active preparations exist",LOGIN,JB,"1. With an active PREP, open Prepare.","'This job already has N active preparation(s)…'.","PrepareScreen.kt")
c(A,"Retries; T08","P1","Unconfirmed prepare: Retry resends identical bytes",LOGIN,JB,"1. Block the reply; tap Prepare.\n2. Restore; tap Retry.","'Station 2 has not confirmed this prepare. Don't prepare again — Retry.' Retry publishes identical bytes/messageId; one PREP only.","PrepareViewModel.kt")
c(A,"Errors","P2","message_id_conflict on prepare triggers recovery","Simulator",JB,"1. Force message_id_conflict.","App recovers at once; a committed result opens the PREP.","PrepareViewModel.kt:267-289")
c(A,"Seq 5","P1","Save and reopen the PREP with read",LOGIN,JB,"1. Leave the Prep.\n2. Reopen from Job Detail or by typing the PREP_ id.","Read returns the same PREP: names, required/collected/remaining, revision.","PreparationViewModel.kt")
c(A,"-","P2","Answers and N survive a refresh",LOGIN,JC,"1. Choose answers, type N.\n2. Trigger a reconnect.","Form keeps answers and N.","PrepareForm.kt:52-59")

# ---------------- Collection ----------------
A = "COL General collection"
c(A,"Seq 6, 8; T04","P1","Capture one full bag, then read the PREP",LOGIN+"; 1-mix PREP",JB,"1. Scan dummytag_47.\n2. Amount 25 → Submit.\n3. Leave and reopen the PREP.","Stock −25 once; C4LL collected 25 / remaining 25 in this PREP's scope.","CollectSheet.kt")
c(A,"Seq 8; T04","P1","Multi-bag submit sends one capture per bag",LOGIN+"; 1-mix PREP",JA,"1. Scan dummytag_51.\n2. Submit 100 kg ('Submit 100 kg (4 bags)').","4 captures of 25 kg, each with a new id; 'Recording … i of 4'; ingredient complete.","BagGuide.kt:50-57")
c(A,"Seq 8; T05","P1","Final fraction smaller than 25 kg accepted exactly",LOGIN+"; 1-mix PREP",JB,"1. Scan dummytag_23 (0.5 kg needed).\n2. Submit 0.5.","Capture quantity 0.5 (JSON number); accepted; not rounded up.","BagGuide.kt")
c(A,"Seq 8; T05","P1","Bag plus remainder split",LOGIN+"; 2-mix PREP",JB,"1. Scan dummytag_47 (100 needed).\n2. Submit 37.5.","Two captures: 25 then 12.5. Remaining 62.5.","BagGuide.kt")
c(A,"T06","P1","More than the remaining target refused on the scanner",LOGIN,JB,"1. Scan dummytag_23 with 0.5 kg needed.\n2. Enter 1.","'More than still needed — 0.5 kg'; nothing sent.","BagGuide.kt:59-104")
c(A,"Topics; T06","P1","More than bag size refused by Station 2","Crafted request (sniffer)",JB,"1. Publish a capture with quantity 30 using a valid session.","Definite rejection; no deduction; an audit exception may be recorded.","-")
c(A,"Topics; T06","P1","Omitted/zero quantity = full bag, refused when over remainder","Crafted request",JB,"1. Publish a capture with no quantity for dummytag_23 (0.5 kg needed).","Rejected; no deduction.","-")
c(A,"-","P2","Amount validation messages","Collect sheet open",JB,"1. Blank.\n2. 'abc'.\n3. 0.\n4. 1.2345.\n5. 12,5.","'Enter an amount' / 'Enter a number, e.g. 12.5' / 'Enter more than 0' / 'At most 3 decimals' / comma accepted.","BagGuide.kt")
c(A,"Topics","P2","Amount above pallet stock","Tag with a low balance","-","1. Enter more than On pallet.","'Only X kg on this pallet'.","BagGuide.kt")
c(A,"T07","P1","Already-complete line refused",LOGIN,JA,"1. After collecting all 100 kg, scan dummytag_51 again.","'Can't collect from this pallet' — 'X is already fully collected'. No deduction.","CollectState.kt")
c(A,"T07","P1","Excluded line refused",LOGIN+"; PREP with PW60 excluded",JC,"1. Scan dummytag_20.","'Excluded from this preparation — can't collect'.","CollectState.kt")
c(A,"T07","P1","Order-excluded line refused",LOGIN+"; PREP of 510023059",JD,"1. Scan dummytag_52 (LD 07523.AC).","'Not in this preparation'; capture only after an accepted Add.","CollectState.kt")
c(A,"T07","P1","Off-BOM tag refused",LOGIN+"; PREP of 510023064",JA+"; dummytag_69","1. Scan dummytag_69 (HD WHITE, not in this job).","'Not in this preparation'; a usable source alone does not authorise collection.","CollectState.kt")
c(A,"-","P2","Typed code via keyboard",LOGIN,JB,"1. Keyboard button → type dummytag_47 → Use code.","Same result as a scan.","CollectHome.kt")
c(A,"-","P2","Scan while a pallet is open or Station 2 is answering","Collect sheet open",JB,"1. Scan a second tag.\n2. Scan during 'Recording…'.","'Finish or cancel this pallet first' / 'Wait — Station 2 is still answering the last bag'.","PreparationViewModel.kt:185")
c(A,"-","P2","Badge or machine code during collection","Collecting",JB,"1. Scan a badge.\n2. Scan a mixer code.","'That is a user badge, not a pallet' / 'That's the {machine} code — collect every ingredient and tap Mix complete first'.","PreparationViewModel.kt")
c(A,"-","P2","Partial multi-bag failure stops at the first unrecorded bag",LOGIN,JA,"1. Submit 100 kg; drop the network after bag 2.","'Recorded 2 of 4 bags…' / 'Not recorded: …'; remaining bags not sent; hold shown if unresolved.","CollectSheet.kt")
c(A,"-","P1","Progress and row states",LOGIN,JB,"1. Collect lines one at a time.","'x of y ingredients collected'; amber outstanding → green ✓; Modified / added tags where applicable.","CollectHome.kt")
c(A,"-","P1","Mix complete confirmation",LOGIN,JA,"1. Collect everything.\n2. Mix complete → Confirm.","'All ingredients collected'; Confirm moves to the mixer stage.","CollectHome.kt")
c(A,"Seq 9; T10","P1","Invalidation hint rebinds the PREP without clearing a pending command",LOGIN,JB,"1. Leave one capture unanswered.\n2. Change the PREP from the desktop or another scanner.","Names, targets, revision and progress refresh; the pending-command hold stays.","PreparationViewModel.kt")
c(A,"-","P3","Exceptions list","PREP with exceptions","-","1. Expand '▸ Exceptions (n)'.","Exceptions listed.","CollectHome.kt")

# ---------------- Decisions ----------------
A = "DEC Decisions / Add / Substitute"
c(A,"Read first; T15","P1","Reasoned Add of an off-SAP material in this PREP, then capture",LOGIN+"; PREP of 510023059",JD,"1. Scan dummytag_57 (LD CLEAR).\n2. 'Not in this preparation' → amount + reason → Add.\n3. Scan again and capture.","Add accepted, row tagged 'added'; capture accepted under normal gates; no Manager review needed.","CollectSheet.kt")
c(A,"T15","P1","Same material in another PREP without a decision is refused",LOGIN,JD,"1. In a second PREP of 510023059, scan dummytag_57 and try to capture without adding.","Refused; the other scope's decision does not authorise it.","-")
c(A,"T15","P2","SAP issue for the added material may be Blocked","SAP posting agreed",JD,"1. Finish the PREP with the added line.\n2. Check SAP Posting on Station 2.","Issue may be Blocked for the missing component; no invented base line.","-")
c(A,"-","P1","Change a row total (less / more)",LOGIN,JB,"1. Tap the C4LL row → Less / more → new total + reason.","Collected, proposed total and still-needed-after shown; accepted change tags the row 'Modified'.","IngredientDecisionDialog.kt")
c(A,"-","P2","Decision validation","Dialog open",JB,"1. Same total.\n2. Below collected.\n3. Omit after collecting.\n4. Blank reason.","'That is the current total…' / 'Can't go below…' / '…already collected — change the total instead' / 'Enter a reason'.","IngredientDecision.kt")
c(A,"-","P2","Omitting the last ingredient refused","1-ingredient PREP",JA,"1. Omit LF 2220.","'At least one ingredient must stay'.","IngredientDecision.kt")
c(A,"Errors","P1","collection_revision_conflict is never re-sent automatically",LOGIN,JB,"1. Open a decision on scanner A.\n2. Change the PREP elsewhere.\n3. Submit on A.","Shown as rejected; read the current PREP; nothing re-sent automatically.","IngredientDecision.kt")
c(A,"-","P2","Decisions blocked in the wrong stage","PREP in mixer stage","-","1. Tap a row.","'Ingredients can't be changed now (stage)'.","IngredientDecision.kt")

# ---------------- Mixer ----------------
A = "MIX Mixer & production"
c(A,"T12","P1","Start a mix on a mixer",LOGIN+"; mix complete",JA,"1. Scan a mixer code → Start mix 1 on M.","'Mixing 1 of 1'; note 'This mixer stays assigned for all n mixes'.","ui/preparation/MixerStage.kt")
c(A,"T13","P1","Wrong machine type / not a machine","Mixer stage",JA,"1. Scan an extruder.\n2. Scan dummytag_51.","'{m} is an extruder — scan a mixer' / 'Not a machine — X'. No assignment.","domain/model/MixerStage.kt")
c(A,"T13","P1","Only the active mixer can finish","Mixing",JA,"1. Scan another mixer.","'Only {active} can finish this mix'.","MixerStage.kt")
c(A,"T13","P2","Disabled machine","Disabled machine","-","1. Scan it.","'Disabled — can't start'.","MixerStage.kt")
c(A,"T12","P1","Finish mix carries the current cycleId once","Mixing",JA,"1. Scan the active mixer → Finish.","Finish carries the current cycle id; saved once.","MixerStage.kt")
c(A,"-","P1","Start production on a free extruder","Mix finished","-","1. Scan an extruder → Start production.","'Producing k of n'; free extruders listed.","MixerStage.kt")
c(A,"T12","P1","Final cycle completes the PREP and queues the SAP issue","SAP posting agreed",JA,"1. Finish the last production.\n2. Check SAP Posting on Station 2.","'Preparation complete' with Back to job. One Issue for Production queued (verify separately from the scanner reply).","-")
c(A,"T13","P2","Stale confirm card after the stage moved","Card open",JA,"1. Advance the stage elsewhere.\n2. Tap the card.","'The preparation moved on — scan the machine again'.","MixerStage.kt")
c(A,"-","P2","Multi-mix PREP keeps the same mixer",LOGIN,JB,"1. In a 2-mix PREP, start mix 2 on a different mixer.","Warning 'Saved mixer is X' or refusal.","MixerStage.kt")

# ---------------- Rajoo ----------------
A = "RAJ Rajoo"
c(A,"Seq 7; T11","P1","Look up a Rajoo JC with the machine code",LOGIN,JR,"1. Home → Rajoo.\n2. Machine auto-selected (one enabled), or scan its code.\n3. Look up the JC.","Rajoo job screen with full-job quantities (data.job.materials) and cumulative progress; 'No session running'. No mix-count multiplication.","ui/rajoo/RajooLookupScreen.kt")
c(A,"T13","P1","Wrong machine code refused",LOGIN,JR,"1. Scan a mixer code on Rajoo lookup and on the job screen.","'X is not the Rajoo machine code — rescan' / 'Not the Rajoo machine code — X'.","RajooLookupViewModel.kt")
c(A,"Seq 7; T11","P1","Start a session with the machine code",LOGIN,JR,"1. Scan the machine code → Start.","RAJ cycleId returned and shown ('Session …last8'); first-session choices auto-answered.","RajooJobViewModel.kt")
c(A,"Seq 7","P1","Capture against JC + exact cycleId","Session running",JR,"1. Scan a BOM tag; submit one bag.","Request targetId = JC and cycleId = active RAJ id; stock −25 once; cumulative collected updates.","RajooUseCase.kt")
c(A,"T05","P1","Rajoo final fraction",LOGIN,JR,"1. Collect dummytag_47 to 200 kg (8 bags).\n2. Capture the remaining 3.146 kg.","Accepted as exactly 3.146; line complete; not rounded up to a bag.","RajooUseCase.kt")
c(A,"T07","P1","Non-BOM pallet refused with a reason","Session running",JR,"1. Scan dummytag_23 (ANTI BLOCK, not on this order).","Refusal with a reason (or the Add / Substitute offer); no capture without a decision.","RajooJobScreen.kt")
c(A,"-","P1","Pallet scan with no session","No session",JR,"1. Scan a BOM tag.","'Scan M to start a session'; nothing captured.","RajooJobViewModel.kt")
c(A,"T12","P1","End the session with the same code","Session with ≥1 capture",JR,"1. Scan the machine code → End.","Session ended once; issue queued under configured posting (check SAP separately).","RajooJobViewModel.kt")
c(A,"T11","P1","A second session adds to the same totals",LOGIN,JR,"1. Start a new session; capture.","New cycleId; cumulative totals continue.","RajooJobScreen.kt")
c(A,"T13","P1","Old or missing cycleId capture refused","Crafted request",JR,"1. Publish a capture with an ended cycleId, then with none.","Refusal; no stock action.","-")
c(A,"-","P1","Close the job card when complete",LOGIN,JR,"1. Collect everything; scan the code → Close.","'Job card closed'; history retained; further scans do nothing.","RajooJobViewModel.kt:117-126")
c(A,"T15","P2","Rajoo-scoped Add / Substitute",LOGIN,JR,"1. During a session scan a non-job pallet → 'Add as an extra ingredient' or 'Instead of {row}' with a reason.\n2. Capture it.","Accepted in this session scope only; capture then allowed.","RajooJobScreen.kt")
c(A,"-","P2","Resume from 'Open Rajoo job cards'",LOGIN,JR,"1. Leave and tap the job in the list.","An open job is looked up again; a closed job opens its history.","RajooLookupScreen.kt")
c(A,"-","P2","Badge scan on Rajoo screens","-",JR,"1. Scan a badge on the lookup and job screens.","Lookup: ignored. Job: 'That is a user badge, not a pallet'.","RajooLookupViewModel.kt:91")
c(A,"T11","P1","A General-qualified JC also runs on Rajoo with full-order targets",LOGIN,JB,"1. Look up 510023052 on Rajoo (not used by another open Rajoo job).\n2. Compare targets with the General per-mix figures.","Rajoo job loads; targets are the full order (C4LL 132.390, LD 07523.AC 132.390, ANTI BLOCK 1.324 kg), not per mix × N.","Rev2Mapping.kt:69")
c(A,"T14","P2","Catalogue-unavailable JC on Rajoo",LOGIN,"JC 510023106 (General catalogue Unavailable; full order all kg)","1. Look up 510023106 on Rajoo.\n2. Start a session and capture one bag of dummytag_69.","The fresh response decides. If accepted, targets are the saved full order (e.g. HD WHITE 84.887 kg); if refused, the reason is shown.","-")
c(A,"T14","P2","Each / levy line on Rajoo not collected as kg",LOGIN,"JC 510023208 (CORES 1502000025, 166.667 each)","1. Look up 510023208 on Rajoo.","The Each line has no kg target and cannot be captured; kg lines behave normally or the job is refused with a reason.","-")
c(A,"-","P2","Same JC used on General and Rajoo",LOGIN,JB,"1. With a General PREP open on 510023052, look up 510023052 on Rajoo (and the reverse).","Station 2 decides; the app shows either the Rajoo job or a readable refusal. No capture lands in the wrong scope.","-")

# ---------------- Retries / recovery ----------------
A = "RET Retry & recovery"
c(A,"Retries; T08","P1","Retry an accepted capture with identical bytes/id",LOGIN,JB,"1. Capture one bag; drop the network after publish.\n2. Restore; tap Retry.","Retry publishes identical bytes and messageId (same fingerprint). Original result returned; stock deducted once.","data/mqtt/outbox/CommandOutbox.kt")
c(A,"T08","P1","A hold blocks scanning until resolved",LOGIN,JB,"1. While unanswered, scan another tag, tap Submit, tap Mix complete.","'Station 2 hasn't answered — don't scan again'; actions blocked; scan bar 'Scanning paused'.","ResultBanner.kt:114-157")
c(A,"Retries; T09","P1","Lose the reply, expire login, recover (committed)",LOGIN,JB,"1. Capture; drop the reply.\n2. Expire the session; sign in again as the same operator on the same scanner.\n3. Open Home.","Recover sent in the original family with a new id, originalMessageId and originalRequestFingerprint. 'Station 2 had already done this. It was not repeated.' No second deduction.","CommandRecoveryUseCase.kt")
c(A,"Retries; T09","P1","Recover → not_executed offers a replacement",LOGIN,JB,"1. Arrange that the original never executed (simulator).\n2. Recover.","'…this never happened…' with Open PREP; after a fresh read the 'Record bag / Don't record' offer appears; accepting sends a NEW id.","ReplacementOffer.kt")
c(A,"Retries; T09","P1","Recover → rejected keeps the original rejection",LOGIN,JB,"1. Original rejected; recover.","'Station 2 rejected it — msg. Re-read before trying again.'","RecoveryNoticeText.kt")
c(A,"T09","P1","Another operator on the same scanner cannot resolve","Operator A pending; sign in as B","-","1. Sign in as B on the same scanner.","Card: '…sent by operator A. They must sign in on this scanner…'. B never sends A's recover and does not see A's notices.","CommandRecoveryUseCase.kt")
c(A,"Errors","P1","receipt_owner_mismatch / receipt_recovery_unavailable → needs a manager","Simulator",JB,"1. Return those codes.","'A manager must reconcile this at the station before you scan again.'; no Retry; replacement physical actions stopped.","CommandOutcome.kt")
c(A,"Errors","P1","receipt_sealed → replacement offer; original never runs","Simulator",JB,"1. Answer a Retry with receipt_sealed.","'Station 2 never ran this, and it can no longer run.' plus a replacement offer.","CommandOutcome.kt")
c(A,"Errors","P2","outcome_unconfirmed → retry identical","Simulator",JB,"1. Reply outcome_unconfirmed.","'Station 2 has not confirmed this yet. Don't repeat it — retry.'","CommandOutcome.kt")
c(A,"Errors","P2","message_id_conflict → recover now","Simulator",JB,"1. Reply message_id_conflict.","'Station 2 couldn't match this command. Checking what happened…'; original payload hash not overwritten.","CommandOutcome.kt")
c(A,"Retries","P2","A pending command survives an app kill",LOGIN,JB,"1. Leave a capture unanswered; force-stop.\n2. Relaunch.","Unresolved card on Home; retry/recover use the original bytes.","CommandOutbox.kt")
c(A,"-","P2","Clear a needs-manager command","Needs-manager entry","-","1. Clear → confirm.\n2. Try Clear on one Station 2 can still settle.","Entry removed and written to the cleared-command log; otherwise 'Not cleared: Station 2 can still settle this one.'","PendingCommandCoordinator.kt:78-107")
c(A,"Retries","P2","Check again for an earlier-session command","Unresolved from an earlier session","-","1. Tap Check again.","Recovery re-attempted; notice updated.","RecoveryNoticeText.kt:43")
c(A,"Retries; T09","P2","A generic read does not resolve an unanswered capture","Pending capture",JB,"1. Re-read the PREP while the capture is unanswered.","Hold stays until retry/recover gives a definite answer.","-")

# ---------------- Catalogue / refusal cases ----------------
A = "CAT Catalogue refusal cases"
c(A,"T14","P1","Unavailable: Each-unit ingredient",LOGIN,"JC 510023208","1. Look up.","The fresh response decides; if refused, readable reason (Each vs kg). No conversion of Each to kg.","-")
c(A,"T14; Errors","P1","Unavailable: recipe ingredient missing from the order",LOGIN,"JC 510023106","1. Look up.","The fresh response decides; if refused, reason names 1600000217 (recipe_order_component_mismatch); targets not altered.","-")
c(A,"T14","P1","Available but no whole mix count",LOGIN,"JC 510022879","1. Look up; try Prepare.","No positive supported mix count — Prepare unavailable or refused with a reason.","-")
c(A,"T14","P2","Unsupported SAP unit 187",LOGIN,"JC 510022849","1. Look up.","Refused or line excluded; no kg conversion.","-")
c(A,"T14","P2","Ambiguous full-order mapping",LOGIN,JE,"1. Look up; prepare 1 mix.","General qualification stands on its own; the ambiguous 1600000003 line is not collectable on General.","-")
c(A,"Errors","P2","saved_job_changed / collection_context_changed","Change the job on Station 2 mid-flow",JB,"1. Act after the job changed.","Refusal; the app re-reads the exact scope before a new intent.","-")
c(A,"Errors","P1","rev2_rejected shows reason and current scope","Any definite rejection (e.g. capture over remainder via crafted request)",JB,"1. Trigger a rev2_rejected reply.\n2. Correct the input and retry.","Reason shown with the current saved scope; command settled (no Retry); the corrected attempt uses a NEW messageId.","CommandOutcome.kt")
c(A,"Errors","P2","sap_unavailable","SAP readiness down","-","1. Look up a new JC.","Refusal with the sap_unavailable message; nothing created.","-")
c(A,"Job sweep","P2","Lookup sweep of all 189 job cards",LOGIN,"Job Coverage sheet","1. Look up each JC; record the outcome in the Job Coverage sheet.","Each job loads with the expected materials or gives a readable refusal matching its class.","-")

# ---------------- Scan routing ----------------
A = "SCN Scanner input"
c(A,"-","P1","RFID and barcode on each screen","-","-","1. Scan an RFID tag and a barcode on Login, Home, Settings, Lookup, Detail, Prepare, Prep and Rajoo.","Only the visible screen reacts. Home, Settings, Detail and Prepare ignore scans; Login takes RFID only.","data/rfid/DataWedgeReceiver.kt")
c(A,"-","P2","DataWedge and Chainway intents both work","Each device type in use","-","1. Scan on each scanner model used on site.","Scans received from both intent actions.","DataWedgeReceiver.kt")
c(A,"-","P3","A scan counts as activity","Auto sign-out 1 min",LOGIN,"1. Scan every 40 s without touching the screen.","Not signed out.","InactivityMonitor.kt")
