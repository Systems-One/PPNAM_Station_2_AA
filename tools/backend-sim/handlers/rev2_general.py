"""rev2_general_requested -> rev2_general_result, for the job-lookup slice: `read` and `lookup`.

Mirrors Rev2ScannerProcessor.ProcessAsync: every reply's data is the full snapshot, with the
command's own result under `result`. The other five General actions exist in the contract but are
outside this slice, so they answer action_not_allowed with a message saying so.

Simulator approximation: the real server builds one-mix quantities from the SAP ProductTree. The
simulator divides each im_Manual line's planned quantity by a mix count derived from
config.rev2OutputPerMix. The shape is exact; the arithmetic is illustrative.
"""

import re

from envelope import Rejection
from state import iso, utc_now

ACTIONS = ("lookup", "read", "prepare", "capture", "confirm", "machine_start", "machine_finish")
SLICE_ACTIONS = ("lookup", "read")
OPEN_STATUSES = ("boposPlanned", "boposReleased")
DIGITS = re.compile(r"^[0-9]+$")
UNITS = {"269": "kg", "268": "each"}
LOOKUP_REJECTION = ("Only Standard Planned or Released jobs in header warehouse FAC with positive "
                    "outstanding output can be loaded.")


def check_action(req):
    """Runs BEFORE session authorization, as on the server."""
    action = req.get("action") or ""
    if action not in ACTIONS:
        raise Rejection("action_not_allowed", "Action is not part of this process mode.")
    if action not in SLICE_ACTIONS:
        raise Rejection("action_not_allowed",
                        "The simulator implements only read and lookup; '%s' is outside the "
                        "job-lookup slice." % action)
    return action


def handle(world, log, req, action):
    """Return (result, snapshot). result["success"] decides success/rev2_rejected."""
    world.general_readers.add(req["deviceId"])
    if action == "lookup":
        result = lookup(world, log, str(req.get("jobCard") or ""))
    else:
        result = {"success": True, "message": "Current progress.",
                  "targetId": req.get("targetId") or None, "cycleId": None}
    snap = snapshot(world, result.get("targetId") or req.get("targetId") or None)
    snap["result"] = result
    return result, snap


def _fail(message):
    return {"success": False, "message": message, "targetId": None, "cycleId": None}


def _round_half_away(value):
    return int(value + 0.5) if value >= 0 else -int(-value + 0.5)


def lookup(world, log, job_card):
    if job_card in world.rev2_jobs:
        log.step("lookup %s: already held, earlier progress preserved" % job_card)
        return {"success": True, "message": "JC loaded.", "targetId": job_card, "cycleId": None}
    if not DIGITS.match(job_card):
        log.fail("lookup '%s': not a digit-only Production Order number" % job_card)
        return _fail(LOOKUP_REJECTION)
    order = world.sap_orders.get(job_card)
    if (not order
            or order.get("ProductionOrderType") != "bopotStandard"
            or order.get("ProductionOrderStatus") not in OPEN_STATUSES
            or order.get("Warehouse") != "FAC"
            or (order.get("PlannedQuantity") or 0) <= (order.get("CompletedQuantity") or 0)):
        log.fail("lookup %s: not an open Standard FAC order with outstanding output" % job_card)
        return _fail(LOOKUP_REJECTION)

    outstanding = order["PlannedQuantity"] - (order.get("CompletedQuantity") or 0)
    output_per_mix = world.config["rev2OutputPerMix"]
    mixes = _round_half_away(outstanding / output_per_mix)
    if mixes <= 0:
        return _fail("The current BOM material required for outstanding output does not round to "
                     "a positive supported whole-mix count.")
    materials = []
    for line in order.get("ProductionOrderLines", []):
        if line.get("ProductionOrderIssueType") != "im_Manual":
            continue
        planned = line.get("PlannedQuantity") or 0.0
        materials.append({
            "code": line["ItemNo"],
            "name": line.get("ItemName") or "",
            "unit": UNITS.get(str(line.get("UoMCode")), str(line.get("UoMCode"))),
            "perMix": round(planned / mixes, 3),
            "required": round(planned, 3),
            "collected": 0.0,
            "excluded": False,
        })
    world.rev2_jobs[job_card] = {
        "id": job_card,
        "product": order.get("ProductDescription") or "",
        "mode": "General",
        "unit": "each",
        "overallQuantity": order["PlannedQuantity"],
        "scopeQuantity": outstanding,
        "sapCompletedAtLookup": order.get("CompletedQuantity") or 0.0,
        "outputPerMix": output_per_mix,
        "requiredMixes": mixes,
        "materials": materials,
        "capturedAtUtc": iso(utc_now()),
        "activeSessionId": None,
        "rajooMachineId": None,
        "closed": False,
        "ingredientChoices": None,
        "exceptionListRevision": None,
    }
    log.transition("lookup %s: job saved locally, %d mixes, %d materials"
                   % (job_card, mixes, len(materials)))
    return {"success": True, "message": "JC loaded.", "targetId": job_card, "cycleId": None}


CAPABILITIES = {"operatorIngredientDecisions": True, "receiptRecovery": True,
                "managerReviewDesktopOnly": True, "jobMixProgress": True}


def mix_progress(job, preparations):
    """Contract §8.1, from the simulator's saved preparations (collectedMixes is not modelled: 0)."""
    preps = [p for p in preparations if p["jobId"] == job["id"] and p["stage"] != "Cancelled"]
    active = [p for p in preps if p["stage"] != "Completed"]
    produced = sum(p["produced"] for p in preps)
    allocated = sum(p["mixCount"] for p in preps)
    required = job["requiredMixes"]
    return {
        "requiredMixes": required,
        "allocatedMixes": allocated,
        "activeMixes": sum(max(0, p["mixCount"] - p["produced"]) for p in active),
        "availableToPrepareMixes": max(0, required - allocated),
        "remainingToFinishMixes": max(0, required - produced),
        "collectedMixes": 0,
        "confirmedMixes": sum(p["mixCount"] for p in preps if p.get("confirmedAtUtc")),
        "mixedMixes": sum(p["mixed"] for p in preps),
        "producedMixes": produced,
        "activePreparationCount": len(active),
        "activePreparations": [{
            "id": p["id"], "jobId": p["jobId"], "mixCount": p["mixCount"], "stage": p["stage"],
            "mixed": p["mixed"], "produced": p["produced"],
            "remainingToFinishMixes": max(0, p["mixCount"] - p["produced"]),
            "collectionRevision": p.get("collectionRevision", 0), "startedAtUtc": p["startedAtUtc"],
            "startedBy": p["startedBy"], "mixerId": p["mixerId"], "productionId": p["productionId"],
            "cycleId": p["cycleId"],
        } for p in active],
    }


def snapshot(world, target_id):
    preparation = world.rev2_preparations.get(target_id) if target_id else None
    job_id = preparation["jobId"] if preparation else target_id
    job = world.rev2_jobs.get(job_id) if job_id else None
    preparations = list(world.rev2_preparations.values())
    return {
        "recovery": None,
        "mode": "General",
        "capabilities": CAPABILITIES,
        "jobs": [{"id": j["id"], "product": j["product"], "closed": j["closed"],
                  "requiredMixes": j["requiredMixes"],
                  "mixProgress": mix_progress(j, preparations)} for j in world.rev2_jobs.values()],
        "job": None if job is None else dict(
            job,
            allocatedMixes=sum(p["mixCount"] for p in preparations if p["jobId"] == job["id"]),
            collectionRevision=0,
            mixProgress=mix_progress(job, preparations)),
        "preparation": preparation if job is not None else None,
        "preparations": preparations,
        "machines": [],
        "exceptionListRevision": 1,
        "ingredientExceptions": [],
        "requiredIngredientChoices": [],
        "collectionExceptions": [],
        "commandExceptions": [],
    }


def seed_demo_jobs(world, log):
    """One held job with one preparation, so a fresh login sees a non-empty list."""
    if lookup(world, log, "510019068")["success"]:
        world.rev2_preparations["PREP_demo0001"] = {
            "id": "PREP_demo0001", "jobId": "510019068", "mixCount": 2, "mixed": 0,
            "produced": 0, "stage": "Collecting", "ingredientChoices": [],
            "exceptionListRevision": 1, "mixerId": None, "productionId": None, "cycleId": None,
            "startedAtUtc": iso(utc_now()), "confirmedAtUtc": None, "startedBy": "SEEDED",
            "materials": [],
        }
        log.transition("seed: job 510019068 held with preparation PREP_demo0001")
