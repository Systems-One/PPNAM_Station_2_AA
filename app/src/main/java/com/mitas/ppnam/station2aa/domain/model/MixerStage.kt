package com.mitas.ppnam.station2aa.domain.model

enum class MachineKind {
    FixedPair, Main, Mixer6, Rajoo, Unknown;

    companion object {
        fun fromWire(value: String): MachineKind = entries.firstOrNull { it.name == value } ?: Unknown
    }
}

/** A commissioned machine (contract §8). [code] is blank until an Admin commissions it. */
data class Machine(
    val id: String,
    val name: String,
    val area: String,
    val code: String,
    val enabled: Boolean,
    val kind: MachineKind,
    val isProduction: Boolean,
    val pairId: String?,
    /** Machine ids (or codes) a mixer may feed. */
    val destinations: List<String>,
)

/** Another preparation, as listed in `data.preparations`. */
data class PrepSummary(
    val id: String,
    val jobId: String,
    val stage: PrepStage,
    val mixerId: String?,
    val productionId: String?,
)

sealed interface MachineUse {
    data object Free : MachineUse
    /** Another live preparation [prepId] holds this machine. */
    data class InUse(val prepId: String) : MachineUse
    data object Disabled : MachineUse
}

sealed interface MachineScan {
    data class StartMix(val machine: Machine, val k: Int, val n: Int, val firstStart: Boolean, val savedMixer: Machine?) : MachineScan
    data class StartProduction(val machine: Machine, val k: Int, val n: Int, val savedProduction: Machine?) : MachineScan
    data class Finish(val machine: Machine, val k: Int, val n: Int, val isProduction: Boolean, val cycleId: String) : MachineScan
    data class NotAMachine(val code: String) : MachineScan
    /** Finish scan of a machine that is not the active one; [activeName] is the one that can finish. */
    data class NotActive(val activeName: String) : MachineScan
    data class Disabled(val machine: Machine) : MachineScan
    data class NotNow(val message: String) : MachineScan
}

enum class StripState { Done, Current, Future }
data class StripStep(val label: String, val state: StripState)
data class StageStrip(val steps: List<StripStep>)

internal fun Machine.matches(ref: String): Boolean =
    id.equals(ref, ignoreCase = true) || (code.isNotBlank() && code.equals(ref.trim(), ignoreCase = true))

private fun PrepSnapshot.resolve(ref: String?): Machine? =
    ref?.takeIf { it.isNotBlank() }?.let { r -> machines.firstOrNull { it.matches(r) } }

fun PrepSnapshot.matchMachine(code: String): Machine? {
    val c = code.trim()
    if (c.isEmpty()) return null
    return machines.firstOrNull { it.code.isNotBlank() && it.code.equals(c, ignoreCase = true) }
}

private val PrepStage.isLive: Boolean get() = this != PrepStage.Completed && this != PrepStage.Cancelled

fun PrepSnapshot.machineUse(machine: Machine): MachineUse {
    if (!machine.enabled || machine.code.isBlank()) return MachineUse.Disabled
    val holder = preparations.firstOrNull { p ->
        p.id != preparation?.id && p.stage.isLive &&
            ((p.mixerId?.let(machine::matches) == true) || (p.productionId?.let(machine::matches) == true))
    }
    return holder?.let { MachineUse.InUse(it.id) } ?: MachineUse.Free
}

fun PrepSnapshot.mixers(): List<Machine> = machines.filter { !it.isProduction && it.kind != MachineKind.Rajoo }

fun PrepSnapshot.savedMixer(): Machine? = resolve(preparation?.mixerId)

fun PrepSnapshot.savedProduction(): Machine? = resolve(preparation?.productionId)

fun PrepSnapshot.isFixedPair(): Boolean = savedMixer()?.kind == MachineKind.FixedPair

fun PrepSnapshot.freeDestinations(): List<Machine> {
    val mixer = savedMixer() ?: return emptyList()
    return mixer.destinations.mapNotNull { resolve(it) }.distinct()
        .filter { it.enabled && machineUse(it) == MachineUse.Free }
}

fun PrepSnapshot.scanMachine(code: String): MachineScan {
    val machine = matchMachine(code) ?: return MachineScan.NotAMachine(code.trim())
    val p = preparation ?: return MachineScan.NotNow("No preparation is open")
    val n = p.mixCount
    return when (p.stage) {
        PrepStage.ReadyForMixer, PrepStage.ReadyForNextMix -> when {
            machine.isProduction -> MachineScan.NotNow("${machine.name} is an extruder — scan a mixer")
            !machine.enabled -> MachineScan.Disabled(machine)
            else -> MachineScan.StartMix(machine, p.mixed + 1, n, firstStart = p.mixerId.isNullOrBlank(), savedMixer = savedMixer())
        }
        PrepStage.ReadyForProduction -> when {
            !machine.isProduction -> MachineScan.NotNow("${machine.name} is a mixer — scan an extruder")
            !machine.enabled -> MachineScan.Disabled(machine)
            else -> MachineScan.StartProduction(machine, p.produced + 1, n, savedProduction())
        }
        PrepStage.Mixing -> finishScan(machine, p, p.mixerId, p.mixed + 1, isProduction = false)
        PrepStage.Producing -> finishScan(machine, p, p.productionId, p.produced + 1, isProduction = true)
        PrepStage.Completed -> MachineScan.NotNow("This preparation is complete")
        else -> MachineScan.NotNow("This preparation is not ready for a machine")
    }
}

private fun PrepSnapshot.finishScan(machine: Machine, p: Preparation, activeRef: String?, k: Int, isProduction: Boolean): MachineScan {
    val active = resolve(activeRef)
    if (active == null || active.id != machine.id) {
        return MachineScan.NotActive(active?.name ?: activeRef.orEmpty())
    }
    val cycle = p.cycleId?.takeIf { it.isNotBlank() }
        ?: return MachineScan.NotNow("No active cycle to finish — refresh")
    return MachineScan.Finish(machine, k, p.mixCount, isProduction, cycle)
}

/** Collect · Mix · Produce · Done; a fixed pair has no Produce step. */
fun Preparation.stageStrip(isFixedPair: Boolean): StageStrip {
    val labels = if (isFixedPair) listOf("Collect", "Mix", "Done") else listOf("Collect", "Mix", "Produce", "Done")
    val current: Int = when (stage) {
        PrepStage.Collecting, PrepStage.AwaitingConfirmation, PrepStage.Unknown -> 0
        PrepStage.ReadyForMixer, PrepStage.Mixing, PrepStage.ReadyForNextMix -> 1
        PrepStage.ReadyForProduction, PrepStage.Producing -> if (isFixedPair) 1 else 2
        PrepStage.Completed -> labels.size
        PrepStage.Cancelled -> -1
    }
    return StageStrip(labels.mapIndexed { i, l ->
        StripStep(l, when {
            current < 0 -> StripState.Future
            i < current -> StripState.Done
            i == current -> StripState.Current
            else -> StripState.Future
        })
    })
}
