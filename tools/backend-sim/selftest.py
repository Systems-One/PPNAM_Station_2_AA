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

    r, _ = hh.request("rev2_general_requested", {"action": "machine_start", "targetId": "PREP_demo0001"})
    check(r and r["error"] == "action_not_allowed", "machine_start is outside the slice -> action_not_allowed")
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
    collection(hh)

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
