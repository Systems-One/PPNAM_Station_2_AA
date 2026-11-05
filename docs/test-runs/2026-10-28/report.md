# Station 2 Android scanner — live test run, 28 Oct 2026

**Build:** v1.4.1 (7) on Chainway C72 `HC720DE260100322`, device id `scanner_5c64df8d86a8`
**Backend:** live Station 2 via `mqtt.sysone.co.za:443` (WSS + TLS), rev2.1. The backend was restarted several times by its developer during the day.
**Operators:** Avi (Administrator), Jono (Worker)
**Test data:** dummy stock from `Station2_Android_Scanner_Test_Guide_All_Jobs_20261027.docx`
**Method:** scanner only (no broker publishing or simulator). The app was driven over adb with RFID/barcode broadcasts, and evidence came from uiautomator dumps, screenshots and the app's `MqttWire` log.
**Matrix:** `docs/test-matrix/Station2_Android_Test_Matrix_2026-10-28.xlsx`. Results are in `Station2_Android_Test_Matrix_RUN_2026-10-28.xlsx`, next to this report.

## Result

| | Count |
|---|---|
| Pass | 115 |
| Fail | 5 |
| Blocked (needs simulator, broker access, second device or desktop) | 45 |
| N/A | 1 |
| **Total matrix cases** | **166** |

- **Job card sweep:** all 189 job cards were looked up and every outcome matches its expected class (see the *Job Coverage* sheet).
- **Dummy tag sweep:** all 63 tags were identified.
- **Load test:** every reachable mixer and every extruder ran at the same time (details below).

## Failures

| ID | Sev | Finding |
|---|---|---|
| MQ-03 | P1 | Automatic reconnect gets stuck on "Reconnecting" after a Wi-Fi drop. Reproduced 3 times, lasting 2–9+ minutes. Each time, Settings → Test & Apply connected within about 9 s. The app logs nothing about the failed connect attempts. |
| SCN-03 | P1 | Scans do not count as activity. With auto sign-out at 1 min, scanning every 40 s on Home still signed the operator out. |
| DEC-05 | P2 | The change-total dialog silently disables "Change total" when the total is unchanged, below what's already collected, or has no reason; none of the explanatory messages appear. 1,000,000,000 kg gives "Enter a number" instead of "At most 999999999 kg". |
| DEC-06 | P3 | Omit is disabled for the last remaining ingredient (correct), but no message explains why. |
| AUTH-12 | P2 | The live server returns no operator directory, so the Login username dropdown is empty. |

## Other defects found (not separate matrix rows)

- **P1 — Machine codes.** "Main mixer 6" and "Crespi Mixer" appear as free mixers, but scanning their exact names returns "Not a machine — X". For every other machine the code is the name.
- **P1 — Wrong message on a completed PREP.** Scanning a machine on a completed PREP says "That's the X code — collect every ingredient and tap Mix complete first" instead of "This preparation is complete".
- **P2 — Duplicate replies.** Each Station 2 reply arrives two or more times; once, before an app restart, it arrived 16 times on 4 threads. The app drops the extras as "unmatched".
- **P3 — Back to job.** "Back to job" on a completed PREP that was opened by its id returns to the Job Cards list, not Job Detail.
- **P3 — Stale banners.** "Finish or cancel this pallet first" and "Station 2 has not confirmed this yet. Don't repeat it — retry." stay on screen after the situation has resolved.
- **P3 — IDs instead of names.** The exceptions list and the unresolved-command card show operator IDs (e.g. "e33f…", "571a2c96…") rather than names.
- **P3 — Developer wording shown to operators.** The server text "Retry the identical request and message ID…" was displayed verbatim.
- **P3 — Mixer for mix 2.** Choosing a different mixer for mix 2 shows a warning but still offers Start; Station 2 then rejects it. The app could block this itself.
- **P3 — Layout.** The job list is a nested scroll box about two rows tall. The Prepare screen puts every active PREP above the mix-count form, so with 10 or more PREPs the form is far down the page.
- **P3 — Progress counter.** The excluded PW60 line still counts in the ingredient total ("4 of 5"), so the counter can never reach "5 of 5".
- **P3 — No partial summary.** When a multi-bag submit stops partway, only the hold is shown; there's no "Recorded 1 of 4" summary.
- **P3 — Scan length.** A scanned 18-digit value is sent as a lookup, although typing is limited to 12 digits.

## Load test (JC 510022145, dummytag_41)

- **Mixers:** 9 of 11 were mixing at the same time: DOLCI mixer 1 and 2, Main mixer 1–5, Mackie and Bubblewrap. Main mixer 6 and Crespi Mixer couldn't be used (code mismatch above). DOLCI, Mackie and Bubblewrap are fixed mixer–extruder pairs, with no separate Produce step.
- **Extruders:** 27 PREPs were active at once: **25 producing, one on each of Main extruder 1–25, and 2 mixing on the DOLCI mixers.**
- **Station 2 under load:** it accepted every machine start and finish. The only error was one "did not respond" during a backend restart, which succeeded on retry.
- **Finish:** all 27 were finished, so JC 510022145 now shows 29 of 66 finished.

## Stock and SAP

- Stock arithmetic is exact. For example, dummytag_47 went 10,000 → 9,714.354 kg, which equals the sum of every General and Rajoo capture against it. One balance is shared across jobs and modes.
- The scanner showed "Queued for SAP" for the Rajoo sessions. **Check SAP posting on the Station 2 desktop** for:
  - PREPs on 510023052, 510023059 (includes the added LD CLEAR, which may be Blocked), 510023069 and 510022145 (×27);
  - Rajoo 510023047 (2 sessions, closed).

## Observations about the live system

- **Mode lock.** The first mode used claims a job card. A General lookup or prepare locks it to General and a Rajoo lookup locks it to Rajoo; the other mode then refuses with "This JC already belongs to the other process mode." 510023047, 510023050 and 510023208 are now Rajoo cards.
- **Previously unavailable cards.** 13 of the 20 catalogue-unavailable job cards now load, because the fresh server check passes the recipe/order-mismatch cases. The other 7 are still refused with readable reasons.

## Leftovers on the live system

- PREP_…7c26d3 on 510023064: 1 mix, not collected (DEC-06).
- PREP_…25af04 on 510023052: 2 mixes, partly collected (C4LL 37.5 kg, LD 50 kg).
- PREP_…6a38b6 on 510023059: partly collected (white, carriers and 20 kg of PCR).
- Settings were restored to auto sign-out 15 minutes.

## Evidence

All evidence is in this folder:

- **Result logs:** `results.tsv` (every result with its evidence), `sweep.tsv` (job cards), `tags.tsv` (dummy tags), `load.tsv`, `prod.tsv` and `fin.log` (load test).
- **Screenshots:** `shots/`.
