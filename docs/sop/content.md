---
app_name: Station 2
file_stem: Station_2
station: Station 2 mixing
mark: "2"
accent: "#1D6B45"
accent_tint: "#A2CBB4"
icon: images/icon.png
version: 1.5.0
date: 2026-11-06
device: Chainway C72
standfirst: >-
  How to look up a job card, prepare mixes, collect every ingredient from its
  pallet, run the mix on a mixer, collect a Rajoo job in sessions, and what to
  do when Station 2 refuses a scan or does not answer.
description: Standard operating procedure for the PPNAM Station 2 mixing handheld app 1.5.0 on the Chainway C72.
crop: {top: 72, bottom: 126}
revisions:
  - version: 1.5.0
    date: 2026-11-06
    change: "Suite release: visual refresh, consistency pass, scan sounds. First Station 2 handheld SOP."
---

<!-- Screenshots: v1.5.0 (8) clean debug build of master on the c72_clone2 emulator (airplane
     mode, adb reverse), against tools/backend-sim on a private Mosquitto (port 1886). Job cards,
     pallets, machines and the operator "Operator One" are simulator demo data. -->

# Purpose and scope {#purpose}

The Station 2 app runs on the Chainway C72 handheld in the mixing area. Operators use it to **prepare mixes for a job card**, **collect each ingredient from its pallet** by scanning the pallet tag, and **run each mix on a mixer** (and, on routes with an extruder, its production). A separate **Rajoo** mode collects a whole job card on the Rajoo machine in one or more sessions. Every scan is checked by the Station 2 desktop, which keeps the job, the stock and the history.

## What it does

- Looks up a job card (Production Order) and shows its mixes, progress and materials.
- Creates a preparation of one or more mixes, with your Include or Exclude choice for listed ingredients.
- Tells you which pallet to take from and how much, records each bag against the preparation, and lets you add or change an ingredient with a reason.
- Starts and finishes each mix (and production) when you scan the machine's code.
- Runs Rajoo sessions that add to the job card's full targets, and closes the job card when all material is collected.
- Holds you back, and offers **Retry**, when Station 2 has not answered a command, so nothing is recorded twice.

## What it does not do

- It does not weigh anything. The amounts come from the bag size and what you type.
- It does not move stock in SAP or print anything. The Station 2 desktop does that.
- It cannot undo a recorded bag. Corrections and exception reviews are made on the Station 2 desktop by a manager.

> [!NOTE] Who this is for
> Mixing operators at Station 2. Sections marked **Supervisor** are for the shift supervisor, who holds the settings PIN.

# Before you start {#before}

- [ ] The handheld is charged. Put it back on its charger at the end of the shift.
- [ ] You are on the plant Wi-Fi, in coverage at the pallets and the mixers.
- [ ] You know your Station 2 username and password, or you have your RFID badge.
- [ ] The Station 2 desktop is running. The status pill shows **Connected** (section 3).
- [ ] You have the job card, and the pallets you collect from have their RFID tags.

::: step Opening the app
shot: 01_login_empty.png
caption: The Log In screen.
alt: Station 2 Log In screen with Username and Password fields, a green Log In button and "or scan your badge"
The handheld starts in the **PPNAM Station Launcher**. Tap the green **Station 2** tile.

The app opens on **Log In**, or straight on the home screen if you are still signed in. Wait for the status pill in the top-right corner to settle on **Connected** before you start.
:::

# The status pill and header {#status}

The coloured pill in the top-right corner is on every screen. It reports two links: the **network link** (the broker that carries messages) and **Station 2 itself** (the desktop). It waits 1.5 seconds before it changes, so a short blip does not make it flicker.

::: pills
go | Connected | The network is up and the Station 2 desktop is running. Everything works. | Carry on.
warn | Reconnecting | The handheld lost the network link and is getting it back. It retries by itself, from every second up to every 30 seconds. | Wait. If it does not clear, move into Wi-Fi coverage.
warn | Station 2 offline | The network is fine, but the Station 2 desktop is not running or not reachable. Scans and look-ups get no answer. | Do not start new work. Wait for **Connected** and tell your supervisor if it lasts.
warn | Clock out of sync | The handheld's clock differs from Station 2 by more than 30 seconds. Station 2 may refuse every request as expired. | Tell your supervisor; the handheld's date and time need correcting.
stop | Offline | The handheld could not reach the broker at all, for example a new handheld with no broker set up, or outside coverage at start-up. It tries again every 5 seconds. | Move into coverage. If it stays red, ask your supervisor to check Settings.
:::

::: shots
03_home_reconnecting.png | Reconnecting: the link dropped and is coming back. | Home screen with an amber Reconnecting pill
04_home_station_offline.png | Station 2 offline: the network is fine, the desktop is not. | Home screen with an amber Station 2 offline pill
05_login_offline.png | Offline: no broker link since the app started. | Log In screen with a red Offline pill
:::

::: step The header
shot: 02_home.png
caption: Home: your name and role, Settings, the pill, and the two jobs.
alt: Station 2 home screen with "Operator One · Operator", a Connected pill, and Job Cards and Rajoo tiles
- **Your name and role** sit under the logo. Tap them to log out (section 13).
- The **gear** opens Settings.
- On the other screens the header has a **back arrow**, your name (tap it to log out) and the pill, with the screen name on the line below.

The home screen has two tiles: **Job Cards** (*Look up a job card*) and **Rajoo** (*Collect a full job card in sessions on a Rajoo machine*). Scans do nothing on the home screen.
:::

# Signing in {#signin}

Everything you record is saved against your name, so the app does nothing until you sign in. You can sign in with your username and password, or with your badge.

::: step By username and password
shot: 06_login_dropdown.png
caption: Tap the arrow to pick your username from the station's list.
alt: Username dropdown open, listing "manager1 — Manager One" and "operator1 — Operator One"
1. Tap **Username**. Pick your name from the list (each row reads *username — Your Name*) or type your username. The arrow appears once Station 2 has sent its list; a username that is not listed still works.
2. Tap **Password** and type your password. The eye icon shows what you typed.
3. Tap **Log In**.
:::

::: step By badge
shot: 07_login_logging_in.png
caption: The spinner shows while Station 2 checks you. The fields are locked.
alt: Log In screen with operator1, a masked password and a spinner on the Log In button
While the Log In screen is showing, **scan your RFID badge**. You do not need to tap anything first.

A second read while a sign-in is already running is ignored. Signing in makes no scan sound.
:::

::: step If sign-in fails
shot: 08_login_error.png
caption: The reason appears in red above the fields.
alt: Log In screen with the red message "Incorrect username or password."
The reason appears in red at the top of the card. The common ones:

- **Incorrect username or password.** Retype them carefully.
- **This badge is not registered or is no longer active.** Ask your supervisor to check your badge.
- **Station 2 did not respond. Check the station and retry.** No answer within 10 seconds. Check the pill, then try again.
- **Please fill in all fields.** Both boxes are needed.

Every sign-in message is listed in section 11.
:::

::: shots
09_login_badge_refused.png | An unknown badge. | Log In screen with "This badge is not registered or is no longer active."
10_login_timeout.png | No answer from Station 2. | Log In screen with "Station 2 did not respond. Check the station and retry."
11_login_signed_out.png | Signed out for inactivity (shown with a 1-minute setting). | Log In screen with "Signed out after 1 minute of inactivity."
:::

## Automatic sign-out

If nobody touches the handheld or scans for **15 minutes**, the app signs you out and shows *Signed out after 15 minutes of inactivity.* on the Log In screen. Your supervisor can change the time. Time with the app in the background counts too.

If Station 2 ends your session, the app returns to Log In. Sign in again; anything that was waiting for an answer is checked with Station 2 (section 9).

# Job Cards and preparing mixes {#workflow-jobcards}

A **preparation** is a batch of one or more mixes of a job card. You create it here, then collect its ingredients (section 6) and run it on a mixer (section 7).

::: step Look up the job card
label: Step 1
shot: 12_job_cards.png
caption: Job Cards, with the jobs Station 2 already has.
alt: Job Cards screen with the field "Production order number or PREP id", a Look up button, and job 510019068 under "Jobs on Station 2"
On the home screen tap **Job Cards**. Then do one of these:

- **Scan the job card's barcode.** It is looked up straight away.
- Type the production order number (digits only) and tap **Look up**.
- Tap a job under **Jobs on Station 2**.
- Type or scan a **PREP_** id to open that preparation directly.

Each job shows *Required · Active · Available to prepare · Finished* mixes.
:::

::: step Check the job
label: Step 2
shot: 13_job_detail.png
caption: The job: product, mix counts and Prepare mixes.
alt: Job 510019068 detail with Required 8, Active 2, Available to prepare 6, Finished 0, Output per mix 50 each, and a green Prepare mixes button
The job screen shows the product, the mix counts, the output per mix and when it was loaded. Scroll down for the **Materials** (*per mix · for the job*) and the job's **Active preparations**.

To carry on with an existing preparation, tap it under **Active preparations**. To start a new one, tap **Prepare mixes**.
:::

::: shots
14_job_detail_bottom.png | Materials and the active preparation. | Job detail scrolled to its materials and "Active preparations: PREP_demo0001, 2 mixes · Collecting"
15_lookup_refused.png | Station 2 refused the job and says why. | Job Cards with the message "Only Standard Planned or Released jobs in header warehouse FAC with positive outstanding output can be loaded."
:::

::: step Choose the mixes
label: Step 3
shot: 17_prepare_filled.png
caption: One mix, with the listed ingredient excluded.
alt: Prepare 510019068 screen with PREP_demo0001 and Open, Mixes to prepare 1, ingredient 1500000331 with Exclude selected, an optional Reason and a Prepare 1 mix button
1. If the job already has an active preparation, it is listed with **Open ›**. Open it instead if you are continuing that work.
2. Set **Mixes to prepare** with **−** and **+**, or type the number (1 up to the mixes still available).
3. For each listed ingredient, tap **Include** or **Exclude**. You must choose for every one.
4. Add a **Reason** if you want (optional, up to 1000 characters).
5. Tap **Prepare N mixes**.
:::

::: step Confirm
label: Step 4
shot: 18_prepare_confirm.png
caption: The confirmation names the job and anything excluded.
alt: Dialog "Prepare 1 mix?" with "Job 510019068. Excluded: 1500000331." and Cancel and Prepare buttons
A dialog asks *Prepare 1 mix?* and lists anything excluded. Tap **Prepare**. The new preparation opens on its Collect screen.

If Station 2 does not answer, the screen says *Station 2 has not confirmed this prepare. Don't prepare again — Retry.* Tap **Retry**; it sends the same request, so it cannot create a second preparation.
:::

# Collecting ingredients {#workflow-collect}

Collect every ingredient of the preparation from its pallet. You scan the **pallet tag**, the app tells you how much to take, and you **Submit** what you took.

::: step The ingredient list
label: Step 1
shot: 20_collect_home.png
caption: What is still needed, ingredient by ingredient.
alt: Collect screen for 510019068 with 1 mix, "0 of 6 ingredients collected", ingredient rows with kg left, and "Scan a pallet tag" at the bottom
The screen lists each ingredient with the **kg still needed**. An amber edge means not yet collected; green with a tick means done. The bar shows *N of M ingredients collected*.

At the bottom is the scan box: **Scan a pallet tag**. Scan the RFID tag of the pallet you are taking from. If a tag will not read, tap the keyboard icon, type the code and tap the tick.
:::

::: step Take the amount shown
label: Step 2
shot: 21_capture_sheet.png
caption: TAKE 2 bags + ≈ ¾, and the exact kg it records.
alt: Collect sheet for HD WHITE, pallet tag, bag size 25 kg, still needed 69.631 kg, TAKE 2 bags + ≈ ¾ with drawn bags, Amount 69.631 and Submit 69.631 kg (3 bags)
When Station 2 recognises the pallet you hear the success chirp and the sheet opens:

- The pallet's ingredient, **Bag size**, **Still needed** and what is **On pallet**.
- **TAKE** in large letters: whole bags plus a fraction (for example *2 bags + ≈ ¾*), and *records 25 + 25 + 19.631 kg*.
- **Amount (kg)**, filled in with what is still needed. *Took a different amount? Edit the kg.*

Take the bags, then tap **Submit**. The button names the amount and the number of bags.
:::

::: step The bag is recorded
label: Step 3
shot: 22_capture_result.png
caption: Recorded, and the ingredient is complete.
alt: Green card "Recorded 69.631 kg HD WHITE" with "HD WHITE is now fully collected", and the HD WHITE row ticked
While it sends you see *Recording HD WHITE: 1 of 3…* and *Don't scan again until Station 2 answers.* Each bag is sent on its own.

A green card confirms it, for example *Recorded 69.631 kg HD WHITE*, with the success chirp. The row turns green when the ingredient is complete. The scan box now reads **Scan next pallet**. Repeat for every ingredient.
:::

## When a pallet cannot be used

A refusal plays the fail buzz and shows a red or amber card. Tap **Cancel** and fetch the right pallet.

::: shots
24_pallet_blocked.png | A blocked pallet. | Red card "Can't collect from this pallet", "Pallet is blocked." and Cancel
25_pallet_excluded.png | An ingredient excluded from this mix. | Red card "Excluded from this mix — can't collect" with the material name and code
26_capture_rejected.png | Station 2 refused the amount. | Red card "Not recorded", "Capture one bag or its actual fraction…", "Logged … · operator OP-0…"
:::

- **Can't collect from this pallet** gives Station 2's reason (blocked, at another station, no stock). It also shows when the ingredient *is already fully collected*.
- **Not recorded** means Station 2 refused the bag. The reason is shown in full, and the refusal is logged for a manager. Read the reason, correct the amount and scan again.

::: step Pallet not recognised
shot: 23_identify_fallback.png
caption: No answer for this pallet: pick the ingredient yourself.
alt: Amber card "Station 2 didn't answer in time — pick the ingredient" with the pallet code and a list of ingredients with their kg left
If Station 2 does not know the pallet, or did not answer in time, an amber card says *Station 2 couldn't identify this pallet — pick the ingredient* (or *didn't answer in time*). Tap the ingredient that is really on the pallet, check the **Amount** and tap **Submit**, or tap **Cancel**.
:::

## Adding an ingredient that is not in the mix

::: step Not in this mix
shot: 27_add_ingredient.png
caption: Adding an ingredient needs an amount and a reason.
alt: Amber card "Not in this mix" for BLUE PIGMENT, bag size 20 kg, Adding (kg) 20, Reason "Colour correction for this mix", and an "Add 20 kg to mix" button
If the pallet's material is not in the mix, an amber card says **Not in this mix**. Only add it when you have been told to.

1. If the screen offers it, choose **Add as an extra ingredient** or **Instead of** an ingredient not collected yet.
2. Enter the amount (**Adding (kg)**) and a **Reason (required)**.
3. Tap **Add N kg to mix** (or **Use N kg instead of …**). *Logged as an exception for manager review.*

Then record the bag as usual: the sheet opens for the new ingredient. **Cancel — don't add** backs out.
:::

::: step Changing an ingredient's total
shot: 29_ingredient_decision.png
caption: Less / more, or Omit, always with a reason.
alt: Dialog "Change MASTERBATCH WHITE…" with current total 8.704 kg, Less / more selected, new total 10, Proposed total 10 kg, More +1.296 kg, a reason and Change total
Tap an ingredient row (*Tap an ingredient to change its preparation total or omit it*):

- **Less / more**: type the **New total for this preparation**. The dialog shows the difference and what is still needed after.
- **Omit**: *Sets the target to 0 and excludes it.* Not possible once some of it is collected.

Enter a **Reason (required)** and tap **Change total** or **Omit ingredient**. This is logged as an exception for manager review; no stock moves until you record a bag.
:::

::: shots
28_added_recorded.png | The added ingredient, recorded. | Green card "Recorded 20 kg BLUE PIGMENT (not on any BOM)"
31_mix_complete_confirm.png | Confirm the mix is complete. | Dialog "Mix complete?" with Cancel and Confirm
:::

::: step Mix complete
label: Step 4
shot: 30_all_collected.png
caption: Every ingredient collected.
alt: Collect screen with "7 of 7 ingredients collected" and a green "Mix complete — ready for mixer" button
When every ingredient is collected, **Mix complete — ready for mixer** appears (*Asks once to confirm*). Tap it, then **Confirm** on *Mix complete?*. The preparation moves to the mixer stage.
:::

> [!WARNING] One pallet at a time
> While a pallet's sheet is open, scanning another pallet is refused with *Finish or cancel this pallet first*. Submit or Cancel first.

# Mixing and production {#workflow-mixer}

After **Mix complete**, the preparation shows its stages: **Collect · Mix · Produce · Done** (routes where the mixer feeds a fixed extruder show *Collect · Mix · Done*). Each start and finish is a scan of the machine's code, then a tap to confirm.

::: step Scan a mixer
label: Step 1
shot: 32_mixer_stage.png
caption: Which mixers are free, in use or disabled.
alt: Mixer stage with Collect, Mix, Produce, Done; Mixed 0/1, Produced 0/1; a list of mixers marked free or disabled; and "Scan a mixer / to start mix 1 of 1"
The list shows each mixer as **free**, **in use** (by another preparation) or **disabled**. Scan the code on the mixer you are using.
:::

::: step Start the mix
label: Step 2
shot: 33_start_mix_confirm.png
caption: Check the machine, then tap Start.
alt: Start mix card for Main mixer 1 with area Main, the extruders it feeds, "Mix 1 of 1", "This mixer stays assigned for this mix." and "Start mix 1 on Main mixer 1"
A card shows the machine you scanned, its area and what it feeds. Check it is the right one, then tap **Start mix 1 on …**. Tap **Cancel** if it is wrong.

The first mix assigns the mixer: *This mixer stays assigned for all N mixes.* You hear the success chirp when Station 2 accepts.
:::

::: step While it mixes
label: Step 3
shot: 34_mixing.png
caption: Mixing, with the time so far.
alt: Green card "Mix 1 started on Main mixer 1", "Mixing 1 of 1 · Main mixer 1 · 0 min", and "Scan Main mixer 1 to finish / only Main mixer 1 is accepted"
The screen shows *Mixing 1 of 1*, the mixer and the minutes so far. When the mix is done, scan the **same** mixer and tap **Finish mix N**.

Scanning another mixer is refused: *Only Main mixer 1 can finish this mix*.
:::

::: shots
35_wrong_mixer.png | Another mixer cannot finish this mix. | Red card "Only Main mixer 1 can finish this mix"
36_finish_confirm.png | Finish the mix on its own mixer. | Card "Finish mix 1 on Main mixer 1?" with a Finish mix 1 button
37_ready_production.png | Mixed: now scan an extruder. | "Mix 1 finished", "All 1 mix mixed", free extruders and "Scan an extruder"
:::

::: step Production on an extruder
label: Step 4
shot: 38_producing.png
caption: Producing on the extruder.
alt: Green card "Production 1 started on Main extruder 1" and "Producing 1 of 1 · Main extruder 1"
On routes with production, after the mix the screen says **Scan an extruder** and lists the free ones. Scan the extruder, tap **Start production N on …**, and when it is done scan the same extruder and tap **Finish production N**.

With more than one mix, repeat: scan the assigned mixer for the next mix (*Scan Main mixer 1 / to start mix 2 of 3*).
:::

::: step Preparation complete
label: Step 5
shot: 39_prep_complete.png
caption: All mixes mixed and produced.
alt: Green card "Preparation complete", "1 mix · Main mixer 1 → Main extruder 1", "1 of 8 finished · 5 available to prepare", and a Back to job button
A green card reads **Preparation complete** with the route and the job's progress. Tap **Back to job** to prepare the next mixes.
:::

> [!NOTE] A machine code too early
> Scanning a mixer before every ingredient is collected is refused: *That's the Main mixer 1 code — collect every ingredient and tap Mix complete first*.

# Rajoo sessions {#workflow-rajoo}

On the Rajoo machine a job card is collected **as a whole** (its full Production Order quantities), in one or more **sessions**. Every session adds to the same totals. The Rajoo machine's code starts and ends each session and closes the job card.

::: step Look up the job card
label: Step 1
shot: 40_rajoo_lookup.png
caption: Scan the job card, then tap Look up job card.
alt: Rajoo screen with "Machine: Rajoo", "Look up the job card", the Job card field 510019070 and a Look up job card button
On the home screen tap **Rajoo**. If Station 2 has more than one Rajoo machine, scan the machine's code first (*Which Rajoo machine?*).

Scan the job card (it fills **Job card**) or type it, then tap **Look up job card**. Open Rajoo job cards are listed underneath: tap one to resume it.
:::

::: step Start a session
label: Step 2
shot: 42_rajoo_start.png
caption: The first session asks Include or Exclude.
alt: Dialog "Start a session on Rajoo?" with "First session: choose Include or Exclude for each listed ingredient…", ingredient 1500000331 with Exclude selected, Cancel and Start session
The job screen says **Scan the Rajoo machine code** / *to start a session*. Scan it and a dialog asks *Start a session on Rajoo?*.

- On the **first** session choose **Include** or **Exclude** for each listed ingredient. *The choices are kept for every later session.*
- Tap **Start session**. The status changes to **Session running**.
:::

::: step Scan a pallet for each bag
label: Step 3
shot: 44_rajoo_capture.png
caption: One bag at a time, against the full job target.
alt: Collect sheet for HD WHITE with bag size 25 kg, still needed 100 kg, TAKE 1 bag, Amount 25 and Submit 25 kg
Scan the pallet tag for each bag, check the amount and tap **Submit**. The sheet, refusals, *Not in this mix* and ingredient changes work as in section 6, except that totals are for the **whole job** (*Tap an ingredient to change its full-job total or omit it*).
:::

::: shots
41_rajoo_job.png | Before a session: no session running. | Rajoo job 510019070 with "No session running", three ingredients and "Scan the Rajoo machine code"
43_rajoo_session.png | Session running. | "Session running", "Rajoo session started. Scan the source pallet for every bag."
45_rajoo_recorded.png | A bag recorded. | Green card "Recorded 25 kg HD WHITE"
:::

::: step End the session
label: Step 4
shot: 46_rajoo_end_confirm.png
caption: The machine code ends the session.
alt: Dialog "End the session on Rajoo?" with "Everything captured stays counted. The job card stays open for the next session." and End session
At the end of a run, scan the Rajoo machine code again. *End the session on Rajoo?* Tap **End session**. *Everything captured stays counted. The job card stays open for the next session.*
:::

::: step Close the job card
label: Step 5
shot: 49_rajoo_closed.png
caption: Closed. The history is kept.
alt: Rajoo job with status Closed and a green card "Job card closed / Session, bag and pallet-source history is retained"
When every ingredient is collected and no session is running, the scan box reads **Job card work complete** / *Rajoo machine code closes the job card*. Scan it, then tap **Close job card** on *Close job card 510019070?*.

A green card reads **Job card closed**. The card above it shows Station 2's own wording.
:::

::: shots
47_rajoo_session_ended.png | Session ended; progress kept. | "No session running" with "Rajoo session saved. Cumulative JC material progress retained."
48_rajoo_close_confirm.png | Closing ends Rajoo capture. | Dialog "Close job card 510019070?" with Close job card
:::

# When Station 2 does not answer {#workflow-unanswered}

Every capture, prepare, ingredient change and machine start or finish is saved on the handheld **before** it is sent, and kept until Station 2 answers. If no answer comes, the app holds you back instead of letting you send it twice.

::: step Hasn't answered: Retry
shot: 51_unanswered_hold.png
caption: Don't scan again. Tap Retry.
alt: Amber card "Station 2 hasn't answered — don't scan again", "The last capture may or may not have been recorded. Retry sends the same message again." and a large Retry button; the scan box reads Scanning paused
After about 10 seconds without an answer an amber card reads **Station 2 hasn't answered — don't scan again**. Scanning is paused (the scan box says **Scanning paused**).

1. Check the pill. Wait for **Connected**.
2. Tap **Retry**. It sends **exactly the same message**, so Station 2 records it at most once.
3. Read the answer. Then scan the pallet again for any bags that were not recorded yet.
:::

::: shots
50_capture_sending.png | Sending: one bag of two. | "Recording PALLET WRAP (PP): 1 of 2…" and "Don't scan again until Station 2 answers."
52_after_retry.png | After Retry: the bag recorded once. | Green card "Recorded 25 kg FILLER…" with the note "Station 2 has not confirmed this yet. Don't repeat it — retry."
53_home_unanswered.png | Left unanswered: Home shows it. | Home with an amber card "1 unanswered command" and "capture PREP_demo0001: waiting for Station 2 — reopen the job to retry it"
:::

## The unanswered command card on Home

If you leave the screen, Home shows **N unanswered command(s)** with one line each, for example *capture PREP_demo0001: waiting for Station 2 — reopen the job to retry it*. Reopen the job: the app retries it or checks with Station 2 what happened.

After you sign in again, the app asks Station 2 about commands from your earlier session and tells you the result:

- *…: Station 2 had already done this. It was not repeated.* Nothing to do; tap **OK**.
- *…: this never happened. Re-read the job, then do it again only if it is still needed.* Tap **Open …** to go to it.
- *…: Station 2 rejected it — reason. Re-read before trying again.*
- *…: still unresolved — reason.* It is checked again when the handheld reconnects, or tap **Check again**.

If a bag or confirmation was never received, the job screen asks *Not received by Station 2* — *It has been cancelled and can never happen later. Record this bag now?* Tap **Record bag** (or **Confirm collection**) only if you really did take it; otherwise **Don't record**.

> [!SUPERVISOR] Needs a manager
> *A manager must reconcile this at the station* (or *…: needs a manager*) means the handheld cannot settle the command. A manager reconciles it on the Station 2 desktop. Only then tap **Clear** on Home and confirm; *The scanner records that you cleared it, and when.*

# Scan sounds and vibration {#sounds}

Every PPNAM handheld uses the same two sounds. The Chainway scanner beeps by itself when it reads anything; the app's sound comes **after** that, when Station 2 has answered.

- **Success chirp** (a bright rising two-note chirp) with one short 40 ms vibration: the scan was **accepted**.
- **Fail buzz** (a low falling double buzz) with two 200 ms vibrations: the scan **failed**. Read the screen.

The sounds play at the **media volume**. If you cannot hear them, turn the media volume up; the vibration still tells you.

Table: What you hear in the Station 2 app
| When | Outcome | Sound |
|---|---|---|
| Job card scan on Job Cards | Station 2 loads the job, or a PREP id opens | {ok} |
| Job card scan on Job Cards | Refused, not digits, no answer, or a user badge | {fail} |
| Pallet tag on Collect | Recognised, and its ingredient is still needed in this mix | {ok} |
| Pallet tag on Collect | Blocked, not in this mix, excluded, already collected, not recognised or no answer | {fail} |
| Submit (or Retry) of a bag | Station 2 recorded it | {ok} |
| Submit (or Retry) of a bag | Not recorded | {fail} |
| Submit of a bag | No answer yet (the hold), or not sent while offline | {none} |
| Mixer or extruder code | Opens the Start or Finish card | {none} |
| Start / Finish tap after a machine scan | Station 2 accepted / refused | {ok} / {fail} |
| Mixer or extruder code | Wrong machine, not a machine, or not now | {fail} |
| Rajoo: Look up job card | Loaded / refused | {ok} / {fail} |
| Rajoo machine code | Opens Start, End or Close; its answer sounds as above | {none} |
| Any scan refused on the handheld | A badge, a pallet still open, sending, a hold, a machine code too early | {fail} |
| Mix complete, ingredient changes, Prepare, offers, Retry of a prepare | Buttons | {none} |
| Log In, badge sign-in, Settings | Signing in, settings | {none} |

## The repeat-read rule

One pull of the trigger can read the same tag several times. A repeat of the code **already being handled** (the pallet or machine on screen, or the job card being looked up), or of a code **refused** within the last 1.5 seconds, makes no sound. A different code while one is sending plays the fail buzz once. When Station 2 answers, you hear one sound for that scan.

# Messages and what to do {#messages}

Every message the app shows, word for word. Where Station 2 refuses something, the app shows **Station 2's own reason** in full: read it and do what it says, or tell your supervisor.

Table: Signing in
| Message | What it means | What to do |
|---|---|---|
| Please fill in all fields. | The username or password box is empty. | Fill in both. |
| Incorrect username or password. | Station 2 did not accept the password. | Retype carefully. Ask your supervisor if you are locked out. |
| This badge is not registered or is no longer active. | Station 2 does not know the badge. | Use your username and password; ask your supervisor. |
| Card sign-in was interrupted. Tap the card again. | A badge sign-in was cut off. | Scan the badge again. |
| Station 2 did not respond. Check the station and retry. | No answer within 10 seconds. | Check the pill and the desktop, then retry. |
| Not connected to Station 2 | The handheld has no network link. | Check the pill and Wi-Fi, then retry. |
| Station 2 sent an unreadable response | The answer could not be read. | Retry. Report it if it repeats. |
| Station 2 does not accept this kind of sign-in. Update the app. / This app build sent credentials in a form Station 2 no longer accepts. Update the app. / Station 2 rejected the sign-in request as malformed. Update the app if this keeps happening. | The app and the desktop do not match. | Tell your supervisor. |
| Station 2 sent an incomplete / invalid / unusable authentication challenge; Station 2's authentication challenge did not match this device's request | The station's answer could not be used. | Retry. Report it if it repeats. |
| Station 2 failed authentication verification — this response is not trusted | The answer could not be verified. | Do not keep retrying. Tell your supervisor. |
| Station 2 accepted the login but issued no session / Station 2 closed this session immediately | The desktop did not open a session. | Retry, then tell your supervisor. |
| Signed out after *N* minutes of inactivity. | The automatic sign-out ran. | Sign in again. |
| App update required: This version of Station 2 (v1.5.0) is too old for the station. Ask a supervisor to install the latest version, then log in again. | Station 2 needs a newer app. | Tap **Close app** and tell your supervisor. |

Table: Job Cards and Prepare
| Message | What it means | What to do |
|---|---|---|
| Scan or enter the production order number (digits only) | The code is not a job card number. | Scan the job card barcode. |
| That is a user badge, not a job card. Use the login screen to sign in. | A badge was scanned. | Scan the job card. |
| Station 2 has no General job *N* / Station 2 loaded *N* but returned no job | No such job here. | Check the number. |
| Station 2 couldn't confirm that — try again | The outcome is unknown. | Try again. |
| Your session has ended — sign in again | Station 2 ended your session. | Sign in again. |
| This app needs updating before it can talk to Station 2 | The app is too old. | Tell your supervisor. |
| Station 2 rejected the request | Refused without a reason. | Tell your supervisor. |
| N must be a whole number from 1 to *N* / Nothing left to prepare for this job | Wrong mix count, or no mixes left. | Correct the number. |
| Choose Include or Exclude for *code* | A listed ingredient has no choice. | Tap Include or Exclude. |
| Excluding every material leaves nothing to prepare | Everything is excluded. | Include at least one. |
| Reload the job — Station 2 sent no ingredient list revision | The job is out of date. | Go back and open the job again. |
| Station 2 has not confirmed this prepare. Don't prepare again — Retry. | No answer to Prepare. | Tap **Retry**. |
| An earlier prepare for this job is being recovered — check Home / A prepare for this job is waiting for Station 2 | An earlier Prepare is unresolved. | Check Home; tap **Open** to retry it. |
| A manager must reconcile this at the station before you prepare again. | The prepare needs a manager. | Tell your supervisor. |
| Station 2 already settled that prepare. Check the job's preparations. / Prepared, but Station 2 did not say which preparation. Open it from the job. | The prepare was done. | Open it from the job. |

Table: Collect
| Message | What it means | What to do |
|---|---|---|
| Can't collect from this pallet: *Station 2's reason* | Blocked, elsewhere, no stock, or *… is already fully collected*. | Fetch another pallet. |
| Excluded from this mix — can't collect | The ingredient was excluded. | Fetch the right pallet. |
| Not in this mix | The material is not in the mix. | Cancel, unless told to add it. |
| Station 2 couldn't identify this pallet — pick the ingredient / Station 2 didn't answer in time — pick the ingredient | Unknown pallet, or no answer. | Pick the ingredient, or Cancel. |
| Not recorded: *Station 2's reason*, Logged *date* · operator *id* | Station 2 refused the bag and logged it. | Read the reason, correct, rescan. |
| Recorded *n* of *N* bags (*kg* kg); Not recorded: *reason* | Some bags of a multi-bag Submit were refused. | Check what is still needed; rescan. |
| Recorded *n* of *N* (*kg* kg) — then: *reason* | Stopped part-way through a multi-bag Submit. | Follow the reason. |
| Enter an amount / Enter a number, e.g. 12.5 / Enter more than 0 / At most 3 decimals | The amount is not valid. | Correct the amount. |
| More than still needed — *n* kg / Only *n* kg on this pallet | Too much. | Lower the amount. |
| That is a user badge, not a pallet | A badge was scanned. | Scan the pallet tag. |
| Finish or cancel this pallet first | A pallet sheet is open. | Submit or Cancel. |
| Wait — Station 2 is still answering (the last bag) | A command is in flight. | Wait. |
| Resolve the unanswered command first — don't scan again | A hold is showing. | Tap **Retry** (section 9). |
| This preparation hasn't loaded yet / This preparation is not collecting (*stage*) | Not ready for scans. | Wait, or go back and reopen it. |
| Added — scan the pallet again to collect it | The ingredient was added. | Scan the pallet. |
| *Name* is no longer in this mix — scan again / *Name* changed — check the preparation and try again | The preparation changed meanwhile. | Rescan. |
| Station 2 recorded this against *X*, not *Y* | The bag went on another row. | Check the list; tell a manager if wrong. |
| Reason (required) / Enter a reason / That is the current total — enter a different one / Can't go below the *n* kg already collected / *n* kg is already collected — change the total instead / At least one ingredient must stay / *Name* is excluded — it can't be changed | The ingredient change is not valid. | Correct it. |
| Ingredients can't be changed now (*stage*) | The preparation is not collecting. | No change possible here. |
| Station 2 is not waiting for confirmation (*stage*) | Mix complete is not possible now. | Refresh; collect what is missing. |
| The scanner couldn't save this, so nothing was sent. Try again. / Not connected — nothing was sent | Nothing went out. | Try again when **Connected**. |
| Something went wrong sending this: *error*. Check the preparation before trying again. | An unexpected error. | Check the list before you redo anything. |

Table: Mixing and production
| Message | What it means | What to do |
|---|---|---|
| Not a machine — *code* | The code is not a machine. | Scan the machine's code. |
| Only *machine* can finish this mix / production | Another machine was scanned. | Scan the assigned machine. |
| *Machine* is an extruder — scan a mixer / *Machine* is a mixer — scan an extruder | Wrong kind for this step. | Scan the right kind. |
| That's the *machine* code — collect every ingredient and tap Mix complete first | Too early. | Finish collecting. |
| Disabled — can't start | The machine is disabled. | Use another; tell your supervisor. |
| Saved mixer is *X* / Saved extruder is *X* | Another machine is assigned. | Use the assigned one. |
| This preparation is complete / This preparation is not ready for a machine / No active cycle to finish — refresh / No preparation is open | Nothing to start or finish. | Refresh the screen. |
| The preparation moved on — scan the machine again / The preparation changed — scan the machine again / Finish or cancel first | The card no longer applies. | Scan again. |
| Not done: *Station 2's reason* | Station 2 refused the start or finish. | Read the reason. |

Table: Rajoo
| Message | What it means | What to do |
|---|---|---|
| *code* is not the Rajoo machine code — rescan / *Name* is disabled — ask an admin to enable it | Wrong or disabled machine. | Scan the right machine; tell your supervisor. |
| No Rajoo machine is enabled and commissioned in Station 2 — ask an admin | Rajoo is not set up. | Tell your supervisor. |
| Not the Rajoo machine code — *code*: Scan Rajoo to start a session | A pallet scanned with no session. | Scan the Rajoo machine code first. |
| Start a session first — scan the Rajoo machine code / No session is running / No session is active | No session. | Start a session. |
| This job card is closed / This job card hasn't loaded yet | Not possible now. | Open another job card. |
| Excluding every ingredient leaves nothing to collect | Everything excluded. | Include at least one. |
| The job changed — scan the Rajoo machine again | The card no longer applies. | Scan again. |
| Saved — scan the pallet again to collect it | The ingredient was added. | Scan the pallet. |

Table: Unanswered commands and Home
| Message | What it means | What to do |
|---|---|---|
| Station 2 hasn't answered — don't scan again | No answer to the last command. | Tap **Retry**. |
| Station 2 has not confirmed this yet. Don't repeat it — retry. | The same, as a note. | Tap **Retry**. |
| Your session ended. Sign in again; the scanner will ask Station 2 what happened. | Unanswered when your session ended. | Sign in again. |
| Station 2 couldn't match this command. Checking what happened… | Recovery is running. | Wait. |
| A manager must reconcile this at the station (before you scan again) | Only a manager can settle it. | Tell your supervisor. |
| *command*: sent by operator *id* (on another scanner)… | Another operator's command. | They sign in here, or a manager reconciles it. |
| Station 2 never received the scan of *code* (*kg*)… Record this bag now? / …the confirmation… Confirm collection now? | It never happened. | Record or confirm only if it is still true. |
| Not possible right now — resolve the unanswered command or wait for Station 2. | Another command is pending. | Resolve that first. |
| Sign in to clear it. / Not cleared: Station 2 can still settle this one. / Not cleared: the scanner could not record it (*error*). Try again. | Clear did not happen. | Follow the message. |
| Could not check this command / prepare: *error* | The check failed. | Tap **Check again** later. |

Table: Settings
| Message | What it means | What to do |
|---|---|---|
| Incorrect PIN. *N* attempts left before lockout. | Wrong PIN. | Supervisor only. |
| Too many attempts. Try again in *N*s. | Five wrong PINs lock the box for 30 seconds. | Wait for the countdown. |
| Host required / Invalid port (1–65535) / Enter 1000–60000 ms / Enter 0–1440 | A field is missing or out of range. | Supervisor: correct the field. |
| No answer from the broker within 15 s. Check the host, port and TLS setting. | The broker did not answer. | Supervisor: check the values. |
| Could not connect to the broker. Check the host, port, username, password and TLS setting. | The connection was refused. | Supervisor: check the values. |

# Settings {#settings}

The gear opens Settings from Log In or Home. The top half is open to everyone and is what support will ask you to read out. The bottom half needs the supervisor PIN.

::: step Diagnostics: anyone
shot: 62_settings_applied.png
caption: Diagnostics, and the PIN gate below.
alt: Settings Diagnostics: MQTT Broker Connected, Station 2 Online, Version v1.5.0 (8), Device ID, the supervisor PIN box and "Connected — settings saved"
- **MQTT Broker**: the network link on its own (Connected, Reconnecting, Offline).
- **Station 2**: the desktop (Online, Offline, Unknown). Broker *Connected* with Station 2 *Offline* means the network is fine and the desktop is the problem.
- **Version**: `v1.5.0 (8)` for this release.
- **Device ID**: this handheld's identity, such as `scanner_40db7f6eef44`. Read it out when you report a fault.
- **Signed in as** and **Log out** appear at the bottom when you opened Settings while signed in.
:::

> [!SUPERVISOR] Configuration
> Type the supervisor PIN and tap **Unlock**. Five wrong attempts lock the PIN box for 30 seconds, and the count survives leaving Settings. Never tell an operator the PIN; operators should ask the supervisor.

::: step Unlocked: supervisors only
shot: 61_settings_unlocked.png
caption: Unlocked, showing the standard broker settings on a new handheld.
alt: Unlocked Settings with Host mqtt.sysone.co.za, Port 443 and the WebSocket switch on
- **Host** and **Port**: normally `mqtt.sysone.co.za` and `443`.
- **WebSocket** and **TLS**: both on for the standard setup.
- **Username** and **Password**: the broker account, not an operator account. The password box is always empty; *Blank keeps the current one*.
- **Auto sign-out (minutes)**: 15 by default, *0 = never*, up to 1440.
- **Request timeout (ms)**: 10000 by default (1000 to 60000). Leave it unless support asks.

**Test & Apply** shows *Testing connection…*, connects with the new values and saves them only if that works. *Connected — settings saved* means it worked, and the section locks again after two seconds.
:::

# Finishing up {#finishing}

::: step Handing the handheld over
shot: 70_logout_dialog.png
caption: Log out asks first.
alt: Log out? dialog reading "You'll need to log in again to continue." with Cancel and Log out
1. Make sure nothing is sending and no amber **hasn't answered** card is showing. Retry it first.
2. Tap your name in the header (or **Log out** in Settings).
3. Confirm **Log out**.

Always log out before you pass the handheld to someone else. Otherwise their work is recorded under your name, and only you can settle your unanswered commands.
:::

::: step Closing the app
shot: 71_exit_dialog.png
caption: Back from Log In or Home.
alt: Close the app? dialog reading "You'll leave PPNAM Station 2 and return to the home screen." with Stay and Close
From Log In or Home, the **Back** key asks *Close the app?*. Tap **Close** to return to the Launcher, or **Stay**.

At the end of the shift, log out and put the handheld on its charger.
:::

# Troubleshooting {#troubleshooting}

Table: Common problems
| Problem | Likely cause | What to do |
|---|---|---|
| The pill says **Station 2 offline**. | The desktop is off or its program is not running. | Do not start new work. Wait for **Connected**; tell your supervisor. |
| The pill stays **Reconnecting**. | No Wi-Fi where you are, or the broker restarted. | Move into coverage. If it lasts more than a minute while others are connected, close the app with Back and open it again from the Launcher. |
| The pill is red **Offline** on a new handheld. | No broker account is set up. | Your supervisor fills in Settings. |
| The pill says **Clock out of sync**. | The handheld's time is wrong. | Tell your supervisor. |
| An amber **hasn't answered** card will not go away. | Station 2 is not answering. | Wait for **Connected**, then tap **Retry**. Do not scan the bag again. |
| **Scanning paused** in the scan box. | A hold or a sheet is open. | Retry the hold, or Submit or Cancel the sheet. |
| A pallet is *not recognised*. | The tag is not registered, or no answer. | Pick the ingredient yourself, or fetch another pallet. |
| **Mix complete** is missing. | An ingredient is still needed. | Check the list for amber rows. |
| A mixer scan is refused. | Wrong mixer, or the mix is not ready. | Read the message; scan the assigned mixer. |
| No scan sounds. | Media volume is down. | Turn up the media volume. The vibration still works. |
| Signed out unexpectedly. | 15 minutes without a touch or scan, or Station 2 ended the session. | Sign in again; reopen your job. |

> [!NOTE] Reporting a fault
> Give your supervisor the **Device ID** and **Version** from Settings, the time, the job card and PREP id, the pallet, and the exact message on screen.
