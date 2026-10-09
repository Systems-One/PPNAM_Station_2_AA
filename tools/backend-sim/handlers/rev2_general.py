"""rev2_general_requested -> rev2_general_result: `read`, `lookup`, `recover` and `capture`.

Mirrors Rev2ScannerProcessor.ProcessAsync: every reply's data is the full snapshot, with the
command's own result under `result`. The General actions outside this slice answer
action_not_allowed with a message saying so.

Mutations go through durable receipts keyed (device, request type, message id), as in
Rev2MixingService.Mutate: an identical replay returns the saved result with a fresh snapshot and
never executes again; a changed body under the same id is message_id_conflict. `recover` (from
Rev2MixingService.Recovery) reports a saved receipt's outcome, or seals an absent one so a delayed
original can never execute.

Quantities on preparation rows and pallets are Decimal, so a captured fraction stays exact; the
envelope encoder writes them as plain JSON numbers.

Simulator approximation: the real server builds one-mix quantities from the SAP ProductTree. The
simulator divides each im_Manual line's planned quantity by a mix count derived from
config.rev2OutputPerMix. The shape is exact; the arithmetic is illustrative.
"""

import hashlib
import re
from decimal import Decimal

from envelope import Rejection
from state import iso, utc_now

ACTIONS = ("lookup", "read", "recover", "prepare", "capture", "confirm", "machine_start",
           "machine_finish")
SLICE_ACTIONS = ("lookup", "read", "recover", "capture")
MUTATIONS = ("prepare", "capture", "confirm")
HEX64 = re.compile(r"^[0-9A-Fa-f]{64}$")
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
                        "The simulator implements only %s; '%s' is outside its slice."
                        % (", ".join(SLICE_ACTIONS), action))
    return action


def handle(world, log, req, action, request_type, raw, operator_id):
    """Return (result, snapshot). result["errorCode"], else result["success"], decides `error`."""
    world.general_readers.add(req["deviceId"])
    recovery = None
    if action == "lookup":
        result = lookup(world, log, str(req.get("jobCard") or ""))
    elif action == "recover":
        result, recovery = recover(world, log, req, request_type, operator_id)
    elif action in MUTATIONS:
        result = mutate(world, log, req, action, request_type, raw, operator_id)
    else:
        result = {"success": True, "message": "Current progress.",
                  "targetId": req.get("targetId") or None, "cycleId": None}
    snap = snapshot(world, result.get("targetId") or req.get("targetId") or None)
    snap["result"] = result
    snap["recovery"] = recovery
    return result, snap


def _fail(message, error_code=None):
    result = {"success": False, "message": message, "targetId": None, "cycleId": None}
    if error_code:
        # Rev2Result.ErrorCode is omitted from the wire when null.
        result["errorCode"] = error_code
    return result


def _dec(value):
    """Exact decimal from a seed/JSON number: Decimal(str(x)), so 0.1 stays 0.1."""
    return value if isinstance(value, Decimal) else Decimal(str(value))


def _remaining(row):
    return max(Decimal(0), _dec(row["required"]) - _dec(row["collected"]))


# ---- receipts (Rev2MixingService.Mutate / Recovery) ----------------------------------------------

def mutate(world, log, req, action, request_type, raw, operator_id):
    key = (req["deviceId"], request_type, req["messageId"])
    body_hash = hashlib.sha256(raw).hexdigest().upper()
    prior = world.rev2_receipts.get(key)
    if prior is not None:
        if prior["sealed"] and prior["hash"] == body_hash:
            log.warn("%s %s: identity sealed by recovery -> receipt_sealed" % (action, key[2]))
            return prior["result"]
        if prior["hash"] == body_hash:
            log.step("%s %s: identical replay, saved result returned, nothing executed"
                     % (action, key[2]))
            return prior["result"]
        log.fail("%s %s: same message id, different body" % (action, key[2]))
        return _fail("This message ID was already used with a different body.",
                     "message_id_conflict")
    result = EXECUTORS[action](world, log, req)
    world.rev2_receipts[key] = {"hash": body_hash, "result": result, "operatorId": operator_id,
                                "sealed": False}
    return result


def recover(world, log, req, request_type, operator_id):
    """Return (result, recovery). recovery is None unless the lookup or seal succeeded."""
    original_id = req.get("originalMessageId")
    original_fp = req.get("originalRequestFingerprint")
    if (not isinstance(original_id, str) or not original_id.strip() or len(original_id) > 100
            or original_id == req["messageId"]
            or not isinstance(original_fp, str) or not HEX64.match(original_fp)):
        return _fail("Supply the original message ID and SHA-256 of its exact UTF-8 request body.",
                     "invalid_envelope"), None
    key = (req["deviceId"], request_type, original_id)
    prior = world.rev2_receipts.get(key)
    if prior is not None:
        if prior["operatorId"] != operator_id:
            log.fail("recover %s: receipt belongs to %s" % (original_id, prior["operatorId"]))
            return _fail("Sign in as the operator who submitted the original command on this "
                         "device.", "receipt_owner_mismatch"), None
        if prior["hash"].upper() != original_fp.upper():
            log.fail("recover %s: fingerprint differs from the saved receipt" % original_id)
            return _fail("The saved receipt belongs to a different request body.",
                         "message_id_conflict"), None
        outcome = ("not_executed" if prior["sealed"]
                   else "committed" if prior["result"]["success"] else "rejected")
        saved = prior["result"]
    else:
        saved = {"success": False,
                 "message": "The unanswered command did not execute and its message ID is now "
                            "sealed. Reload before submitting a new intent.",
                 "targetId": None, "cycleId": None, "errorCode": "receipt_sealed"}
        world.rev2_receipts[key] = {"hash": original_fp.upper(), "result": saved,
                                    "operatorId": operator_id, "sealed": True}
        outcome = "not_executed"
    log.transition("recover %s: %s" % (original_id, outcome))
    return ({"success": True,
             "message": "Original command outcome recovered. Follow the current saved state.",
             "targetId": saved.get("targetId"), "cycleId": saved.get("cycleId")},
            {"originalMessageId": original_id, "outcome": outcome, "result": saved})


# ---- capture (Rev2MixingService.Capture) ---------------------------------------------------------

def find_pallet(world, code):
    """By RFID tag or pallet id, trimmed and case-insensitive."""
    code = (code or "").strip().lower()
    if not code:
        return None
    for p in world.pallets.values():
        if p["palletRfidTag"].lower() == code or p["palletId"].lower() == code:
            return p
    return None


def capture(world, log, req):
    target = req.get("targetId") or ""
    batch = world.rev2_preparations.get(target)
    job = world.rev2_jobs.get(batch["jobId"] if batch else target)
    if job is None:
        return _fail("Select an active preparation or Rajoo JC.")
    if batch is None or batch["stage"] != "Collecting":
        return _fail("This preparation is not collecting ingredients.")
    pallet = find_pallet(world, req.get("code") if isinstance(req.get("code"), str) else "")
    if pallet is None or pallet["blocked"] or pallet["palletState"] not in ("Holding", "Mixing"):
        log.fail("capture %s: pallet %r is unknown, blocked or not in Holding/Mixing"
                 % (target, req.get("code")))
        return _fail("Pallet must be an available local Station 1 pallet in Holding or Mixing.")
    material = next((m for m in batch["materials"]
                     if m["code"].lower() == pallet["productCode"].lower() and not m["excluded"]),
                    None)
    if material is None or _remaining(material) <= 0:
        log.fail("capture %s: %s is not outstanding" % (target, pallet["productCode"]))
        return _fail("This material is not outstanding on the collection BOM.")
    if pallet["unit"] != material["unit"]:
        return _fail("Pallet and recipe units do not match.")
    bag = _dec(pallet.get("fullBagWeight") or world.config["defaultFullBagWeightKg"])
    quantity = req.get("quantity")
    q = bag if quantity in (None, 0) else _dec(quantity)
    stock = _dec(pallet["remainingQuantity"])
    if q <= 0 or q > bag or q > stock or q > _remaining(material):
        log.fail("capture %s: %s of %s outside bag %s, stock %s, remaining %s"
                 % (target, q, material["code"], bag, stock, _remaining(material)))
        return _fail("Capture one bag or its actual fraction, within both pallet stock and the "
                     "outstanding material target.")
    pallet["remainingQuantity"] = stock - q
    material["collected"] = _dec(material["collected"]) + q
    batch["collectionRevision"] = batch.get("collectionRevision", 0) + 1
    if all(_remaining(m) == 0 for m in batch["materials"]):
        batch["stage"] = "AwaitingConfirmation"
    log.transition("capture %s: %s %s %s from %s (pallet now %s), revision %d, stage %s"
                   % (target, q, material["unit"], material["code"], pallet["palletId"],
                      pallet["remainingQuantity"], batch["collectionRevision"], batch["stage"]))
    return {"success": True, "message": "Bag recorded and local pallet stock deducted.",
            "targetId": batch["id"], "cycleId": None}


EXECUTORS = {"capture": capture}


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


def material_dto(m):
    """Contract section 8 material: the stored row plus originalRequired and remaining."""
    return dict(m, originalRequired=m.get("originalRequired", m["required"]),
                remaining=_remaining(m))


def preparation_dto(p):
    return dict(p, materials=[material_dto(m) for m in p["materials"]])


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
            materials=[material_dto(m) for m in job["materials"]],
            # Contract §8: non-cancelled preparations only — the same total as mixProgress.
            allocatedMixes=sum(p["mixCount"] for p in preparations
                               if p["jobId"] == job["id"] and p["stage"] != "Cancelled"),
            collectionRevision=0,
            mixProgress=mix_progress(job, preparations)),
        "preparation": preparation_dto(preparation) if job is not None and preparation else None,
        "preparations": [preparation_dto(p) for p in preparations],
        "machines": [],
        "exceptionListRevision": 1,
        "ingredientExceptions": [],
        "requiredIngredientChoices": [],
        "collectionExceptions": [],
        "commandExceptions": [],
    }


def effective_rows(job, mix_count, excluded=()):
    """A preparation's effective rows: Decimal(str(perMix)) x N, excluded rows at required 0."""
    excluded = {c.lower() for c in excluded}
    rows = []
    for m in job["materials"]:
        original = _dec(m["perMix"]) * mix_count
        out = m["code"].lower() in excluded
        rows.append({"code": m["code"], "name": m["name"], "unit": m["unit"], "perMix": m["perMix"],
                     "required": Decimal(0) if out else original, "collected": Decimal(0),
                     "excluded": out, "originalRequired": original})
    return rows


def seed_demo_jobs(world, log):
    """One held job with one preparation, so a fresh login sees a non-empty list."""
    if lookup(world, log, "510019068")["success"]:
        world.rev2_preparations["PREP_demo0001"] = {
            "id": "PREP_demo0001", "jobId": "510019068", "mixCount": 2, "mixed": 0,
            "produced": 0, "stage": "Collecting", "includeTackifier": None,
            "ingredientChoices": [], "exceptionListRevision": 1, "collectionRevision": 0,
            "mixerId": None, "productionId": None, "cycleId": None,
            "startedAtUtc": iso(utc_now()), "confirmedAtUtc": None, "startedBy": "SEEDED",
            "materials": effective_rows(world.rev2_jobs["510019068"], 2),
        }
        log.transition("seed: job 510019068 held with preparation PREP_demo0001")
