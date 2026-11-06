package com.mitas.ppnam.station2aa.domain.feedback

import com.mitas.ppnam.station2aa.domain.model.MachineScan
import com.mitas.ppnam.station2aa.domain.model.SourceFit
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.IdentifyResult
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupResult
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult

/** The two suite scan sounds (UI_Design/sounds/README.md). */
enum class ScanSound { SUCCESS, FAIL }

/**
 * Suite rule S1: which sound a scan's outcome makes. A scan sounds once: success when Station 2
 * accepts it, fail when Station 2 refuses it, it times out, or the handheld refuses it. Nothing
 * sounds on the raw read, because the scanner service already beeps for it. A read the screen
 * takes but sends only after a tap (the machine confirmation card, the Rajoo job card field) is
 * silent until Station 2 answers that tap. Non-scan actions (ingredient decisions, confirm
 * collection, Retry, offers, login, navigation, settings) never reach here. A command still
 * unresolved or never sent (offline) makes no sound: its outcome is not known yet. The ViewModels
 * emit what this decides, and the screens play it through `ui.feedback.ScanFeedback`.
 */
object ScanSounds {

    /**
     * Station 2's answer to a pallet `identify`. [fit] is how the identified pallet fits the
     * shown rows: only an outstanding row of a usable pallet is accepted. A pallet that is
     * blocked, not on the BOM, excluded or already collected fails, and so do not found,
     * timed out and could not identify.
     */
    fun forIdentify(result: IdentifyResult, fit: SourceFit?): ScanSound = when (result) {
        is IdentifyResult.Identified ->
            if (result.source.usable && fit is SourceFit.InMix) ScanSound.SUCCESS else ScanSound.FAIL
        is IdentifyResult.Unknown,
        is IdentifyResult.Unsupported,
        is IdentifyResult.Failed -> ScanSound.FAIL
    }

    /**
     * Station 2's answer to a command carrying a scanned code: a capture, or a machine start /
     * finish / close. Unresolved (queued) and not-sent (offline) outcomes stay silent.
     */
    fun forCommand(result: CollectionResult): ScanSound? = when (result) {
        is CollectionResult.Done,
        is CollectionResult.AlreadySettled -> ScanSound.SUCCESS
        is CollectionResult.Rejected -> ScanSound.FAIL
        is CollectionResult.Unresolved,
        is CollectionResult.NotSent -> null
    }

    /**
     * A machine code scanned in the mixer or production stage. A wrong machine fails. An
     * accepted one opens the confirmation card silently: its Start / Finish answer sounds.
     */
    fun forMachineScan(scan: MachineScan): ScanSound? = when (scan) {
        is MachineScan.NotAMachine,
        is MachineScan.NotActive,
        is MachineScan.NotNow -> ScanSound.FAIL
        is MachineScan.StartMix,
        is MachineScan.StartProduction,
        is MachineScan.Finish,
        is MachineScan.Disabled -> null
    }

    /** Station 2's answer to a General job lookup. A `PREP_` id opens its preparation. */
    fun forJobLookup(result: JobLookupResult): ScanSound = when (result) {
        is JobLookupResult.Loaded,
        is JobLookupResult.OpenPreparation -> ScanSound.SUCCESS
        is JobLookupResult.Failed -> ScanSound.FAIL
    }

    /** Station 2's answer to a Rajoo job card `lookup`. */
    fun forRajooLookup(result: ReadResult): ScanSound = when (result) {
        is ReadResult.Loaded -> ScanSound.SUCCESS
        is ReadResult.Failed -> ScanSound.FAIL
    }
}

/**
 * Suite rule S3: a single trigger pull can produce several reads of the same tag. A read of the
 * value the screen is still working on ([inHand]: the scan in flight, or the pallet or machine card
 * it produced) is the same scan. So is a value that failed within the last [WINDOW_MS]. Both stay
 * silent, but the screen still handles them as before. Not thread-safe: owned by a ViewModel on
 * the main thread.
 */
class ScanRepeatGate(private val nowMs: () -> Long = System::currentTimeMillis) {
    private var failed: String? = null
    private var failedAt = 0L

    /** True when [value] is a repeat read and its outcome must not sound again. */
    fun isRepeat(value: String, inHand: String? = null): Boolean {
        if (inHand != null && value == inHand.trim()) return true
        return value == failed && nowMs() - failedAt < WINDOW_MS
    }

    /** [value]'s scan failed (refused here or by Station 2): repeats within the window are silent. */
    fun failed(value: String) {
        failed = value
        failedAt = nowMs()
    }

    companion object {
        const val WINDOW_MS = 1_500L
    }
}
