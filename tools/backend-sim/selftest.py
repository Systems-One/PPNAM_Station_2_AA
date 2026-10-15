"""rev2.1 conformance self-test: a fake handheld driving the simulator through login and the
General read/lookup slice, plus envelope negative probes.

    python selftest.py --direct                 # in-process, no broker needed
    python selftest.py [--host …] [--port …]    # over MQTT against a running sim

Exits 0 when every check passes, 1 on the first deviation.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import queue
import re
import sys
import time
import unicodedata
import uuid
from datetime import datetime, timezone
from decimal import Decimal

DEVICE = "scanner_selftest"
CHECKS = {"passed": 0}
TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")
REPLY_KEYS = {"schemaVersion", "contractRevision", "deviceId", "inResponseToMessageId", "requestFingerprint",
              "receivedAtUtc", "sentAtUtc", "durationMs", "success", "error", "operatorMessage", "nextAction", "data"}


def now_iso():
    now = datetime.now(timezone.utc)
    return "%s.%06dZ" % (now.strftime("%Y-%m-%dT%H:%M:%S"), now.microsecond)


def check(cond, label, detail=""):
    if cond:
        CHECKS["passed"] += 1
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label}  {detail}")
        sys.exit(1)


class Handheld:
    """Over MQTT against a running simulator."""

    def __init__(self, host, port):
        import paho.mqtt.client as mqtt
        self.rx = queue.Queue()
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=DEVICE,
                                  protocol=mqtt.MQTTv5)
        self.client.on_message = lambda c, u, m: self.rx.put(m)
        self.client.connect(host, port, keepalive=30)
        self.client.loop_start()
        # sim.py defaults to a remote broker and this script to localhost: a mismatch used to
        # surface as a misleading FAIL on the first check. Require the sim's retained presence.
        self.client.subscribe([("PPNAM/station_2", 1), (f"PPNAM/station_2/{DEVICE}/res/+", 1)])
        deadline, presence = time.time() + 3, None
        while time.time() < deadline and presence != "online":
            try:
                m = self.rx.get(timeout=0.2)
            except queue.Empty:
                continue
            if m.topic == "PPNAM/station_2":
                presence = m.payload.decode(errors="replace")
        self.client.unsubscribe("PPNAM/station_2")
        self.pushes = []
        if presence != "online":
            print(f"no simulator is online on {host}:{port} (station presence: {presence or 'none'}).\n"
                  f"Start it on the same broker, e.g.:\n"
                  f"  python sim.py --host {host} --port {port} --transport tcp --no-tls --username \"\"\n"
                  f"or run this self-test in-process with --direct.")
            sys.exit(2)
        self.session = ""

    def send_raw(self, suffix, text):
        self.client.publish(f"PPNAM/station_2/{DEVICE}/req/{suffix}", text, qos=1)

    def invalidate(self):
        self.client.publish("PPNAM/_sim/control", json.dumps({"cmd": "invalidate"}), qos=1)

    def control(self, cmd):
        self.client.publish("PPNAM/_sim/control", json.dumps(cmd), qos=1)
        time.sleep(0.3)

    def close(self):
        self.client.loop_stop()
        self.client.disconnect()

    # ---- shared ----
    def envelope(self, fields, msg_id=None, schema="rev2.1", timestamp=None, session=True):
        body = dict(fields)
        body.update({"schemaVersion": schema, "deviceId": DEVICE,
                     "messageId": msg_id or uuid.uuid4().hex, "timestampUtc": timestamp or now_iso()})
        if session and self.session:
            body["sessionId"] = self.session
        return body

    def request(self, suffix, fields=None, **kw):
        body = self.envelope(fields or {}, **kw)
        self.send_raw(suffix, json.dumps(body))
        return self.await_reply(body["messageId"]), body

    def await_reply(self, msg_id, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                m = self.rx.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                break
            r = json.loads(m.payload)
            if m.topic.endswith("/res/active_job_cards_invalidated"):
                self.pushes.append(r)
            if r.get("inResponseToMessageId") == msg_id:
                r["_topic"] = m.topic
                r["_raw"] = m.payload.decode("utf-8")
                return r
        return None

    def await_push(self, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                m = self.rx.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                break
            if m.topic.endswith("/res/active_job_cards_invalidated"):
                return json.loads(m.payload)
        return None

    def scram_login(self, username, password, purpose="login"):
        client_nonce = base64.b64encode(os.urandom(18)).decode("ascii")
        r, _ = self.request("scram_start_requested",
                            {"username": username, "clientNonce": client_nonce, "purpose": purpose},
                            session=False)
        if not r or not r["success"]:
            return r
        ch = r["data"]
        salt = base64.b64decode(ch["salt"])
        normalized = unicodedata.normalize("NFKC", password).encode("utf-8")
        salted = hashlib.pbkdf2_hmac("sha256", normalized, salt, ch["iterations"])
        client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
        stored_key = hashlib.sha256(client_key).digest()
        escaped = username.replace("=", "=3D").replace(",", "=2C")
        client_final = f"c=biws,r={ch['serverNonce']}"
        auth_message = f"n={escaped},r={client_nonce},{ch['serverFirstMessage']},{client_final}".encode()
        signature = hmac.new(stored_key, auth_message, hashlib.sha256).digest()
        proof = bytes(a ^ b for a, b in zip(client_key, signature))
        pr, _ = self.request("scram_proof_requested", {
            "challengeId": ch["challengeId"], "clientFinalWithoutProof": client_final,
            "clientProof": base64.b64encode(proof).decode("ascii"), "purpose": purpose,
        }, session=False)
        if pr and pr["success"]:
            server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
            expected = base64.b64encode(
                hmac.new(server_key, auth_message, hashlib.sha256).digest()).decode("ascii")
            pr["_serverSignatureValid"] = hmac.compare_digest(expected, pr["data"]["serverSignature"])
        return pr


class _FakeMsg:
    def __init__(self, topic, payload):
        self.topic = topic
        self.payload = payload.encode() if isinstance(payload, str) else payload


class _FakeClient:
    def __init__(self, rx):
        self.rx = rx

    def publish(self, topic, payload=None, qos=0, retain=False, properties=None):
        if "/res/" in topic:
            self.rx.put(_FakeMsg(topic, payload))

        class _R:
            def wait_for_publish(self, timeout=None):
                pass
        return _R()

    def subscribe(self, *a, **k):
        pass


class DirectHandheld(Handheld):
    """In-process: send_raw calls handle_request directly, as the worker thread would."""

    def __init__(self):
        import sim as sim_module
        args = argparse.Namespace(host="direct", port=0, transport="tcp", ws_path="/mqtt",
                                  tls=False, username="", password="", window=None,
                                  tolerance=None, yield_to_real=False, no_color=True,
                                  demo_collections=True)
        self.sim = sim_module.Simulator(args)
        self.rx = queue.Queue()
        self.sim.client = _FakeClient(self.rx)
        self.session = ""
        self.pushes = []
        print(f"direct mode: simulator in-process, logs at {self.sim.log.run_dir}")

    def send_raw(self, suffix, text):
        self.sim.handle_request(DEVICE, suffix, text.encode())

    def invalidate(self):
        self.sim.publish_invalidations()

    def control(self, cmd):
        self.sim._apply_control(json.dumps(cmd).encode())

    def close(self):
        self.sim.log.close()


def check_reply_shape(r, label):
    check(r is not None, f"{label}: a reply arrived")
    check(REPLY_KEYS <= set(r), f"{label}: reply carries every rev2.1 envelope field",
          sorted(REPLY_KEYS - set(r)))
    check("messageId" not in r, f"{label}: a direct reply carries no messageId of its own")
    check(r["schemaVersion"] == "rev2.1", f"{label}: schemaVersion is rev2.1")
    check(TS_RE.match(r["sentAtUtc"] or "") is not None, f"{label}: sentAtUtc has six fractional digits")
    check(isinstance(r["durationMs"], (int, float)), f"{label}: durationMs is numeric")


def fingerprint(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest().upper()


def collected(data, code):
    return next(m["collected"] for m in data["preparation"]["materials"] if m["code"] == code)


PREP = "PREP_demo0001"
PAL_001 = "300833B2DDD9014000000001"   # PAL-001, 1600000301 HD WHITE, 25 kg bags


def receipts(hh):
    """Task 11: receipts, identical replay, recover with sealing, the drop_reply fault."""
    print("receipts")
    general = "rev2_general_requested"
    direct = isinstance(hh, DirectHandheld)
    stock_before = hh.sim.world.pallets[PAL_001]["remainingQuantity"] if direct else None

    first, body = hh.request(general, {"action": "capture", "targetId": PREP, "code": PAL_001})
    check(first and first["success"], "a whole-bag capture against the seeded preparation succeeds",
          first and first["operatorMessage"])
    hh.send_raw(general, json.dumps(body))
    replay = hh.await_reply(body["messageId"])
    check(replay and replay["success"] and replay["data"]["result"] == first["data"]["result"],
          "an identical capture replay returns the saved result")
    check(collected(replay["data"], "1600000301") == collected(first["data"], "1600000301") == 25,
          "…and collects once", collected(replay["data"], "1600000301"))
    if direct:
        stock = hh.sim.world.pallets[PAL_001]["remainingQuantity"]
        check(stock == stock_before - 25, "…and deducts the pallet stock once", stock)

    hh.send_raw(general, json.dumps(dict(body, quantity=1)))
    r = hh.await_reply(body["messageId"])
    check(r and r["error"] == "message_id_conflict", "the same id with a changed body -> message_id_conflict",
          r and r["error"])

    r, _ = hh.request(general, {"action": "recover", "originalMessageId": body["messageId"],
                                "originalRequestFingerprint": fingerprint(json.dumps(body))})
    check(r and r["success"] and r["nextAction"] == "review_recovered_outcome",
          "recover succeeds with nextAction review_recovered_outcome", r and r["error"])
    check(r["data"]["recovery"]["outcome"] == "committed", "recover of a committed capture -> committed",
          r["data"]["recovery"])
    check(r["data"]["recovery"]["result"] == first["data"]["result"]
          and r["data"]["recovery"]["originalMessageId"] == body["messageId"],
          "…carrying the saved result and the original id")
    check(r["data"]["preparation"]["id"] == PREP, "…with a snapshot of the saved result's target")

    late = json.dumps(hh.envelope({"action": "capture", "targetId": PREP, "code": PAL_001}))
    late_id = json.loads(late)["messageId"]
    r, _ = hh.request(general, {"action": "recover", "originalMessageId": late_id,
                                "originalRequestFingerprint": fingerprint(late)})
    check(r and r["success"] and r["data"]["recovery"]["outcome"] == "not_executed",
          "recover of an unknown id -> not_executed", r and r["data"])
    check(r["data"]["recovery"]["result"].get("errorCode") == "receipt_sealed",
          "…and the sealed result carries errorCode receipt_sealed")
    hh.send_raw(general, late)
    r = hh.await_reply(late_id)
    check(r and r["error"] == "receipt_sealed", "a later delivery of the sealed id -> receipt_sealed",
          r and r["error"])
    check(collected(r["data"], "1600000301") == 25, "…and captures nothing", collected(r["data"], "1600000301"))

    own_id = uuid.uuid4().hex
    r, _ = hh.request(general, {"action": "recover", "originalMessageId": own_id,
                                "originalRequestFingerprint": "A" * 64}, msg_id=own_id)
    check(r and r["error"] == "invalid_envelope", "recover with originalMessageId == messageId -> invalid_envelope",
          r and r["error"])
    r, _ = hh.request(general, {"action": "recover", "originalMessageId": uuid.uuid4().hex,
                                "originalRequestFingerprint": "XYZ"})
    check(r and r["error"] == "invalid_envelope", "recover with a malformed fingerprint -> invalid_envelope")

    hh.control({"cmd": "drop_reply", "match": general, "count": 1})
    dropped = json.dumps(hh.envelope({"action": "capture", "targetId": PREP, "code": PAL_001}))
    dropped_id = json.loads(dropped)["messageId"]
    hh.send_raw(general, dropped)
    check(hh.await_reply(dropped_id, timeout=1) is None, "drop_reply: no reply is published")
    if direct:
        check((DEVICE, general, dropped_id) in hh.sim.world.rev2_receipts, "…yet the receipt is saved")
    r, _ = hh.request(general, {"action": "recover", "originalMessageId": dropped_id,
                                "originalRequestFingerprint": fingerprint(dropped)})
    check(r and r["success"] and r["data"]["recovery"]["outcome"] == "committed",
          "…and recover finds it committed")

    mgr = hh.scram_login("manager1", "secret")
    check(mgr and mgr["success"], "manager1 signs in on the same device")
    hh.session = mgr["data"]["session"]["sessionId"]
    r, _ = hh.request(general, {"action": "recover", "originalMessageId": body["messageId"],
                                "originalRequestFingerprint": fingerprint(json.dumps(body))})
    check(r and r["error"] == "receipt_owner_mismatch", "recover by another operator -> receipt_owner_mismatch",
          r and r["error"])
    op = hh.scram_login("operator1", "pass")
    check(op and op["success"], "operator1 signs in again")
    hh.session = op["data"]["session"]["sessionId"]


JOB = "510019068"
ADDITIVE = "1500000331"                 # the catalog's exception code, on job 510019068's BOM
# One usable pallet per included material of job 510019068.
PALLET_FOR = {
    "1500000326": "300833B2DDD9014000000009",   # PAL-009
    "1600000301": PAL_001,                      # PAL-001
    "1600000217": "300833B2DDD9014000000002",   # PAL-002 (no bag weight: the 25 kg default)
    "1600000070": "300833B2DDD901400000000C",   # PAL-012
    "1600000233": "300833B2DDD901400000000A",   # PAL-010
    "1600000309": "300833B2DDD9014000000008",   # PAL-008
}
PAL_003_BLOCKED = "300833B2DDD9014000000003"
PAL_007_ADDITIVE = "300833B2DDD9014000000007"


def exact(r):
    """The reply's data parsed with Decimal numbers, to check quantities exactly."""
    return json.loads(r["_raw"], parse_float=Decimal)["data"]


def row(data, code):
    return next(m for m in data["preparation"]["materials"] if m["code"] == code)


def collection(hh):
    """Task 12: the exception catalog, Include/Exclude choices, prepare, capture and confirm."""
    print("collection")
    general = "rev2_general_requested"

    r, _ = hh.request(general, {"action": "lookup", "jobCard": JOB})
    check([c["code"] for c in r["data"]["requiredIngredientChoices"]] == [ADDITIVE],
          "lookup lists the BOM's catalog code under requiredIngredientChoices",
          r["data"]["requiredIngredientChoices"])
    revision = r["data"]["exceptionListRevision"]
    check(revision == 1 and r["data"]["ingredientExceptions"][0]["code"] == ADDITIVE,
          "…with the catalog and its revision")
    available = r["data"]["job"]["mixProgress"]["availableToPrepareMixes"]

    r, _ = hh.request(general, {"action": "prepare", "targetId": JOB, "mixCount": 1,
                                "exceptionListRevision": revision})
    check(r and r["error"] == "rev2_rejected" and ADDITIVE in r["operatorMessage"],
          "prepare without choices -> rev2_rejected naming the code", r and r["operatorMessage"])
    choices = [{"code": ADDITIVE, "include": False}]
    r, _ = hh.request(general, {"action": "prepare", "targetId": JOB, "mixCount": 1,
                                "exceptionListRevision": revision + 1, "ingredientChoices": choices})
    check(r and r["error"] == "rev2_rejected" and "changed" in r["operatorMessage"],
          "prepare with a stale revision -> rev2_rejected", r and r["operatorMessage"])
    r, _ = hh.request(general, {"action": "prepare", "targetId": JOB, "mixCount": 1, "includeTackifier": False,
                                "exceptionListRevision": revision, "ingredientChoices": choices})
    check(r and r["error"] == "rev2_rejected", "prepare with includeTackifier false -> rev2_rejected")
    check(r["data"]["job"]["mixProgress"]["availableToPrepareMixes"] == available,
          "…and no rejected prepare allocated anything")

    hh.pushes.clear()
    r, body = hh.request(general, {"action": "prepare", "targetId": JOB, "mixCount": 2,
                                   "exceptionListRevision": revision, "ingredientChoices": choices,
                                   "reason": "Without the additive"})
    check(r and r["success"] and r["data"]["result"]["targetId"].startswith("PREP_"),
          "prepare 2 excluding the additive succeeds", r and r["operatorMessage"])
    prep_id = r["data"]["result"]["targetId"]
    data = exact(r)
    prep = data["preparation"]
    check(prep["id"] == prep_id and prep["stage"] == "Collecting" and prep["collectionRevision"] == 0
          and prep["startedBy"] == "OP-001" and prep["mixCount"] == 2,
          "…the preparation is Collecting at revision 0, started by the operator")
    additive = row(data, ADDITIVE)
    check(additive["excluded"] and additive["required"] == 0
          and additive["originalRequired"] == additive["perMix"] * 2,
          "…the excluded row keeps its original quantity at required 0", additive)
    resin = row(data, "1600000301")
    check(resin["required"] == resin["originalRequired"] == resin["perMix"] * 2 and not resin["excluded"],
          "…included rows are perMix x N exactly", resin)
    omitted = [e for e in data["commandExceptions"] if e["kind"] == "Ingredient omitted"]
    check(len(omitted) == 1 and omitted[0]["messageId"] == body["messageId"]
          and omitted[0]["batchId"] == prep_id and omitted[0]["operatorId"] == "OP-001"
          and omitted[0]["reason"] == "Without the additive" and ADDITIVE in omitted[0]["details"],
          "…an Ingredient omitted exception is recorded for the command", data["commandExceptions"])
    check(data["requiredIngredientChoices"] == [], "…a selected preparation needs no choices")
    check(data["job"]["mixProgress"]["availableToPrepareMixes"] == available - 2,
          "…available to prepare drops by 2", data["job"]["mixProgress"]["availableToPrepareMixes"])
    check([p["reason"] for p in hh.pushes] == ["preparation_created"],
          "…and a preparation_created hint is pushed", [p["reason"] for p in hh.pushes])
    r, _ = hh.request(general, {"action": "prepare", "targetId": JOB, "mixCount": available - 1,
                                "exceptionListRevision": revision, "ingredientChoices": choices})
    check(r and r["error"] == "rev2_rejected" and str(available - 2) in r["operatorMessage"],
          "a second prepare above available -> rev2_rejected", r and r["operatorMessage"])

    def capture(code, quantity=None, target=None):
        fields = {"action": "capture", "targetId": target or prep_id, "code": code}
        if quantity is not None:
            fields["quantity"] = quantity
        reply, _ = hh.request(general, fields)
        return reply

    r = capture(PAL_001)
    check(r and r["success"] and row(exact(r), "1600000301")["collected"] == 25,
          "capture a whole bag of PAL-001 collects 25", r and r["operatorMessage"])
    check(exact(r)["preparation"]["collectionRevision"] == 1, "…and increments collectionRevision")
    active = {a["id"]: a for a in r["data"]["job"]["mixProgress"]["activePreparations"]}
    check(active[prep_id]["collectionRevision"] == 1,
          "…mixProgress carries the preparation's collectionRevision", active[prep_id])
    r = capture(PAL_001, 0.5)
    check(r and r["success"] and row(exact(r), "1600000301")["collected"] == Decimal("25.5")
          and row(exact(r), "1600000301")["remaining"] == row(exact(r), "1600000301")["required"] - Decimal("25.5"),
          "a part bag of 0.5 is exact", r and row(exact(r), "1600000301"))
    check(re.search(r"[:,\[]\s*-?[0-9]+(\.[0-9]+)?[eE]", r["_raw"]) is None, "…and every number is written without an exponent")

    hh.pushes.clear()
    r = capture(PALLET_FOR["1500000326"], 20)
    check(r and r["error"] == "rev2_rejected", "a part bag above the remaining target -> rev2_rejected")
    rejected = [e for e in r["data"]["commandExceptions"] if e["kind"] == "Rejected collection action"]
    check(len(rejected) == 1 and rejected[0]["batchId"] == prep_id,
          "…its exception appears in commandExceptions", r["data"]["commandExceptions"])
    check(any(e["id"] == rejected[0]["id"] for e in r["data"]["collectionExceptions"]),
          "…and in the preparation's collectionExceptions")
    check([p["reason"] for p in hh.pushes] == ["collection_exception_recorded"],
          "…with a collection_exception_recorded hint", [p["reason"] for p in hh.pushes])
    r = capture(PAL_003_BLOCKED)
    check(r and r["error"] == "rev2_rejected" and "Holding or Mixing" in r["operatorMessage"],
          "a blocked pallet (PAL-003) -> rev2_rejected", r and r["operatorMessage"])
    r = capture(PAL_007_ADDITIVE)
    check(r and r["error"] == "rev2_rejected" and "not outstanding" in r["operatorMessage"],
          "an excluded material's pallet (PAL-007) -> rev2_rejected", r and r["operatorMessage"])

    r, _ = hh.request(general, {"action": "confirm", "targetId": prep_id})
    check(r and r["error"] == "rev2_rejected"
          and any(e["kind"] == "Rejected collection action" for e in r["data"]["commandExceptions"]),
          "confirm before everything is collected -> rev2_rejected, recorded")

    hh.pushes.clear()
    data = exact(r)
    for _ in range(200):
        todo = [m for m in data["preparation"]["materials"] if not m["excluded"] and m["remaining"] > 0]
        if not todo:
            break
        m = todo[0]
        r = capture(PALLET_FOR[m["code"]], None if m["remaining"] >= 25 else float(m["remaining"]))
        check(r and r["success"], f"capture {m['code']} toward completion", r and r["operatorMessage"])
        data = exact(r)
    check(data["preparation"]["stage"] == "AwaitingConfirmation",
          "capture to completion -> AwaitingConfirmation", data["preparation"]["stage"])
    check(all(m["remaining"] == 0 for m in data["preparation"]["materials"]), "…with nothing remaining")
    check(set(p["reason"] for p in hh.pushes) == {"capture"}, "…each capture pushed a capture hint")
    progress = data["job"]["mixProgress"]
    check(progress["collectedMixes"] == 2, "…collectedMixes counts the complete preparation",
          progress["collectedMixes"])

    r, _ = hh.request(general, {"action": "confirm", "targetId": prep_id})
    check(r and r["success"] and r["data"]["preparation"]["stage"] == "ReadyForMixer"
          and r["data"]["preparation"]["confirmedAtUtc"], "confirm -> ReadyForMixer",
          r and r["operatorMessage"])
    progress = r["data"]["job"]["mixProgress"]
    check(progress["confirmedMixes"] == 2 and progress["collectedMixes"] == 2,
          "…confirmedMixes and collectedMixes are updated", progress)

    r, _ = hh.request(general, {"action": "read", "targetId": PREP})
    check(r and r["success"] and len(r["data"]["preparation"]["materials"]) == 7,
          "read PREP_demo0001 has materials", r and r["data"]["preparation"])
    return prep_id


PAL_013_PIGMENT = "300833B2DDD901400000000D"   # PAL-013, 1500000400 BLUE PIGMENT, 20 kg bags, not on the BOM
PIGMENT = "1500000400"


def identify_and_add(hh):
    """The proposed `identify` action and `adjust_ingredient` kind Add."""
    print("identify and add")
    general = "rev2_general_requested"
    direct = isinstance(hh, DirectHandheld)
    choices = [{"code": ADDITIVE, "include": False}]
    r, _ = hh.request(general, {"action": "read", "targetId": JOB})
    revision = r["data"]["exceptionListRevision"]
    r, _ = hh.request(general, {"action": "prepare", "targetId": JOB, "mixCount": 1,
                               "exceptionListRevision": revision, "ingredientChoices": choices})
    check(r and r["success"], "prepare 1 mix for the identify/add checks", r and r["operatorMessage"])
    prep_id = r["data"]["result"]["targetId"]

    def identify(code, target=prep_id):
        reply, _ = hh.request(general, {"action": "identify", "targetId": target, "code": code})
        return reply

    receipts_before = len(hh.sim.world.rev2_receipts) if direct else None
    exceptions_before = len(exact(r)["collectionExceptions"])
    r = identify(PAL_001)
    check(r and r["success"] and r["error"] == "", "identify a known pallet succeeds", r and r["operatorMessage"])
    source = exact(r)["source"]
    check(source == {"code": PAL_001, "materialCode": "1600000301", "name": "HD WHITE", "unit": "kg",
                     "bagSize": Decimal(25), "available": source["available"], "usable": True, "reason": ""},
          "…source carries code, materialCode, name, unit, bagSize, available, usable, reason", source)
    check(exact(r)["preparation"]["id"] == prep_id and r["data"]["result"]["targetId"] == prep_id,
          "…and the normal snapshot for targetId")
    check(re.search(r'"bagSize":\s*25(\.0)?[,}]', r["_raw"]) is not None, "…with bagSize a JSON number")
    check(identify("  " + PAL_001.lower() + " ")["data"]["source"]["materialCode"] == "1600000301",
          "identify trims and ignores case")
    check(identify(PAL_013_PIGMENT)["data"]["source"]["bagSize"] == 20, "a pigment pallet has its own bagSize (20)")
    check(identify("PAL-002")["data"]["source"]["bagSize"] is None, "an unconfigured pallet has bagSize null")

    r = identify("NO-SUCH-CODE")
    check(r and not r["success"] and r["error"] == "unknown_source" and r["data"]["source"] is None
          and r["data"]["result"]["errorCode"] == "unknown_source",
          "identify an unknown code -> unknown_source with source null", r and r["error"])
    r = identify(PAL_003_BLOCKED)
    s = r["data"]["source"]
    check(r["success"] and s["usable"] is False and "blocked" in s["reason"].lower()
          and s["materialCode"] == "1600000070",
          "identify a blocked pallet -> usable false with a reason", s)
    r = identify("300833B2DDD9014000000004")
    check(r["success"] and r["data"]["source"]["usable"] is False and r["data"]["source"]["reason"],
          "identify a pallet at Station 1 -> usable false with a reason", r["data"]["source"])
    r = identify(PAL_001, target="PREP_nope")
    check(r and not r["success"] and r["data"]["source"] is None, "identify against an unknown preparation is rejected")

    if direct:
        check(len(hh.sim.world.rev2_receipts) == receipts_before, "identify saves no receipt")
    r, _ = hh.request(general, {"action": "read", "targetId": prep_id})
    check(len(r["data"]["collectionExceptions"]) == exceptions_before
          and r["data"]["preparation"]["collectionRevision"] == 0,
          "identify recorded no exception and did not move the revision")

    def add(revision, code=PAL_013_PIGMENT, quantity=30, reason="Customer asked for blue", kind="Add"):
        fields = {"action": "adjust_ingredient", "targetId": prep_id, "collectionRevision": revision,
                  "reason": reason, "adjustment": {"kind": kind, "sourceCode": code,
                                                   "targetQuantity": quantity}}
        return hh.request(general, fields)

    r, _ = add(0, kind="Omit")
    check(r and r["error"] == "rev2_rejected" and "Add" in r["operatorMessage"],
          "adjust_ingredient with a kind other than Add -> definite rejection", r and r["operatorMessage"])
    r, _ = add(0, reason="   ")
    check(r and r["error"] == "rev2_rejected", "Add with a blank reason -> rejected")
    r, _ = add(0, reason="x" * 1001)
    check(r and r["error"] == "rev2_rejected", "Add with a 1001-character reason -> rejected")
    r, _ = add(0, quantity=0)
    check(r and r["error"] == "rev2_rejected", "Add with a zero targetQuantity -> rejected")
    r, _ = add(0, code="NO-SUCH-CODE")
    check(r and r["error"] == "rev2_rejected", "Add from an unknown source -> rejected")
    r, _ = add(0, code=PAL_003_BLOCKED)
    check(r and r["error"] == "rev2_rejected", "Add from a blocked pallet -> rejected")
    r, _ = add(0, code=PAL_001)
    check(r and r["error"] == "rev2_rejected" and "already" in r["operatorMessage"],
          "Add of a material already a row -> rejected", r and r["operatorMessage"])
    r, _ = add(0, code=PAL_007_ADDITIVE)
    check(r and r["error"] == "rev2_rejected" and "already" in r["operatorMessage"],
          "Add of an excluded row's material -> rejected too", r and r["operatorMessage"])
    check(r["data"]["preparation"]["collectionRevision"] == 0
          and len(r["data"]["preparation"]["materials"]) == 7,
          "…rejections leave the rows and revision unchanged")

    r, _ = add(5)
    check(r and r["error"] == "collection_revision_conflict" and r["nextAction"] == "read_and_review_decision",
          "Add with a stale revision -> collection_revision_conflict", r and r["error"])
    rejected = [e for e in r["data"]["commandExceptions"] if e["kind"] == "Rejected collection action"]
    check(len(rejected) == 1 and "adjust_ingredient" in rejected[0]["details"]
          and r["data"]["preparation"]["collectionRevision"] == 0,
          "…records a rejected exception and returns the current snapshot", r["data"]["commandExceptions"])

    hh.pushes.clear()
    r, body = add(0, reason="  Customer asked for blue  ")
    check(r and r["success"], "Add of a new material at the current revision succeeds", r and r["operatorMessage"])
    data = exact(r)
    added = row(data, PIGMENT)
    check(added["perMix"] == 0 and added["originalRequired"] == 0 and added["required"] == 30
          and added["collected"] == 0 and added["remaining"] == 30 and not added["excluded"],
          "…an effective row: perMix 0, originalRequired 0, required = targetQuantity, collected 0", added)
    check(data["preparation"]["collectionRevision"] == 1, "…collectionRevision increments")
    ev = [e for e in data["commandExceptions"] if e["kind"] == "Ingredient added"]
    check(len(ev) == 1 and ev[0]["reason"] == "Customer asked for blue" and ev[0]["messageId"] == body["messageId"]
          and ev[0]["operatorId"] == "OP-001" and PIGMENT in ev[0]["details"],
          "…an Ingredient added exception is recorded with the trimmed reason", data["commandExceptions"])
    check([p["reason"] for p in hh.pushes] == ["adjust_ingredient"], "…an adjust_ingredient hint is pushed",
          [p["reason"] for p in hh.pushes])
    if direct:
        check(hh.sim.world.pallets[PAL_013_PIGMENT]["remainingQuantity"] == 100, "…and no stock is deducted")

    hh.send_raw(general, json.dumps(body))
    replay = hh.await_reply(body["messageId"])
    check(replay and replay["success"] and replay["data"]["result"] == r["data"]["result"]
          and len(replay["data"]["preparation"]["materials"]) == 8
          and replay["data"]["preparation"]["collectionRevision"] == 1,
          "an identical Add replay returns the saved result and adds nothing twice")
    r, _ = add(1)
    check(r and r["error"] == "rev2_rejected" and "already" in r["operatorMessage"],
          "a second Add of the same material -> rejected")

    def capture(code, quantity=None):
        fields = {"action": "capture", "targetId": prep_id, "code": code}
        if quantity is not None:
            fields["quantity"] = quantity
        reply, _ = hh.request(general, fields)
        return reply

    r = capture(PAL_013_PIGMENT)
    check(r and r["success"] and row(exact(r), PIGMENT)["collected"] == 20,
          "capture of the added source with no quantity records one bag (20 kg)", r and r["operatorMessage"])
    check(r["data"]["preparation"]["collectionRevision"] == 2, "…and increments collectionRevision again")
    r, _ = add(1, code=PAL_013_PIGMENT)
    check(r and r["error"] == "collection_revision_conflict", "a decision on the pre-capture revision is now stale")

    data = exact(r)
    for _ in range(200):
        todo = [m for m in data["preparation"]["materials"]
                if not m["excluded"] and m["remaining"] > 0 and m["code"] != PIGMENT]
        if not todo:
            break
        m = todo[0]
        r = capture(PALLET_FOR[m["code"]], None if m["remaining"] >= 25 else float(m["remaining"]))
        check(r and r["success"], f"capture {m['code']} toward completion", r and r["operatorMessage"])
        data = exact(r)
    check(data["preparation"]["stage"] == "Collecting" and row(data, PIGMENT)["remaining"] == 10,
          "every original row collected but the added row has 10 kg left: still Collecting",
          data["preparation"]["stage"])
    r, _ = hh.request(general, {"action": "confirm", "targetId": prep_id})
    check(r and r["error"] == "rev2_rejected", "confirm is refused while the added row is outstanding")
    r = capture(PAL_013_PIGMENT, 10)
    check(r and r["success"] and r["data"]["preparation"]["stage"] == "AwaitingConfirmation",
          "capturing the last 10 kg of the added row -> AwaitingConfirmation", r and r["operatorMessage"])
    r, _ = hh.request(general, {"action": "confirm", "targetId": prep_id})
    check(r and r["success"] and r["data"]["preparation"]["stage"] == "ReadyForMixer",
          "confirm succeeds once the added row is collected too", r and r["operatorMessage"])
    revision = r["data"]["preparation"]["collectionRevision"]
    r, _ = add(revision, code=PAL_013_PIGMENT)
    check(r and r["error"] == "rev2_rejected", "Add after confirmation -> rejected")


def machines(hh, confirmed):
    """machine_start / machine_finish: routes, rejections, replay, drop_reply and recover."""
    print("machines")
    general = "rev2_general_requested"
    direct = isinstance(hh, DirectHandheld)

    def start(prep, code, **kw):
        return hh.request(general, {"action": "machine_start", "targetId": prep, "code": code}, **kw)

    def finish(prep, code, cycle, **kw):
        return hh.request(general, {"action": "machine_finish", "targetId": prep, "code": code,
                                    "cycleId": cycle}, **kw)

    def injected(pid, mixes, stage="ReadyForMixer"):
        hh.sim.world.rev2_preparations[pid] = dict(
            hh.sim.world.rev2_preparations["PREP_demo0001"], id=pid, mixCount=mixes, stage=stage,
            mixed=0, produced=0, mixerId=None, productionId=None, cycleId=None, materials=[])
        return pid

    def rejected(r, text, label):
        check(r and not r["success"] and r["error"] == "rev2_rejected" and text in r["operatorMessage"],
              label, r and r["operatorMessage"])

    r, _ = hh.request(general, {"action": "read", "targetId": confirmed})
    machines_list = r["data"]["machines"]
    by_id = {m["id"]: m for m in machines_list}
    check(all(set(m) == {"id", "name", "area", "code", "enabled", "kind", "isProduction", "pairId",
                         "destinations"} for m in machines_list),
          "snapshot machines use the contract machine DTO fields")
    check(by_id["DOL-MIX-01"]["pairId"] == "DOL-01" and by_id["DOL-01"]["isProduction"],
          "DOL-MIX-01 pairs with DOL-01")
    check(by_id["MAIN-MIX-03"]["destinations"] == ["MAIN-EXT-%02d" % n for n in range(1, 26)]
          and by_id["MAIN-MIX-06"]["destinations"] == ["JAN-02", "JAN-03", "JAN-04"],
          "Main mixers feed MAIN-EXT-01..25; Mixer 6 feeds JAN-02..04")
    check(any(not m["enabled"] for m in machines_list) and any(m["code"] == "" for m in machines_list),
          "the seed has a disabled machine and one with a blank code")

    # -- Main route over the wire on the real, confirmed preparation (2 mixes) --------------------
    check(r["data"]["preparation"]["stage"] == "ReadyForMixer", "the confirmed preparation is ReadyForMixer")
    rejected(start(confirmed, "NOPE-01")[0], "not a configured machine", "an unknown machine code -> rejected")
    rejected(start(confirmed, "")[0], "not a configured machine", "a blank scanned code -> rejected")
    rejected(start(confirmed, "DOL-MIX-03")[0], "disabled", "a disabled machine -> rejected")
    rejected(start(confirmed, "DOL-MIX-04")[0], "not a configured machine", "a blank-code machine cannot be scanned")
    rejected(start(confirmed, "MAIN-EXT-01")[0], "production machine", "a production machine at ReadyForMixer -> rejected")
    rejected(finish(confirmed, "MAIN-MIX-01", "CYC_x")[0], "No machine cycle", "machine_finish at ReadyForMixer -> rejected")
    hh.pushes.clear()
    r, body = start(confirmed, "MAIN-MIX-01")
    prep = r["data"]["preparation"]
    cyc1 = r["data"]["result"]["cycleId"]
    check(r["success"] and prep["stage"] == "Mixing" and prep["mixerId"] == "MAIN-MIX-01"
          and cyc1.startswith("CYC_") and prep["cycleId"] == cyc1,
          "machine_start MAIN-MIX-01 -> Mixing with a server cycleId", r["operatorMessage"])
    check([p["reason"] for p in hh.pushes] == ["machine_start"], "…and a machine_start hint is pushed",
          [p["reason"] for p in hh.pushes])
    hh.pushes.clear()
    hh.send_raw(general, json.dumps(body))
    replay = hh.await_reply(body["messageId"])
    check(replay and replay["data"]["result"]["cycleId"] == cyc1 and replay["data"]["preparation"]["cycleId"] == cyc1
          and not hh.pushes, "an exact machine_start replay returns the same receipt, no new cycle, no hint")
    rejected(start(confirmed, "MAIN-MIX-01")[0], "cannot be started", "machine_start while Mixing -> rejected")
    rejected(finish(confirmed, "MAIN-MIX-02", cyc1)[0], "own machine", "finish with the wrong machine code -> rejected")
    rejected(finish(confirmed, "MAIN-MIX-01", "CYC_" + "0" * 32)[0], "not the active cycle",
             "finish with the wrong cycle -> rejected")
    rejected(finish(confirmed, "main-mix-01", cyc1)[0], "own machine", "the finish code is exact (case-sensitive)")
    r, body = finish(confirmed, "MAIN-MIX-01", cyc1)
    prep = r["data"]["preparation"]
    check(r["success"] and prep["stage"] == "ReadyForNextMix" and prep["mixed"] == 1 and prep["produced"] == 0
          and prep["cycleId"] is None, "machine_finish mix 1 of 2 -> ReadyForNextMix, mixed 1", r["operatorMessage"])
    hh.send_raw(general, json.dumps(body))
    replay = hh.await_reply(body["messageId"])
    check(replay and replay["data"]["preparation"]["mixed"] == 1, "an exact machine_finish replay does not double-count")
    rejected(start(confirmed, "MAIN-MIX-02")[0], "assigned mixer", "the next mix must use the saved mixer")
    r, _ = start(confirmed, "MAIN-MIX-01")
    cyc2 = r["data"]["result"]["cycleId"]
    check(r["success"] and cyc2 != cyc1 and r["data"]["preparation"]["stage"] == "Mixing",
          "ReadyForNextMix -> Mixing with a new cycleId")
    r, _ = finish(confirmed, "MAIN-MIX-01", cyc2)
    prep = r["data"]["preparation"]
    check(r["success"] and prep["stage"] == "ReadyForProduction" and prep["mixed"] == 2,
          "the last mix finish -> ReadyForProduction", prep["stage"])
    rejected(start(confirmed, "MAIN-MIX-01")[0], "production machine", "a mixer at ReadyForProduction -> rejected")
    rejected(start(confirmed, "JAN-02")[0], "not a permitted destination", "JAN-02 is not a Main destination")
    r, _ = start(confirmed, "MAIN-EXT-07")
    cyc3 = r["data"]["result"]["cycleId"]
    prep = r["data"]["preparation"]
    check(r["success"] and prep["stage"] == "Producing" and prep["productionId"] == "MAIN-EXT-07"
          and prep["mixerId"] == "MAIN-MIX-01", "production start assigns productionId", r["operatorMessage"])
    progress = r["data"]["job"]["mixProgress"]
    check(progress["mixedMixes"] >= 2 and progress["activePreparations"] != [], "…mixProgress shows the active preparation")
    r, _ = finish(confirmed, "MAIN-EXT-07", cyc3)
    check(r["success"] and r["data"]["preparation"]["stage"] == "ReadyForProduction"
          and r["data"]["preparation"]["produced"] == 1, "production finish 1 of 2 -> ReadyForProduction")
    rejected(start(confirmed, "MAIN-EXT-08")[0], "assigned to production machine MAIN-EXT-07",
             "later production cycles must use the same production machine")
    r, _ = start(confirmed, "MAIN-EXT-07")
    r, _ = finish(confirmed, "MAIN-EXT-07", r["data"]["result"]["cycleId"])
    prep = r["data"]["preparation"]
    check(r["success"] and prep["stage"] == "Completed" and prep["produced"] == 2 and prep["cycleId"] is None,
          "the last production finish -> Completed", prep["stage"])
    rejected(start(confirmed, "MAIN-MIX-01")[0], "Completed", "machine_start on a Completed preparation -> rejected")
    r, _ = hh.request(general, {"action": "read", "targetId": confirmed})
    progress = r["data"]["job"]["mixProgress"]
    check(progress["producedMixes"] >= 2 and all(a["id"] != confirmed for a in progress["activePreparations"]),
          "…a Completed preparation leaves activePreparations and counts as produced")

    if not direct:
        print("  (remaining machine checks inject preparations and need --direct)")
        return

    # -- Main 4 mixes then 4 productions ---------------------------------------------------------
    a = injected("PREP_main4", 4)
    for n in range(1, 5):
        r, _ = start(a, "MAIN-MIX-02")
        r, _ = finish(a, "MAIN-MIX-02", r["data"]["result"]["cycleId"])
        check(r["success"] and r["data"]["preparation"]["mixed"] == n, "Main mix %d of 4 finished" % n)
    prep = r["data"]["preparation"]
    check(prep["stage"] == "ReadyForProduction" and prep["produced"] == 0,
          "Main: 4 mixes -> ReadyForProduction, none produced")
    for n in range(1, 5):
        r, _ = start(a, "MAIN-EXT-02")
        r, _ = finish(a, "MAIN-EXT-02", r["data"]["result"]["cycleId"])
        check(r["success"] and r["data"]["preparation"]["produced"] == n, "Main production %d of 4 finished" % n)
    check(r["data"]["preparation"]["stage"] == "Completed", "Main: 4 productions -> Completed")

    # -- occupancy -------------------------------------------------------------------------------
    b = injected("PREP_occ1", 1)
    c = injected("PREP_occ2", 1)
    r, _ = start(b, "MAIN-MIX-03")
    cb = r["data"]["result"]["cycleId"]
    rejected(start(c, "MAIN-MIX-03")[0], "occupied", "a mixer held by another active preparation -> occupied")
    r, _ = finish(b, "MAIN-MIX-03", cb)
    rejected(start(c, "MAIN-MIX-03")[0], "occupied", "…it stays occupied while the other awaits production")
    r, _ = start(b, "MAIN-EXT-10")
    cb = r["data"]["result"]["cycleId"]
    r, _ = start(c, "MAIN-MIX-04")
    r, _ = finish(c, "MAIN-MIX-04", r["data"]["result"]["cycleId"])
    rejected(start(c, "MAIN-EXT-10")[0], "occupied", "a production machine held by another preparation -> occupied")
    r, _ = finish(b, "MAIN-EXT-10", cb)
    check(r["data"]["preparation"]["stage"] == "Completed", "the first preparation completes")
    r, _ = start(c, "MAIN-EXT-10")
    check(r["success"], "…once Completed, its production machine is free again", r["operatorMessage"])

    # -- Mixer 6 -> JAN only ---------------------------------------------------------------------
    m6 = injected("PREP_mix6", 1)
    r, _ = start(m6, "MAIN-MIX-06")
    r, _ = finish(m6, "MAIN-MIX-06", r["data"]["result"]["cycleId"])
    check(r["data"]["preparation"]["stage"] == "ReadyForProduction", "Mixer 6 mix -> ReadyForProduction")
    rejected(start(m6, "MAIN-EXT-01")[0], "not a permitted destination", "Mixer 6 cannot feed a Main extruder")
    r, _ = start(m6, "JAN-03")
    check(r["success"] and r["data"]["preparation"]["productionId"] == "JAN-03", "Mixer 6 -> JAN-03 starts")
    r, _ = finish(m6, "JAN-03", r["data"]["result"]["cycleId"])
    check(r["data"]["preparation"]["stage"] == "Completed", "Mixer 6 route completes")

    # -- FixedPair: each mix finish increments mixed and produced --------------------------------
    f = injected("PREP_pair", 2)
    rejected(start(f, "DOL-01")[0], "production machine", "a FixedPair production code at ReadyForMixer -> rejected")
    r, _ = start(f, "DOL-MIX-01")
    r, _ = finish(f, "DOL-MIX-01", r["data"]["result"]["cycleId"])
    prep = r["data"]["preparation"]
    check(prep["mixed"] == 1 and prep["produced"] == 1 and prep["stage"] == "ReadyForNextMix",
          "FixedPair finish 1 of 2 -> mixed 1, produced 1, ReadyForNextMix")
    r, _ = start(f, "DOL-MIX-01")
    r, _ = finish(f, "DOL-MIX-01", r["data"]["result"]["cycleId"])
    prep = r["data"]["preparation"]
    check(prep["mixed"] == 2 and prep["produced"] == 2 and prep["stage"] == "Completed" and prep["cycleId"] is None,
          "FixedPair finish 2 of 2 -> Completed")
    check(all(x["id"] != f for x in r["data"]["job"]["mixProgress"]["activePreparations"]),
          "…and leaves activePreparations")
    g = injected("PREP_mack", 1)
    r, _ = start(g, "MAC-MIX-01")
    r, _ = finish(g, "MAC-MIX-01", r["data"]["result"]["cycleId"])
    check(r["data"]["preparation"]["stage"] == "Completed", "a Mackie pair completes after its one mix")

    # -- drop_reply then recover -----------------------------------------------------------------
    d = injected("PREP_drop", 1)
    hh.control({"cmd": "drop_reply", "match": general, "count": 1})
    raw = json.dumps(hh.envelope({"action": "machine_start", "targetId": d, "code": "BUB-MIX-01"}))
    mid = json.loads(raw)["messageId"]
    hh.send_raw(general, raw)
    check(hh.await_reply(mid, timeout=1) is None, "drop_reply: the machine_start reply is lost")
    saved = hh.sim.world.rev2_preparations[d]
    check(saved["stage"] == "Mixing" and saved["cycleId"], "…yet the cycle started")
    r, _ = hh.request(general, {"action": "recover", "originalMessageId": mid,
                                "originalRequestFingerprint": fingerprint(raw)})
    check(r and r["success"] and r["data"]["recovery"]["outcome"] == "committed"
          and r["data"]["recovery"]["result"]["cycleId"] == saved["cycleId"],
          "recover finds the machine_start committed, with its cycleId")
    hh.send_raw(general, raw)
    again = hh.await_reply(mid)
    check(again and again["data"]["result"]["cycleId"] == saved["cycleId"],
          "a later delivery of the same machine_start replays the saved result")
    check(not [e for e in hh.sim.world.rev2_exceptions if e["batchId"] in (a, b, c, d, f, g, m6)],
          "machine rejections record no exceptions")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--direct", action="store_true")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=1883)
    args = parser.parse_args()
    hh = DirectHandheld() if args.direct else Handheld(args.host, args.port)

    print("envelope")
    mid = uuid.uuid4().hex
    hh.send_raw("job_card_load_requested", json.dumps(hh.envelope({}, msg_id=mid)))
    # The server rejects an unknown suffix before reading the body, so the reply is uncorrelated.
    r = hh.await_reply("")
    check(r and r["error"] == "client_upgrade_required", "a retired 4.1 suffix -> client_upgrade_required")
    check(r["_topic"].endswith("/res/rev2_general_result"), "…answered on rev2_general_result")

    r, _ = hh.request("rev2_general_requested", {"action": "read"}, schema="4.1")
    check(r and r["error"] == "invalid_envelope", "schemaVersion 4.1 -> invalid_envelope")

    r, _ = hh.request("rev2_general_requested", {"action": "read"},
                      timestamp=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    check(r and r["error"] == "invalid_envelope", "a timestamp without six fractional digits -> invalid_envelope")

    r, _ = hh.request("rev2_general_requested", {"action": "read"}, msg_id="x" * 101)
    check(r and r["error"] == "invalid_envelope", "a 101-character messageId -> invalid_envelope")

    r, _ = hh.request("scram_start_requested", {"username": "operator1", "clientNonce": "n",
                                                "purpose": "login", "Password": "x"}, session=False)
    check(r and r["error"] == "password_field_forbidden", "any field containing 'password' -> password_field_forbidden")

    body = hh.envelope({"action": "read"})
    hh.send_raw("rev2_general_requested", json.dumps(body)[:-1] + ',"ACTION":"read"}')
    r = hh.await_reply("")
    check(r and r["error"] == "invalid_envelope", "a case-insensitive duplicate field -> invalid_envelope")
    check(r["inResponseToMessageId"] == "", "…uncorrelatable, as on the server")

    # The limit is 65,536 UTF-8 BYTES (contract §2): ~33k two-byte characters exceed it.
    big = json.dumps(hh.envelope({"action": "read", "targetId": "é" * 33000}), ensure_ascii=False)
    hh.send_raw("rev2_general_requested", big)
    r = hh.await_reply("")
    check(r and r["error"] == "invalid_envelope",
          "a request over 65,536 UTF-8 bytes (but under 65,536 characters) -> invalid_envelope",
          f"{len(big)} chars, {len(big.encode())} bytes")

    print("directory")
    r, body = hh.request("operator_list_requested", session=False)
    check_reply_shape(r, "operator list")
    check(r["success"] and r["_topic"].endswith("/res/operator_list"),
          "a bare-envelope operator_list_requested answers on operator_list")
    ops = r["data"]["operators"]
    check([o["username"] for o in ops] == ["manager1", "operator1"], "data.operators lists the seeded SCRAM users, sorted by displayName")
    check(all(set(o) == {"username", "displayName"} for o in ops), "each entry carries exactly username and displayName")
    hh.send_raw("operator_list_requested", json.dumps(body))
    replay = hh.await_reply(body["messageId"])
    check(replay and replay["data"] == r["data"], "an identical directory request replays the stored reply")

    print("auth")
    r = hh.scram_login("operator1", "pass", purpose="manager_action")
    check(r and r["error"] == "purpose_not_enabled", "a manager_action purpose -> purpose_not_enabled")
    r = hh.scram_login("operator1", "wrong")
    check(r and r["error"] == "authentication_failed", "a wrong password -> authentication_failed")
    r = hh.scram_login("operator1", "pass")
    check_reply_shape(r, "scram proof")
    check(r["success"], "a correct password signs in")
    check(r.get("_serverSignatureValid"), "the server signature verifies")
    session = r["data"]["session"]
    check(session["sessionId"] and session["isActive"], "data.session carries an active sessionId")
    check("allowedTabs" not in json.dumps(r), "login returns no allowedTabs")
    hh.session = session["sessionId"]

    start = {"username": "operator1", "clientNonce": "fixed-nonce", "purpose": "login"}
    first, body = hh.request("scram_start_requested", start, session=False)
    hh.send_raw("scram_start_requested", json.dumps(body))
    replay = hh.await_reply(body["messageId"])
    check(replay and replay["data"]["challengeId"] == first["data"]["challengeId"],
          "an identical auth request replays the stored reply")
    changed = dict(body, clientNonce="other-nonce")
    hh.send_raw("scram_start_requested", json.dumps(changed))
    r = hh.await_reply(body["messageId"])
    check(r and r["error"] == "message_id_conflict", "the same auth messageId with a new body -> message_id_conflict")

    print("general")
    r, _ = hh.request("rev2_general_requested", {"action": "read"}, session=False)
    check(r and r["error"] == "operator_session_invalid", "read without a session -> operator_session_invalid")

    r, _ = hh.request("rev2_general_requested", {"action": "machine_start", "targetId": "PREP_demo0001",
                                                 "code": "MAIN-MIX-01"})
    check(r and r["error"] == "rev2_rejected" and "Collecting" in r["operatorMessage"],
          "machine_start on a Collecting preparation -> rev2_rejected", r and r["operatorMessage"])
    r, _ = hh.request("rev2_general_requested", {"action": "teleport"})
    check(r and r["error"] == "action_not_allowed", "an unknown action -> action_not_allowed")

    r, _ = hh.request("rev2_general_requested", {"action": "read"})
    check_reply_shape(r, "read")
    check(r["success"] and r["error"] == "", "read succeeds with an empty error")
    ids = [j["id"] for j in r["data"]["jobs"]]
    check("510019068" in ids, "the seeded job is in data.jobs", ids)
    check(r["data"]["job"] is None, "read with no target has no job detail")

    r, _ = hh.request("rev2_general_requested", {"action": "lookup", "jobCard": "510019068"})
    check(r["success"] and r["data"]["job"]["id"] == "510019068", "lookup of an open FAC job succeeds")
    job = r["data"]["job"]
    check(job["requiredMixes"] > 0 and job["materials"], "the job has mixes and materials")
    check(all({"code", "name", "unit", "perMix", "required", "collected", "excluded"} <= set(m)
              for m in job["materials"]), "every material carries the rev2.1 material fields")
    check(r["data"]["result"]["targetId"] == "510019068", "data.result names the target")

    r, _ = hh.request("rev2_general_requested", {"action": "lookup", "jobCard": "510018531"})
    check(not r["success"] and r["error"] == "rev2_rejected", "lookup of a closed order -> rev2_rejected")
    check(isinstance(r["data"]["jobs"], list), "…and still returns the snapshot")

    r, _ = hh.request("rev2_general_requested", {"action": "lookup", "jobCard": "JC-24001"})
    check(r["error"] == "rev2_rejected", "a non-digit job card -> rev2_rejected")

    r, _ = hh.request("rev2_general_requested", {"action": "read", "targetId": "510019068"})
    check(r["data"]["job"]["id"] == "510019068", "read with a target returns that job")
    check(r["data"]["job"]["allocatedMixes"] == 2, "…with allocatedMixes from its preparations")
    check(any(p["jobId"] == "510019068" for p in r["data"]["preparations"]), "…and its preparations")

    if isinstance(hh, DirectHandheld):
        # A cancelled preparation no longer holds mixes (contract §8: non-cancelled only).
        hh.sim.world.rev2_preparations["PREP_cancelled"] = dict(
            hh.sim.world.rev2_preparations["PREP_demo0001"], id="PREP_cancelled", mixCount=3, stage="Cancelled")
        r, _ = hh.request("rev2_general_requested", {"action": "read", "targetId": "510019068"})
        check(r["data"]["job"]["allocatedMixes"] == 2, "job.allocatedMixes excludes cancelled preparations",
              r["data"]["job"]["allocatedMixes"])
        check(r["data"]["job"]["mixProgress"]["allocatedMixes"] == 2, "…as mixProgress.allocatedMixes does")
        del hh.sim.world.rev2_preparations["PREP_cancelled"]

    receipts(hh)
    confirmed = collection(hh)
    identify_and_add(hh)
    machines(hh, confirmed)

    print("push")
    hh.invalidate()
    push = hh.await_push()
    check(push is not None, "active_job_cards_invalidated reaches a device that has read General")
    check(push["mode"] == "General" and push["reason"] == "preparation_created"
          and push["nextAction"] == "read", "the push carries mode, reason and nextAction")
    check(push["messageId"] and "inResponseToMessageId" not in push,
          "the push has its own messageId and no correlation id")

    hh.close()
    print(f"\nall {CHECKS['passed']} checks passed")


if __name__ == "__main__":
    main()
