"""rev2_rajoo_requested -> rev2_rajoo_result: the separate Rajoo job/session family (contract
section 5): `lookup`, `read`, `identify`, `recover`, `rajoo_start`, `capture`,
`adjust_ingredient` (kind Add only), `rajoo_finish` and `rajoo_close`.

Rajoo collects against the FULL Production Order component quantities (no per-mix scaling):
materials have perMix 0, the job has requiredMixes 0, allocatedMixes 0 and mixProgress null. The
JC saves its Rajoo machine at lookup; every session start/finish and the close scan that same
machine. `rajoo_start` returns the server session id `RAJ_<32 hex>` (as `cycleId`) and sets
`job.activeSessionId`; capture and decisions need that exact id. The first session freezes the
per-code Include/Exclude choices (section 7); later sessions omit them.

The shared rules live in rev2_general and are reused here: durable receipts (`mutate`), recovery
(`recover`), exception records, pallet capture rules (`take_bag`), Add validation (`add_row`),
`identify` and the material DTO. Rajoo exceptions carry jobId = JC, batchId null and sessionId =
the active RAJ session. Hints go to the separate Rajoo reader registrations with mode "Rajoo".
"""

import json
import uuid
from decimal import Decimal

from envelope import Rejection
from handlers import rev2_general as g
from state import iso, utc_now

ACTIONS = ("lookup", "read", "identify", "recover", "rajoo_start", "capture",
           "adjust_ingredient", "rajoo_finish", "rajoo_close")
MUTATIONS = ("rajoo_start", "capture", "adjust_ingredient", "rajoo_finish", "rajoo_close")
LOADED = "Rajoo full-job BOM loaded. Scan the same machine to start a session."


def check_action(req):
    """Runs BEFORE session authorization, as on the server."""
    action = req.get("action") or ""
    if action not in ACTIONS:
        raise Rejection("action_not_allowed", "Action is not part of this process mode.")
    return action


def handle(world, log, req, action, request_type, raw, operator_id):
    """Return (result, snapshot, hint_reason), as rev2_general.handle."""
    world.rajoo_readers.add(req["deviceId"])
    recovery, hint = None, None
    if action == "lookup":
        result = lookup(world, log, req)
    elif action == "recover":
        result, recovery = g.recover(world, log, req, request_type, operator_id)
    elif action in MUTATIONS:
        result, hint = g.mutate(world, log, req, action, request_type, raw, operator_id,
                                executors=EXECUTORS, scope=_scope,
                                recorded=("capture", "adjust_ingredient"))
    elif action == "identify":
        result, source = g.identify(world, log, req, target_ok=_rajoo_target,
                                    bad_target="Select a Rajoo job card.")
        # Contract 4.1: compact; Rajoo never has a selected preparation.
        return result, {"result": result, "preparation": None, "source": source}, None
    else:
        result = {"success": True, "message": "Current progress.",
                  "targetId": req.get("targetId") or "", "cycleId": None}
    command_id = recovery["originalMessageId"] if recovery else req["messageId"]
    snap = snapshot(world, result.get("targetId") or req.get("targetId") or None,
                    req["deviceId"], command_id)
    snap["result"] = result
    snap["recovery"] = recovery
    return result, snap, hint


# ---- helpers --------------------------------------------------------------------------------

def _q(value):
    """A decimal for operator text: 100, 12.5 (no exponent, no trailing zeros)."""
    d = g._dec(value)
    text = format(d, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def _rajoo_job(world, target):
    job = world.rev2_jobs.get(target or "")
    return job if job is not None and job["mode"] == "Rajoo" else None


def _rajoo_target(world, target):
    return _rajoo_job(world, target) is not None


def _scope(world, req):
    """(job, batch) for a rejected Rajoo capture/decision record: the JC, no batch."""
    return _rajoo_job(world, req.get("targetId")), None


def _machine_by_code(world, code):
    code = (code if isinstance(code, str) else "").strip().lower()
    if not code:
        return None
    return next((m for m in world.rajoo_machines if m["code"].strip().lower() == code), None)


def _machine_name(world, machine_id):
    m = next((m for m in world.rajoo_machines if m["id"] == machine_id), None)
    return m["name"] if m else machine_id


def _enabled_machine(world, code):
    """(machine, error_message) for a configured, enabled Rajoo machine."""
    machine = _machine_by_code(world, code)
    if machine is None:
        return None, "Scan a configured Rajoo machine."
    if not machine["enabled"]:
        return None, "%s is disabled and cannot be used." % machine["name"]
    return machine, None


def _same_machine(world, job, code, need_enabled=True):
    """Error message unless `code` is the JC's saved Rajoo machine."""
    machine, error = (_enabled_machine(world, code) if need_enabled
                      else (_machine_by_code(world, code), None))
    if machine is None:
        return error or "Scan a configured Rajoo machine."
    if machine["id"] != job["rajooMachineId"]:
        return ("Scan the Rajoo machine saved for this JC (%s)."
                % _machine_name(world, job["rajooMachineId"]))
    return None


def _pascal(materials):
    """beforeJson/afterJson evidence: persisted model names (PascalCase), plain numbers."""
    out = []
    for m in materials:
        dto = g.material_dto(m)
        out.append({k[0].upper() + k[1:]: (_plain(v) if isinstance(v, Decimal) else v)
                    for k, v in dto.items()})
    return json.dumps(out, separators=(",", ":"))


def _plain(d):
    return int(d) if d == d.to_integral_value() else float(d)


def _first_session(world, job):
    return job["ingredientChoices"] is None and not any(
        s["jobId"] == job["id"] for s in world.rajoo_sessions.values())


# ---- lookup ---------------------------------------------------------------------------------

def lookup(world, log, req):
    job_card = str(req.get("jobCard") or "")
    held = world.rev2_jobs.get(job_card)
    if held is not None and held["mode"] != "Rajoo":
        log.fail("rajoo lookup %s: saved in %s mode" % (job_card, held["mode"]))
        return g.other_mode_rejection(held, "Rajoo")
    machine, error = _enabled_machine(world, req.get("code"))
    if error:
        log.fail("rajoo lookup %s: machine %r: %s" % (job_card, req.get("code"), error))
        return g._fail(error)
    if held is not None:
        if held["activeSessionId"] and held["rajooMachineId"] != machine["id"]:
            return g._fail("This JC has an active session on %s. Finish it there first."
                           % _machine_name(world, held["rajooMachineId"]))
        if held["rajooMachineId"] != machine["id"] and not held["closed"]:
            log.transition("rajoo lookup %s: machine context %s -> %s"
                           % (job_card, held["rajooMachineId"], machine["id"]))
            held["rajooMachineId"] = machine["id"]
        log.step("rajoo lookup %s: already held, cumulative progress preserved" % job_card)
        return {"success": True, "message": LOADED, "targetId": job_card, "cycleId": None}
    order = g.eligible_order(world, log, job_card)
    if order is None:
        return g._fail(g.LOOKUP_REJECTION)
    outstanding = order["PlannedQuantity"] - (order.get("CompletedQuantity") or 0)
    materials = []
    for line in order.get("ProductionOrderLines", []):
        if line.get("ProductionOrderIssueType") != "im_Manual":
            continue
        # Full Production Order quantity: outstanding output does not scale Rajoo targets.
        planned = g._dec(line.get("PlannedQuantity") or 0)
        materials.append({
            "code": line["ItemNo"], "name": (line.get("ItemName") or "").strip(),
            "unit": g.UNITS.get(str(line.get("UoMCode")), str(line.get("UoMCode"))),
            "perMix": Decimal(0), "required": planned, "collected": Decimal(0),
            "excluded": False, "originalRequired": planned,
        })
    world.rev2_jobs[job_card] = {
        "id": job_card,
        "product": order.get("ProductDescription") or "",
        "mode": "Rajoo",
        "unit": "each",
        "overallQuantity": order["PlannedQuantity"],
        "scopeQuantity": outstanding,
        "sapCompletedAtLookup": order.get("CompletedQuantity") or 0.0,
        "outputPerMix": world.config["rev2OutputPerMix"],
        "requiredMixes": 0,
        "materials": materials,
        "capturedAtUtc": iso(utc_now()),
        "activeSessionId": None,
        "rajooMachineId": machine["id"],
        "closed": False,
        "ingredientChoices": None,
        "exceptionListRevision": None,
        "collectionRevision": 0,
    }
    log.transition("rajoo lookup %s: job saved locally on %s, %d full-job materials"
                   % (job_card, machine["id"], len(materials)))
    return {"success": True, "message": LOADED, "targetId": job_card, "cycleId": None}


# ---- mutations ------------------------------------------------------------------------------

def rajoo_start(world, log, req, operator_id):
    job = _rajoo_job(world, req.get("targetId"))
    if job is None:
        return g._fail("Load this Rajoo JC first.")
    if job["closed"]:
        return g._fail("This Rajoo JC capture is closed.")
    if job["activeSessionId"]:
        log.fail("rajoo_start %s: session %s is still active" % (job["id"], job["activeSessionId"]))
        return g._fail("A Rajoo session is already active for this JC. Finish it before starting "
                       "another.")
    error = _same_machine(world, job, req.get("code"))
    if error:
        log.fail("rajoo_start %s: %s" % (job["id"], error))
        return g._fail(error)
    busy = next((j for j in world.rev2_jobs.values()
                 if j["mode"] == "Rajoo" and j["id"] != job["id"] and j["activeSessionId"]
                 and j["rajooMachineId"] == job["rajooMachineId"]), None)
    if busy is not None:
        return g._fail("%s already has an active session for JC %s."
                       % (_machine_name(world, job["rajooMachineId"]), busy["id"]))
    first = _first_session(world, job)
    excluded = []
    if first:
        error, choices = g.validate_choices(world, job, req)
        if error:
            log.fail("rajoo_start %s: %s" % (job["id"], error))
            return g._fail(error)
        excluded = [c["code"].lower() for c in choices if c["include"] is False]
    elif req.get("ingredientChoices"):
        return g._fail("The initial ingredient choices are already saved for this JC. Record an "
                       "ingredient decision instead.")
    session_id = "RAJ_" + uuid.uuid4().hex
    world.rajoo_sessions[session_id] = {
        "id": session_id, "jobId": job["id"], "machineId": job["rajooMachineId"],
        "startedAtUtc": iso(utc_now()), "startedBy": operator_id, "finishedAtUtc": None}
    job["activeSessionId"] = session_id
    if first:
        job["ingredientChoices"] = choices
        job["exceptionListRevision"] = world.ingredient_exceptions["revision"]
        for m in job["materials"]:
            if m["code"].lower() in excluded:
                m["required"], m["excluded"] = Decimal(0), True
                g.record_exception(world, req, operator_id, job, None, "Ingredient omitted",
                                   "%s %s: original target %s %s; excluded before collection."
                                   % (m["code"], m["name"], _q(m["originalRequired"]), m["unit"]))
    log.transition("rajoo_start %s: session %s on %s%s"
                   % (job["id"], session_id, job["rajooMachineId"],
                      (", excluded %s" % excluded) if excluded else ""))
    return {"success": True, "message": "Rajoo session started. Scan the source pallet for every bag.",
            "targetId": job["id"], "cycleId": session_id}


def _active_session_error(job, req):
    if job["closed"]:
        return "This Rajoo JC capture is closed."
    if not job["activeSessionId"]:
        return "Start a Rajoo session on the saved machine first."
    if req.get("cycleId") != job["activeSessionId"]:
        return ("That is not the active Rajoo session. Read the saved state and use its current "
                "session ID.")
    return None


def capture(world, log, req, operator_id):
    job = _rajoo_job(world, req.get("targetId"))
    if job is None:
        return g._fail("Load this Rajoo JC first.")
    error = _active_session_error(job, req)
    if error:
        log.fail("rajoo capture %s: %s (cycleId %r)" % (job["id"], error, req.get("cycleId")))
        return g._fail(error)
    error, pallet, material, q = g.take_bag(world, log, job["id"], job["materials"], req)
    if error:
        return g._fail(error)
    job["collectionRevision"] += 1
    log.transition("rajoo capture %s: %s %s %s from %s (pallet now %s), revision %d"
                   % (job["id"], q, material["unit"], material["code"], pallet["palletId"],
                      pallet["remainingQuantity"], job["collectionRevision"]))
    unit = material["unit"]
    return {"success": True,
            "message": "Captured %s %s · %s · %s; total %s / %s %s; remaining %s %s; "
                       "source pallet %s."
                       % (_q(q), unit, material["code"], material["name"],
                          _q(material["collected"]), _q(material["required"]), unit,
                          _q(g._remaining(material)), unit, pallet["palletId"]),
            "targetId": job["id"], "cycleId": job["activeSessionId"]}


def adjust_ingredient(world, log, req, operator_id):
    """Full-job decision in the exact active session, contract section 6: kind Add only."""
    job = _rajoo_job(world, req.get("targetId"))
    if job is None:
        return g._fail("Load this Rajoo JC first.")
    error = _active_session_error(job, req)
    if error:
        log.fail("rajoo adjust_ingredient %s: %s" % (job["id"], error))
        return g._fail(error)
    revision = req.get("collectionRevision")
    if (not isinstance(revision, int) or isinstance(revision, bool)
            or revision != job["collectionRevision"]):
        log.fail("rajoo adjust_ingredient %s: revision %r, current %d"
                 % (job["id"], revision, job["collectionRevision"]))
        return g._fail("The collection changed. Reload its quantities before recording this "
                       "decision.", "collection_revision_conflict")
    reason = req.get("reason")
    reason = reason.strip() if isinstance(reason, str) else ""
    if not 1 <= len(reason) <= 1000:
        return g._fail("Enter a reason of 1 to 1000 characters.")
    error, row, pallet = g.add_row(world, req.get("adjustment"), job["materials"])
    if error:
        return g._fail(error)
    before = _pascal(job["materials"])
    job["materials"].append(row)
    job["collectionRevision"] += 1
    details = ("Different product · Added %s · %s · target 0 → %s %s (+%s %s); "
               "captured 0 %s" % (row["code"], row["name"], _q(row["required"]), row["unit"],
                                  _q(row["required"]), row["unit"], row["unit"]))
    record = g.record_exception(world, req, operator_id, job, None, "Ingredient added", details)
    record["beforeJson"] = before
    record["afterJson"] = _pascal(job["materials"])
    log.transition("rajoo adjust_ingredient %s: added %s target %s from %s, revision %d"
                   % (job["id"], row["code"], row["required"], pallet["palletId"],
                      job["collectionRevision"]))
    return {"success": True, "message": details, "targetId": job["id"],
            "cycleId": job["activeSessionId"]}


def rajoo_finish(world, log, req, operator_id):
    job = _rajoo_job(world, req.get("targetId"))
    if job is None:
        return g._fail("Load this Rajoo JC first.")
    error = _active_session_error(job, req)
    if error is None:
        error = _same_machine(world, job, req.get("code"), need_enabled=False)
    if error:
        log.fail("rajoo_finish %s: %s" % (job["id"], error))
        return g._fail(error)
    session_id = job["activeSessionId"]
    world.rajoo_sessions[session_id]["finishedAtUtc"] = iso(utc_now())
    job["activeSessionId"] = None
    log.transition("rajoo_finish %s: session %s ended; cumulative capture retained"
                   % (job["id"], session_id))
    return {"success": True, "message": "Rajoo session saved. Cumulative JC material progress retained.",
            "targetId": job["id"], "cycleId": session_id}


def rajoo_close(world, log, req, operator_id):
    job = _rajoo_job(world, req.get("targetId"))
    if job is None:
        return g._fail("Load this Rajoo JC first.")
    if job["closed"]:
        return g._fail("This Rajoo JC capture is already closed.")
    error = _same_machine(world, job, req.get("code"))
    if error:
        log.fail("rajoo_close %s: %s" % (job["id"], error))
        return g._fail(error)
    if job["activeSessionId"]:
        return g._fail("Finish the active Rajoo session before closing this JC.")
    outstanding = [m for m in job["materials"] if not m["excluded"] and g._remaining(m) > 0]
    if outstanding or not any(not m["excluded"] and g._dec(m["required"]) > 0
                              for m in job["materials"]):
        log.fail("rajoo_close %s: outstanding %s" % (job["id"], [m["code"] for m in outstanding]))
        return g._fail("Collect every included material before closing this JC. Outstanding: %s."
                       % ", ".join("%s %s %s" % (m["code"], _q(g._remaining(m)), m["unit"])
                                   for m in outstanding))
    job["closed"] = True
    log.transition("rajoo_close %s: local capture closed" % job["id"])
    return {"success": True, "message": "Rajoo JC capture closed locally.", "targetId": job["id"],
            "cycleId": None}


EXECUTORS = {"rajoo_start": rajoo_start, "capture": capture, "adjust_ingredient": adjust_ingredient,
             "rajoo_finish": rajoo_finish, "rajoo_close": rajoo_close}


# ---- snapshot -------------------------------------------------------------------------------

def snapshot(world, target_id, device_id=None, command_id=None):
    job = _rajoo_job(world, target_id)
    selected = [e for e in world.rev2_exceptions if job is not None and e["jobId"] == job["id"]]
    catalog = world.ingredient_exceptions
    return {
        "recovery": None,
        "mode": "Rajoo",
        "capabilities": g.CAPABILITIES,
        "jobs": [{"id": j["id"], "product": j["product"], "closed": j["closed"],
                  "requiredMixes": 0, "mixProgress": None}
                 for j in world.rev2_jobs.values() if j["mode"] == "Rajoo"],
        "job": None if job is None else dict(
            job, materials=[g.material_dto(m) for m in job["materials"]],
            mixProgress=None, allocatedMixes=0),
        "preparation": None,
        "preparations": [],
        "machines": [dict(m, destinations=list(m["destinations"])) for m in world.rajoo_machines],
        "exceptionListRevision": catalog["revision"],
        "ingredientExceptions": catalog["products"],
        "requiredIngredientChoices": (g.matching_exceptions(world, job)
                                      if job is not None and _first_session(world, job) else []),
        "collectionExceptions": selected,
        "commandExceptions": [e for e in selected
                              if e["messageId"] == command_id and e["device"] == device_id],
    }
