"""rev2_general_requested -> rev2_general_result: `read`, `identify`, `lookup`, `recover`,
`prepare`, `capture`, `adjust_ingredient` (kind Add only), `confirm`, `machine_start` and
`machine_finish`.

Mirrors Rev2ScannerProcessor.ProcessAsync: every reply's data is the full snapshot, with the
command's own result under `result`. The General actions outside this slice answer
action_not_allowed with a message saying so.

Mutations go through durable receipts keyed (device, request type, message id), as in
Rev2MixingService.Mutate: an identical replay returns the saved result with a fresh snapshot and
never executes again; a changed body under the same id is message_id_conflict. `recover` (from
Rev2MixingService.Recovery) reports a saved receipt's outcome, or seals an absent one so a delayed
original can never execute.

`prepare` validates the per-code Include/Exclude choices against the Settings ingredient-exception
catalog (IngredientExceptionRules) and freezes effective rows; an excluded row keeps its original
quantity at required 0 and records an "Ingredient omitted" exception. A rejected capture or confirm
against a loaded collection records a "Rejected collection action" exception. Every successful or
exception-recording mutation pushes active_job_cards_invalidated with the action's reason.

`identify` (contract 4.1) is read-only (no receipt, no exception, no stock change) and answers a
compact `data:{result, preparation, source}`: the selected PREP (or null) and the scanned pallet's
material, bag size, stock and usability. An unresolved code is `pallet_not_found`, source null.
`adjust_ingredient` implements only `Add`; the other kinds are a definite rejection.

Quantities on preparation rows and pallets are Decimal, so a captured fraction stays exact; the
envelope encoder writes them as plain JSON numbers.

Simulator approximation: the real server builds one-mix quantities from the SAP ProductTree. The
simulator divides each im_Manual line's planned quantity by a mix count derived from
config.rev2OutputPerMix. The shape is exact; the arithmetic is illustrative.
"""

import hashlib
import json
import re
import uuid
from decimal import Decimal

from envelope import Rejection
from state import iso, utc_now

ACTIONS = ("lookup", "read", "identify", "recover", "prepare", "capture", "adjust_ingredient",
           "confirm", "machine_start", "machine_finish")
SLICE_ACTIONS = ("lookup", "read", "identify", "recover", "prepare", "capture",
                 "adjust_ingredient", "confirm", "machine_start", "machine_finish")
MUTATIONS = ("prepare", "capture", "adjust_ingredient", "confirm", "machine_start",
             "machine_finish")
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
    """Return (result, snapshot, hint_reason).

    result["errorCode"], else result["success"], decides `error`. hint_reason is the
    active_job_cards_invalidated reason to push, or None (reads, replays, plain rejections).
    """
    world.general_readers.add(req["deviceId"])
    recovery, hint = None, None
    if action == "lookup":
        result = lookup(world, log, str(req.get("jobCard") or ""))
    elif action == "recover":
        result, recovery = recover(world, log, req, request_type, operator_id)
    elif action in MUTATIONS:
        result, hint = mutate(world, log, req, action, request_type, raw, operator_id)
    elif action == "identify":
        result, source = identify(world, log, req)
        prep = world.rev2_preparations.get(result.get("targetId") or "")
        # Contract 4.1: deliberately compact - no lists, machines, catalog or exceptions.
        return result, {"result": result, "preparation": preparation_dto(prep) if prep else None,
                        "source": source}, None
    else:
        result = {"success": True, "message": "Current progress.",
                  "targetId": req.get("targetId") or None, "cycleId": None}
    command_id = recovery["originalMessageId"] if recovery else req["messageId"]
    snap = snapshot(world, result.get("targetId") or req.get("targetId") or None,
                    req["deviceId"], command_id)
    snap["result"] = result
    snap["recovery"] = recovery
    return result, snap, hint


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

def _general_scope(world, req):
    """(job, batch) a General capture/decision/confirm addresses, for its rejection record."""
    target = req.get("targetId") or ""
    batch = world.rev2_preparations.get(target)
    job = world.rev2_jobs.get(batch["jobId"] if batch else target)
    if job is not None and job["mode"] != "General":
        job = None
    return job, batch


def mutate(world, log, req, action, request_type, raw, operator_id, executors=None,
           scope=_general_scope, recorded=("capture", "adjust_ingredient", "confirm")):
    """Return (result, hint_reason). A replay or conflict executes nothing and pushes nothing.

    Shared by both families: Rajoo passes its own executors and scope (JC, no batch).
    """
    executors = executors or EXECUTORS
    key = (req["deviceId"], request_type, req["messageId"])
    body_hash = hashlib.sha256(raw).hexdigest().upper()
    prior = world.rev2_receipts.get(key)
    if prior is not None:
        if prior["sealed"] and prior["hash"] == body_hash:
            log.warn("%s %s: identity sealed by recovery -> receipt_sealed" % (action, key[2]))
            return prior["result"], None
        if prior["hash"] == body_hash:
            log.step("%s %s: identical replay, saved result returned, nothing executed"
                     % (action, key[2]))
            return prior["result"], None
        log.fail("%s %s: same message id, different body" % (action, key[2]))
        return _fail("This message ID was already used with a different body.",
                     "message_id_conflict"), None
    exception_count = len(world.rev2_exceptions)
    result = executors[action](world, log, req, operator_id)
    if not result["success"] and action in recorded:
        # Mutate: a rejected capture/confirm against a loaded collection is an operator event.
        job, batch = scope(world, req)
        if job is not None:
            quantity = req.get("quantity")
            source = req.get("code") or ""
            details = ""
            if action == "adjust_ingredient":
                adjustment = req.get("adjustment")
                source = (adjustment.get("sourceCode") or "") if isinstance(adjustment, dict) else ""
                details = " Attempted decision: %s" % json.dumps(adjustment, separators=(",", ":"))
            record_exception(world, req, operator_id, job, batch, "Rejected collection action",
                             "%s: %s Source: %s. Quantity: %s.%s"
                             % (action, result["message"], source,
                                0 if quantity is None else quantity, details))
    world.rev2_receipts[key] = {"hash": body_hash, "result": result, "operatorId": operator_id,
                                "sealed": False}
    if result["success"]:
        hint = "preparation_created" if action == "prepare" else action
    elif len(world.rev2_exceptions) != exception_count:
        hint = "collection_exception_recorded"
    else:
        hint = None
    return result, hint


def record_exception(world, req, operator_id, job, batch, kind, details):
    """Rev2MixingService.RecordCollectionException: the contract §6 exception DTO."""
    record = {
        "id": uuid.uuid4().hex, "timestampUtc": iso(utc_now()), "operatorId": operator_id,
        "device": req["deviceId"], "jobId": job["id"], "batchId": batch["id"] if batch else None,
        "sessionId": job.get("activeSessionId"), "kind": kind, "details": details,
        "reason": (req.get("reason") or "").strip() if isinstance(req.get("reason"), str) else "",
        "beforeJson": "", "afterJson": "", "messageId": req["messageId"],
        "reviewedAtUtc": None, "reviewedBy": None, "reviewReason": None,
    }
    world.rev2_exceptions.append(record)
    return record


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


# ---- prepare (Rev2MixingService.Prepare, IngredientExceptionRules) ------------------------------

def matching_exceptions(world, job):
    """Catalog rows whose code is one of the job's BOM materials (case-insensitive)."""
    codes = {m["code"].lower() for m in job["materials"]}
    return [p for p in world.ingredient_exceptions["products"] if p["code"].lower() in codes]


def validate_choices(world, job, req):
    """Return (error_message_or_None, choices), in IngredientExceptionRules.Validate's order."""
    if req.get("includeTackifier") is False:
        return ("Use a separate Include or Exclude choice for each exception ingredient; the old "
                "tackifier flag cannot exclude materials."), []
    matches = matching_exceptions(world, job)
    revision = req.get("exceptionListRevision")
    if revision is not None and revision != world.ingredient_exceptions["revision"]:
        return "The ingredient exception list changed. Reload and review the ingredient choices.", []
    supplied = req.get("ingredientChoices") or []
    if not isinstance(supplied, list):
        supplied = [None]
    keys = []
    for c in supplied:
        if (not isinstance(c, dict) or not isinstance(c.get("code"), str) or not c["code"].strip()
                or not isinstance(c.get("include"), bool)):
            return "Choose Include or Exclude once for each exception ingredient.", []
        keys.append(c["code"].strip().lower())
    if len(set(keys)) != len(keys):
        return "Choose Include or Exclude once for each exception ingredient.", []
    match_codes = [m["code"].lower() for m in matches]
    if any(k not in match_codes for k in keys):
        return "A supplied ingredient choice is not a matching BOM exception. Reload the choices.", []
    if any(m not in keys for m in match_codes):
        return ("Choose Include or Exclude for each exception ingredient: "
                + ", ".join(m["code"] for m in matches) + "."), []
    if matches and revision is None:
        return "Reload the ingredient exception list before submitting these choices.", []
    choices = [{"code": m["code"],
                "include": next(c["include"] for c in supplied
                                if c["code"].strip().lower() == m["code"].lower())}
               for m in matches]
    excluded = {c["code"].lower() for c in choices if c["include"] is False}
    if all(m["code"].lower() in excluded for m in job["materials"]):
        return "At least one ingredient must be included.", []
    return None, choices


def prepare(world, log, req, operator_id):
    job = world.rev2_jobs.get(req.get("targetId") or "")
    if job is None or job["mode"] != "General":
        return _fail("Load a General Mixing JC first.")
    allocated = sum(p["mixCount"] for p in world.rev2_preparations.values()
                    if p["jobId"] == job["id"] and p["stage"] != "Cancelled")
    available = job["requiredMixes"] - allocated
    mix_count = req.get("mixCount")
    if not isinstance(mix_count, int) or isinstance(mix_count, bool):
        mix_count = 0
    if mix_count <= 0 or mix_count > available:
        log.fail("prepare %s: %d mixes requested, %d available" % (job["id"], mix_count, available))
        return _fail("Choose a positive whole number up to %d mixes remaining to prepare."
                     % available)
    error, choices = validate_choices(world, job, req)
    if error:
        log.fail("prepare %s: %s" % (job["id"], error))
        return _fail(error)
    excluded = [c["code"] for c in choices if c["include"] is False]
    batch = {
        "id": "PREP_" + uuid.uuid4().hex, "jobId": job["id"], "mixCount": mix_count, "mixed": 0,
        "produced": 0, "stage": "Collecting", "includeTackifier": None,
        "ingredientChoices": choices,
        "exceptionListRevision": world.ingredient_exceptions["revision"],
        "collectionRevision": 0, "mixerId": None, "productionId": None, "cycleId": None,
        "startedAtUtc": iso(utc_now()), "confirmedAtUtc": None, "startedBy": operator_id,
        "materials": effective_rows(job, mix_count, excluded),
    }
    world.rev2_preparations[batch["id"]] = batch
    for m in batch["materials"]:
        if m["excluded"]:
            record_exception(world, req, operator_id, job, batch, "Ingredient omitted",
                             "%s %s: original target %s %s; excluded before collection."
                             % (m["code"], m["name"], format(m["originalRequired"], "f"),
                                m["unit"]))
    log.transition("prepare %s: %s created, %d mixes, excluded %s"
                   % (job["id"], batch["id"], mix_count, excluded or "nothing"))
    return {"success": True,
            "message": "Prepare %d mixes. Collect one-mix quantities \u00d7 %d; then explicitly "
                       "confirm collection." % (mix_count, mix_count),
            "targetId": batch["id"], "cycleId": None}


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


def take_bag(world, log, target, materials, req):
    """Shared capture rules (General PREP rows or Rajoo full-job rows).

    Return (error_message, pallet, material, quantity); on success the pallet stock is deducted
    and the material's collected total increased, error_message None.
    """
    pallet = find_pallet(world, req.get("code") if isinstance(req.get("code"), str) else "")
    if pallet is None or pallet["blocked"] or pallet["palletState"] not in ("Holding", "Mixing"):
        log.fail("capture %s: pallet %r is unknown, blocked or not in Holding/Mixing"
                 % (target, req.get("code")))
        return ("Pallet must be an available local Station 1 pallet in Holding or Mixing.",
                None, None, None)
    material = next((m for m in materials
                     if m["code"].lower() == pallet["productCode"].lower() and not m["excluded"]),
                    None)
    if material is None or _remaining(material) <= 0:
        log.fail("capture %s: %s is not outstanding" % (target, pallet["productCode"]))
        return "This material is not outstanding on the collection BOM.", pallet, None, None
    if pallet["unit"] != material["unit"]:
        return "Pallet and recipe units do not match.", pallet, material, None
    bag = _dec(pallet.get("bagSize") or world.config["defaultFullBagWeightKg"])
    quantity = req.get("quantity")
    q = bag if quantity in (None, 0) else _dec(quantity)
    stock = _dec(pallet["remainingQuantity"])
    if q <= 0 or q > bag or q > stock or q > _remaining(material):
        log.fail("capture %s: %s of %s outside bag %s, stock %s, remaining %s"
                 % (target, q, material["code"], bag, stock, _remaining(material)))
        return ("Capture one bag or its actual fraction, within both pallet stock and the "
                "outstanding material target.", pallet, material, None)
    pallet["remainingQuantity"] = stock - q
    material["collected"] = _dec(material["collected"]) + q
    return None, pallet, material, q


def capture(world, log, req, operator_id):
    target = req.get("targetId") or ""
    batch = world.rev2_preparations.get(target)
    job = world.rev2_jobs.get(batch["jobId"] if batch else target)
    if job is None:
        return _fail("Select an active preparation or Rajoo JC.")
    if batch is None or batch["stage"] != "Collecting":
        return _fail("This preparation is not collecting ingredients.")
    error, pallet, material, q = take_bag(world, log, target, batch["materials"], req)
    if error:
        return _fail(error)
    batch["collectionRevision"] = batch.get("collectionRevision", 0) + 1
    if all(_remaining(m) == 0 for m in batch["materials"]):
        batch["stage"] = "AwaitingConfirmation"
    log.transition("capture %s: %s %s %s from %s (pallet now %s), revision %d, stage %s"
                   % (target, q, material["unit"], material["code"], pallet["palletId"],
                      pallet["remainingQuantity"], batch["collectionRevision"], batch["stage"]))
    return {"success": True, "message": "Bag recorded and local pallet stock deducted.",
            "targetId": batch["id"], "cycleId": None}


def pallet_usability(pallet):
    """(usable, reason): unblocked local Station 1 pallet in Holding/Mixing with stock."""
    if pallet["blocked"]:
        return False, "Pallet is blocked."
    if pallet["palletState"] not in ("Holding", "Mixing"):
        return False, "Pallet is not in Holding or Mixing (state %s)." % pallet["palletState"]
    if _dec(pallet["remainingQuantity"]) <= 0:
        return False, "Pallet has no stock left."
    return True, ""


def _general_target(world, target):
    job = world.rev2_jobs.get(target)
    return target in world.rev2_preparations or (job is not None and job["mode"] == "General")


def identify(world, log, req, target_ok=_general_target,
             bad_target="Select a General job card or preparation."):
    """Return (result, source). Read-only: no receipt, no exception, no stock change.

    The target is optional (a General JC or PREP, or for Rajoo a Rajoo JC); a supplied one must
    exist in the mode. A blank code or a bad target rejects without inspection.
    """
    target = req.get("targetId") or ""
    if target and not target_ok(world, target):
        log.fail("identify: no job or preparation %r in this mode" % target)
        return _fail(bad_target), None
    code = (req.get("code") if isinstance(req.get("code"), str) else "").strip()
    if not code:
        log.fail("identify: blank code")
        return _fail("Scan a pallet tag or label."), None
    pallet = find_pallet(world, code)
    if pallet is None:
        log.fail("identify %s: unknown code %r" % (target or "(standalone)", code))
        result = _fail("No current local pallet matches this code.", "pallet_not_found")
        result["targetId"] = target or None
        return result, None
    usable, reason = pallet_usability(pallet)
    bag = pallet.get("bagSize")
    source = {"code": code, "materialCode": pallet["productCode"], "name": pallet["productName"],
              "unit": pallet["unit"], "bagSize": None if bag is None else _dec(bag),
              "available": _dec(pallet["remainingQuantity"]), "usable": usable, "reason": reason}
    log.step("identify %s: %r -> %s %s, usable=%s" % (target or "(standalone)", code,
                                                      pallet["palletId"], pallet["productCode"], usable))
    return {"success": True, "message": "Source identified.", "targetId": target or None,
            "cycleId": None}, source


def add_row(world, adjustment, materials):
    """Shared Add validation. Return (error_message, new_row, source_pallet); not yet appended."""
    if not isinstance(adjustment, dict):
        return "Supply one adjustment object.", None, None
    kind = adjustment.get("kind")
    if kind != "Add":
        return ("The simulator implements only adjustment kind Add; '%s' is not supported."
                % kind), None, None
    source = adjustment.get("sourceCode")
    pallet = find_pallet(world, source if isinstance(source, str) else "")
    if pallet is None:
        return "The source is not a known local pallet.", None, None
    usable, why = pallet_usability(pallet)
    if not usable:
        return "The source is not available. %s" % why, None, None
    if pallet["unit"] != "kg":
        return "Only kg materials can be added.", None, None
    quantity = adjustment.get("targetQuantity")
    if (isinstance(quantity, bool) or not isinstance(quantity, (int, float, Decimal))
            or not 0 < _dec(quantity) <= 999999999):
        return "Enter a positive target quantity.", None, None
    if any(m["code"].lower() == pallet["productCode"].lower() for m in materials):
        return "This material is already in the collection; use Quantity for its row.", None, None
    row = {"code": pallet["productCode"], "name": pallet["productName"].strip(),
           "unit": pallet["unit"], "perMix": Decimal(0), "required": _dec(quantity),
           "collected": Decimal(0), "excluded": False, "originalRequired": Decimal(0)}
    return None, row, pallet


def adjust_ingredient(world, log, req, operator_id):
    """Rev2MixingService decision, contract section 6: kind Add only."""
    target = req.get("targetId") or ""
    batch = world.rev2_preparations.get(target)
    job = world.rev2_jobs.get(batch["jobId"] if batch else target)
    if job is None or batch is None:
        return _fail("Select an active preparation.")
    if batch["stage"] not in ("Collecting", "AwaitingConfirmation"):
        return _fail("Ingredient decisions are allowed only while the preparation is unconfirmed "
                     "and unassigned.")
    revision = req.get("collectionRevision")
    if (not isinstance(revision, int) or isinstance(revision, bool)
            or revision != batch.get("collectionRevision", 0)):
        log.fail("adjust_ingredient %s: revision %r, current %d"
                 % (target, revision, batch.get("collectionRevision", 0)))
        return _fail("The collection changed. Reload its quantities before recording this "
                     "decision.", "collection_revision_conflict")
    reason = req.get("reason")
    reason = reason.strip() if isinstance(reason, str) else ""
    if not 1 <= len(reason) <= 1000:
        return _fail("Enter a reason of 1 to 1000 characters.")
    error, row, pallet = add_row(world, req.get("adjustment"), batch["materials"])
    if error:
        return _fail(error)
    before = json.dumps([material_dto(m) for m in batch["materials"]], default=str)
    batch["materials"].append(row)
    batch["collectionRevision"] = batch.get("collectionRevision", 0) + 1
    batch["stage"] = "Collecting"
    record = record_exception(
        world, req, operator_id, job, batch, "Ingredient added",
        "Added %s: target %s %s; source pallet %s."
        % (row["code"], format(row["required"], "f"), row["unit"], pallet["palletId"]))
    record["beforeJson"] = before
    record["afterJson"] = json.dumps([material_dto(m) for m in batch["materials"]], default=str)
    log.transition("adjust_ingredient %s: added %s target %s from %s, revision %d"
                   % (target, row["code"], row["required"], pallet["palletId"],
                      batch["collectionRevision"]))
    return {"success": True, "message": "Ingredient added. Scan the source again to collect it.",
            "targetId": batch["id"], "cycleId": None}


def confirm(world, log, req, operator_id):
    """Rev2MixingService.Confirm."""
    batch = world.rev2_preparations.get(req.get("targetId") or "")
    if (batch is None or batch["stage"] != "AwaitingConfirmation"
            or any(_remaining(m) > 0 for m in batch["materials"])):
        log.fail("confirm %s: not every included material is collected" % req.get("targetId"))
        return _fail("Collect every included material before confirming.")
    batch["stage"] = "ReadyForMixer"
    batch["confirmedAtUtc"] = iso(utc_now())
    log.transition("confirm %s: -> ReadyForMixer" % batch["id"])
    return {"success": True,
            "message": "Ingredients confirmed. Scan a permitted mixer to assign this preparation.",
            "targetId": batch["id"], "cycleId": None}


# ---- machine_start / machine_finish (contract section 4) -------------------------------------------
# Machine rejections are plain rev2_rejected results: contract section 6 records exceptions only
# for rejected capture, confirmation or ingredient decisions, so none is recorded here.

def find_machine(world, code):
    """A configured machine by its commissioned (non-blank) code, trimmed, case-insensitive."""
    code = (code or "").strip().lower()
    if not code:
        return None
    return next((m for m in world.rev2_machines if m["code"].strip().lower() == code), None)


def machine_by_id(world, machine_id):
    return next((m for m in world.rev2_machines if m["id"] == machine_id), None)


def _occupant(world, machine, batch):
    """Another non-completed PREP that holds this machine as its mixer or production machine."""
    return next((p for p in world.rev2_preparations.values()
                 if p["id"] != batch["id"] and p["stage"] not in ("Completed", "Cancelled")
                 and machine["id"] in (p.get("mixerId"), p.get("productionId"))), None)


def machine_start(world, log, req, operator_id):
    batch = world.rev2_preparations.get(req.get("targetId") or "")
    if batch is None:
        return _fail("Select an active preparation.")
    stage = batch["stage"]
    if stage not in ("ReadyForMixer", "ReadyForNextMix", "ReadyForProduction"):
        log.fail("machine_start %s: stage %s" % (batch["id"], stage))
        return _fail("A machine cannot be started while the preparation is %s." % stage)
    code = req.get("code") if isinstance(req.get("code"), str) else ""
    machine = find_machine(world, code)
    if machine is None:
        log.fail("machine_start %s: %r is not a configured machine" % (batch["id"], code))
        return _fail("The scanned code is not a configured machine.")
    if not machine["enabled"]:
        log.fail("machine_start %s: %s is disabled" % (batch["id"], machine["id"]))
        return _fail("%s is disabled and cannot be started." % machine["name"])
    mixer = machine_by_id(world, batch["mixerId"]) if batch["mixerId"] else None
    if stage == "ReadyForMixer":
        if machine["isProduction"]:
            return _fail("Scan a mixer; %s is a production machine." % machine["name"])
    elif stage == "ReadyForNextMix":
        if mixer is None or machine["id"] != mixer["id"]:
            return _fail("Scan the assigned mixer %s for the next mix."
                         % (mixer["name"] if mixer else batch["mixerId"]))
    else:
        if not machine["isProduction"]:
            return _fail("Scan a production machine; %s is a mixer." % machine["name"])
        if mixer is None or machine["id"] not in mixer["destinations"]:
            return _fail("%s is not a permitted destination of %s."
                         % (machine["name"], mixer["name"] if mixer else batch["mixerId"]))
        if batch["productionId"] and batch["productionId"] != machine["id"]:
            return _fail("This preparation is assigned to production machine %s; scan that one."
                         % batch["productionId"])
    other = _occupant(world, machine, batch)
    if other is not None:
        log.fail("machine_start %s: %s is held by %s" % (batch["id"], machine["id"], other["id"]))
        return _fail("%s is occupied by preparation %s." % (machine["name"], other["id"]))
    cycle = "CYC_" + uuid.uuid4().hex
    mixing = stage != "ReadyForProduction"
    if stage == "ReadyForMixer":
        batch["mixerId"] = machine["id"]
    elif stage == "ReadyForProduction":
        batch["productionId"] = machine["id"]
    batch["cycleId"] = cycle
    batch["stage"] = "Mixing" if mixing else "Producing"
    number = (batch["mixed"] if mixing else batch["produced"]) + 1
    log.transition("machine_start %s: %s %d on %s, cycle %s, stage %s"
                   % (batch["id"], "mix" if mixing else "production", number, machine["id"],
                      cycle, batch["stage"]))
    return {"success": True,
            "message": "JC %s: mix %d of %d started on %s." % (batch["jobId"], number,
                                                               batch["mixCount"], machine["name"]),
            "targetId": batch["id"], "cycleId": cycle}


def machine_finish(world, log, req, operator_id):
    batch = world.rev2_preparations.get(req.get("targetId") or "")
    if batch is None:
        return _fail("Select an active preparation.")
    if batch["stage"] not in ("Mixing", "Producing") or not batch["cycleId"]:
        log.fail("machine_finish %s: stage %s has no active cycle" % (batch["id"], batch["stage"]))
        return _fail("No machine cycle is running for this preparation.")
    mixing = batch["stage"] == "Mixing"
    active = machine_by_id(world, batch["mixerId"] if mixing else batch["productionId"])
    code = req.get("code") if isinstance(req.get("code"), str) else ""
    if active is None or code != active["code"]:
        log.fail("machine_finish %s: code %r is not the active machine" % (batch["id"], code))
        return _fail("Finish the running cycle on its own machine%s."
                     % (" (%s)" % active["code"] if active else ""))
    cycle = req.get("cycleId")
    if cycle != batch["cycleId"]:
        log.fail("machine_finish %s: cycle %r is not the active cycle" % (batch["id"], cycle))
        return _fail("That cycle is not the active cycle. Read the saved state and use its "
                     "current cycle ID.")
    mixer = machine_by_id(world, batch["mixerId"])
    fixed = mixer is not None and mixer["kind"] == "FixedPair"
    if mixing:
        batch["mixed"] += 1
        if fixed:
            batch["produced"] += 1
        if batch["mixed"] < batch["mixCount"]:
            batch["stage"] = "ReadyForNextMix"
        elif fixed:
            batch["stage"] = "Completed"
        else:
            batch["stage"] = "ReadyForProduction"
    else:
        batch["produced"] += 1
        batch["stage"] = "ReadyForProduction" if batch["produced"] < batch["mixCount"] else "Completed"
    batch["cycleId"] = None
    log.transition("machine_finish %s: cycle %s on %s done, mixed %d produced %d of %d, stage %s"
                   % (batch["id"], cycle, active["id"], batch["mixed"], batch["produced"],
                      batch["mixCount"], batch["stage"]))
    return {"success": True,
            "message": "JC %s: %d/%d mixed; %d/%d finished through production."
                       % (batch["jobId"], batch["mixed"], batch["mixCount"], batch["produced"],
                          batch["mixCount"]),
            "targetId": batch["id"], "cycleId": cycle}


EXECUTORS = {"prepare": prepare, "capture": capture, "adjust_ingredient": adjust_ingredient,
             "confirm": confirm, "machine_start": machine_start,
             "machine_finish": machine_finish}


def _round_half_away(value):
    return int(value + 0.5) if value >= 0 else -int(-value + 0.5)


def eligible_order(world, log, job_card):
    """The SAP order for an eligible JC (section 4: Standard, Planned/Released, FAC, positive
    outstanding output), else None."""
    if not DIGITS.match(job_card):
        log.fail("lookup '%s': not a digit-only Production Order number" % job_card)
        return None
    order = world.sap_orders.get(job_card)
    if (not order
            or order.get("ProductionOrderType") != "bopotStandard"
            or order.get("ProductionOrderStatus") not in OPEN_STATUSES
            or order.get("Warehouse") != "FAC"
            or (order.get("PlannedQuantity") or 0) <= (order.get("CompletedQuantity") or 0)):
        log.fail("lookup %s: not an open Standard FAC order with outstanding output" % job_card)
        return None
    return order


def other_mode_rejection(job, mode):
    """A JC saved in the other process mode rejects (section 4)."""
    return _fail("JC %s is saved for %s. Open it from the %s workflow."
                 % (job["id"], job["mode"], job["mode"]))


def lookup(world, log, job_card):
    held = world.rev2_jobs.get(job_card)
    if held is not None and held["mode"] != "General":
        log.fail("lookup %s: saved in %s mode" % (job_card, held["mode"]))
        return other_mode_rejection(held, "General")
    if held is not None:
        log.step("lookup %s: already held, earlier progress preserved" % job_card)
        return {"success": True, "message": "JC loaded.", "targetId": job_card, "cycleId": None}
    order = eligible_order(world, log, job_card)
    if order is None:
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
                "managerReviewDesktopOnly": True, "jobMixProgress": True,
                "androidBadgeLogin": True}


def _collected_all(p):
    """Rev2JobProgress: a positive included row exists and every included row is collected."""
    included = [m for m in p["materials"] if not m["excluded"]]
    return (any(_dec(m["required"]) > 0 for m in included)
            and all(_dec(m["collected"]) >= _dec(m["required"]) for m in included))


def mix_progress(job, preparations):
    """Contract §8.1, from the simulator's saved preparations."""
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
        "collectedMixes": sum(p["mixCount"] for p in preps if _collected_all(p)),
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
    """Contract section 8 material: the stored row plus originalRequired, remaining and isModified
    (excluded, or the effective target differs from a known original; added rows have 0)."""
    original = m.get("originalRequired", m["required"])
    modified = bool(m["excluded"]) or (original is not None and _dec(m["required"]) != _dec(original))
    return dict(m, originalRequired=original, remaining=_remaining(m), isModified=modified)


def preparation_dto(p):
    return dict(p, materials=[material_dto(m) for m in p["materials"]])


def snapshot(world, target_id, device_id=None, command_id=None):
    preparation = world.rev2_preparations.get(target_id) if target_id else None
    job_id = preparation["jobId"] if preparation else target_id
    job = world.rev2_jobs.get(job_id) if job_id else None
    if job is not None and job["mode"] != "General":
        job = None   # a Rajoo JC is not part of the General snapshot
    if job is None:
        preparation = None
    preparations = list(world.rev2_preparations.values())
    # Rev2ScannerProcessor: the selected scope's records (the PREP's, or a JC's own batch-less
    # records), and the subset this device's command (or the recovered original) created.
    selected = [e for e in world.rev2_exceptions
                if job is not None and e["jobId"] == job["id"]
                and e["batchId"] == (preparation["id"] if preparation else None)]
    catalog = world.ingredient_exceptions
    return {
        "recovery": None,
        "mode": "General",
        "capabilities": CAPABILITIES,
        "jobs": [{"id": j["id"], "product": j["product"], "closed": j["closed"],
                  "requiredMixes": j["requiredMixes"],
                  "mixProgress": mix_progress(j, preparations)} for j in world.rev2_jobs.values()
                 if j["mode"] == "General"],
        "job": None if job is None else dict(
            job,
            materials=[material_dto(m) for m in job["materials"]],
            # Contract §8: non-cancelled preparations only — the same total as mixProgress.
            allocatedMixes=sum(p["mixCount"] for p in preparations
                               if p["jobId"] == job["id"] and p["stage"] != "Cancelled"),
            collectionRevision=0,
            mixProgress=mix_progress(job, preparations)),
        "preparation": preparation_dto(preparation) if preparation else None,
        # Contract 8 (21 Oct): active work only, plus the exact selected PREP when it is history.
        "preparations": [preparation_dto(p) for p in preparations
                         if p["stage"] not in ("Completed", "Cancelled")
                         or (preparation is not None and p["id"] == preparation["id"])],
        "machines": [dict(m, destinations=list(m["destinations"])) for m in world.rev2_machines],
        "exceptionListRevision": catalog["revision"],
        "ingredientExceptions": catalog["products"],
        "requiredIngredientChoices": (matching_exceptions(world, job)
                                      if job is not None and preparation is None else []),
        "collectionExceptions": selected,
        "commandExceptions": [e for e in selected
                              if e["messageId"] == command_id and e["device"] == device_id],
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
            "ingredientChoices": [{"code": "1500000331", "include": True}],
            "exceptionListRevision": 1, "collectionRevision": 0,
            "mixerId": None, "productionId": None, "cycleId": None,
            "startedAtUtc": iso(utc_now()), "confirmedAtUtc": None, "startedBy": "SEEDED",
            "materials": effective_rows(world.rev2_jobs["510019068"], 2),
        }
        log.transition("seed: job 510019068 held with preparation PREP_demo0001")
