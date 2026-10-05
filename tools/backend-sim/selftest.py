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

DEVICE = "scanner_selftest"
CHECKS = {"passed": 0}
TS_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")
REPLY_KEYS = {"schemaVersion", "deviceId", "inResponseToMessageId", "receivedAtUtc", "sentAtUtc",
              "durationMs", "success", "error", "operatorMessage", "nextAction", "data"}


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
        self.client.subscribe([(f"PPNAM/station_2/{DEVICE}/res/+", 1)])
        time.sleep(0.5)
        self.session = ""

    def send_raw(self, suffix, text):
        self.client.publish(f"PPNAM/station_2/{DEVICE}/req/{suffix}", text, qos=1)

    def invalidate(self):
        self.client.publish("PPNAM/_sim/control", json.dumps({"cmd": "invalidate"}), qos=1)

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
            if r.get("inResponseToMessageId") == msg_id:
                r["_topic"] = m.topic
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
        print(f"direct mode: simulator in-process, logs at {self.sim.log.run_dir}")

    def send_raw(self, suffix, text):
        self.sim.handle_request(DEVICE, suffix, text.encode())

    def invalidate(self):
        self.sim.publish_invalidations()

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

    r, _ = hh.request("rev2_general_requested", {"action": "prepare", "targetId": "510019068"})
    check(r and r["error"] == "action_not_allowed", "prepare is outside the slice -> action_not_allowed")
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
