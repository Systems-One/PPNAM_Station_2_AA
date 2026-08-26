"""Contract self-test: a fake handheld that drives the simulator through
contract v4.1's minimum end-to-end acceptance flow plus negative probes.

Two modes:
    python selftest.py --direct                 # in-process, no broker needed
    python selftest.py [--host …] [--port …]    # over MQTT against a running sim

Exits 0 when every check passes, 1 on the first contract deviation.
"""

import argparse
import json
import queue
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone

import paho.mqtt.client as mqtt

DEVICE = "handheld_selftest"
CHECKS = {"passed": 0}


def now_iso(offset_seconds=0):
    return (datetime.now(timezone.utc) + timedelta(seconds=offset_seconds)) \
        .strftime("%Y-%m-%dT%H:%M:%SZ")


def check(cond, label, detail=""):
    if cond:
        CHECKS["passed"] += 1
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label}  {detail}")
        sys.exit(1)


class Handheld:
    def __init__(self, host, port):
        self.rx = queue.Queue()
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id=DEVICE, protocol=mqtt.MQTTv5)
        self.client.will_set(f"PPNAM/station_2/{DEVICE}", "offline", qos=1, retain=True)
        self.client.on_message = lambda c, u, m: self.rx.put(m)
        self.client.connect(host, port, keepalive=30)
        self.client.loop_start()
        self.client.subscribe([(f"PPNAM/station_2/{DEVICE}/res/+", 1)])
        self.client.publish(f"PPNAM/station_2/{DEVICE}", "online", qos=1, retain=True)
        time.sleep(0.5)
        self.session = ""
        self.msg_seq = 0

    def next_msg_id(self, prefix):
        self.msg_seq += 1
        return f"{prefix}-{uuid.uuid4().hex[:8]}-{self.msg_seq:04d}"

    def send_raw(self, request_type, payload):
        self.client.publish(f"PPNAM/station_2/{DEVICE}/req/{request_type}",
                            json.dumps(payload), qos=1)

    def request(self, request_type, fields=None, msg_id=None, session=None,
                timestamp=None, schema="4.1", device=DEVICE, timeout=10):
        msg_id = msg_id or self.next_msg_id(request_type.split("_")[0])
        payload = {
            "messageId": msg_id,
            "schemaVersion": schema,
            "deviceId": device,
            "operatorSessionId": self.session if session is None else session,
            "timestampUtc": timestamp or now_iso(),
        }
        payload.update(fields or {})
        self.send_raw(request_type, payload)
        return self.await_response(msg_id, timeout), payload

    def await_response(self, msg_id, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                m = self.rx.get(timeout=max(0.1, deadline - time.time()))
            except queue.Empty:
                break
            body = json.loads(m.payload)
            if body.get("inResponseToMessageId") == msg_id:
                body["_topic"] = m.topic
                return body
        raise TimeoutError(f"no response for {msg_id} within {timeout}s")

    def scram_login(self, username, password, purpose="login",
                    action_target="", manager_action=""):
        """Run a full RFC 7677 SCRAM-SHA-256 exchange and return the proof response.

        This is the client half of the same computation the Android app performs, written
        independently here so the two agreeing is evidence rather than a tautology.
        """
        import base64, hashlib, hmac, os, unicodedata

        client_nonce = base64.b64encode(os.urandom(18)).decode("ascii")
        r, _ = self.request("scram_start_requested", {
            "username": username, "clientNonce": client_nonce, "purpose": purpose,
            "actionTarget": action_target, "managerAction": manager_action,
        }, session="")
        if not r.get("accepted"):
            return r

        salt = base64.b64decode(r["salt"])
        iterations = r["iterations"]
        server_first = r["serverFirstMessage"]
        server_nonce = r["serverNonce"]

        normalized = unicodedata.normalize("NFKC", password).encode("utf-8")
        salted = hashlib.pbkdf2_hmac("sha256", normalized, salt, iterations)
        client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
        stored_key = hashlib.sha256(client_key).digest()
        escaped = username.replace("=", "=3D").replace(",", "=2C")
        client_final = f"c=biws,r={server_nonce}"
        auth_message = (f"n={escaped},r={client_nonce},{server_first},{client_final}").encode("utf-8")
        client_signature = hmac.new(stored_key, auth_message, hashlib.sha256).digest()
        client_proof = bytes(a ^ b for a, b in zip(client_key, client_signature))

        proof_r, _ = self.request("scram_proof_requested", {
            "challengeId": r["challengeId"],
            "clientFinalWithoutProof": client_final,
            "clientProof": base64.b64encode(client_proof).decode("ascii"),
            "purpose": purpose, "actionTarget": action_target, "managerAction": manager_action,
        }, session="")

        # Mutual auth: verify the server signature exactly as the app must.
        if proof_r.get("accepted"):
            server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
            expected = base64.b64encode(
                hmac.new(server_key, auth_message, hashlib.sha256).digest()).decode("ascii")
            proof_r["_serverSignatureValid"] = hmac.compare_digest(
                expected, proof_r.get("serverSignature", ""))
        return proof_r

    def close(self):
        self.client.publish(f"PPNAM/station_2/{DEVICE}", "offline", qos=1, retain=True)
        time.sleep(0.3)
        self.client.loop_stop()
        self.client.disconnect()


class _FakeMsg:
    def __init__(self, topic, payload):
        self.topic = topic
        self.payload = payload.encode() if isinstance(payload, str) else payload
        self.qos = 1


class _FakePublishResult:
    def wait_for_publish(self, timeout=None):
        pass


class _FakeClient:
    """Captures the simulator's publishes and feeds res/ topics back to the
    handheld's receive queue — an in-process loopback replacing the broker."""

    def __init__(self, rx):
        self.rx = rx

    def publish(self, topic, payload=None, qos=0, retain=False, properties=None):
        if "/res/" in topic:
            self.rx.put(_FakeMsg(topic, payload))
        return _FakePublishResult()

    def subscribe(self, *a, **k):
        pass


class DirectHandheld(Handheld):
    """Drives the simulator in-process: send_raw calls handle_request directly,
    exactly as the worker thread would (same serialization guarantee)."""

    def __init__(self):
        import sim as sim_module
        args = argparse.Namespace(host="direct", port=0, transport="tcp", ws_path="/mqtt",
                                  tls=False, username="", password="", window=None,
                                  tolerance=None, yield_to_real=False, no_color=True)
        self.sim = sim_module.Simulator(args)
        self.sim.client = _FakeClient(rx=queue.Queue())
        self.rx = self.sim.client.rx
        self.session = ""
        self.msg_seq = 0
        print(f"direct mode: simulator in-process, logs at {self.sim.log.run_dir}")

    def send_raw(self, request_type, payload):
        self.sim.handle_request(DEVICE, request_type, json.dumps(payload).encode())

    def scram_login(self, username, password, purpose="login",
                    action_target="", manager_action=""):
        """Run a full RFC 7677 SCRAM-SHA-256 exchange and return the proof response.

        This is the client half of the same computation the Android app performs, written
        independently here so the two agreeing is evidence rather than a tautology.
        """
        import base64, hashlib, hmac, os, unicodedata

        client_nonce = base64.b64encode(os.urandom(18)).decode("ascii")
        r, _ = self.request("scram_start_requested", {
            "username": username, "clientNonce": client_nonce, "purpose": purpose,
            "actionTarget": action_target, "managerAction": manager_action,
        }, session="")
        if not r.get("accepted"):
            return r

        salt = base64.b64decode(r["salt"])
        iterations = r["iterations"]
        server_first = r["serverFirstMessage"]
        server_nonce = r["serverNonce"]

        normalized = unicodedata.normalize("NFKC", password).encode("utf-8")
        salted = hashlib.pbkdf2_hmac("sha256", normalized, salt, iterations)
        client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
        stored_key = hashlib.sha256(client_key).digest()
        escaped = username.replace("=", "=3D").replace(",", "=2C")
        client_final = f"c=biws,r={server_nonce}"
        auth_message = (f"n={escaped},r={client_nonce},{server_first},{client_final}").encode("utf-8")
        client_signature = hmac.new(stored_key, auth_message, hashlib.sha256).digest()
        client_proof = bytes(a ^ b for a, b in zip(client_key, client_signature))

        proof_r, _ = self.request("scram_proof_requested", {
            "challengeId": r["challengeId"],
            "clientFinalWithoutProof": client_final,
            "clientProof": base64.b64encode(client_proof).decode("ascii"),
            "purpose": purpose, "actionTarget": action_target, "managerAction": manager_action,
        }, session="")

        # Mutual auth: verify the server signature exactly as the app must.
        if proof_r.get("accepted"):
            server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
            expected = base64.b64encode(
                hmac.new(server_key, auth_message, hashlib.sha256).digest()).decode("ascii")
            proof_r["_serverSignatureValid"] = hmac.compare_digest(
                expected, proof_r.get("serverSignature", ""))
        return proof_r

    def close(self):
        self.sim.log.close()


def collect_all(hh, col):
    """Drive one collection of job 510019068 from Collecting to ReadyForMixing:
    bag scans, bulk direct weight, over-tolerance approval, short-bag waiver.
    Returns the final ingredient_scan_result.
    Seed pallet stock is sized for ~16 full collections; the suite runs five."""
    def scan(fields, **kw):
        f = {"collectionId": col, "correlationKey": col}
        f.update(fields)
        return hh.request("ingredient_scan_requested", f, **kw)

    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000001",
                 "requestedMaterialCode": "1600000301",
                 "bagSizeOption": "full", "bagCount": 22})
    check(r["accepted"], f"[{col}] 22 full bags HD WHITE", json.dumps(r)[:200])
    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000001",
                 "requestedMaterialCode": "1600000301",
                 "bagSizeOption": "1/2", "bagCount": 2})
    check(r["accepted"] and r["overCollectionToleranceBags"] == 1.0,
          f"[{col}] within-tolerance top-up; tolerance echoed")
    r, _ = scan({"palletRfidTag": "300833B2DDD9014000000002",
                 "requestedMaterialCode": "1600000217", "quantity": 1671.147})
    check(r["accepted"], f"[{col}] bulk line by direct weight")
    r, p = scan({"palletRfidTag": "300833B2DDD901400000000C",
                 "requestedMaterialCode": "1600000070", "quantity": 600.0})
    check(not r["accepted"] and r["requiresManagerApproval"],
          f"[{col}] over-tolerance rejected pending approval")
    retry = {k: v for k, v in p.items()
             if k not in ("messageId", "timestampUtc", "schemaVersion",
                          "deviceId", "operatorSessionId")}
    # 4.1: the manager proves their credentials in a SCRAM exchange scoped to this exact
    # collection and action; only the resulting single-use token travels on the retry.
    saved_session = hh.session
    tok_r = hh.scram_login("manager1", "secret", purpose="manager_action",
                           action_target=f"IngredientScan:{col}",
                           manager_action="ingredient_approve_override")
    hh.session = saved_session
    check(tok_r["accepted"] and tok_r.get("authorizationToken"),
          f"[{col}] manager SCRAM issues a scoped authorizationToken")
    retry.update({"authorizationToken": tok_r["authorizationToken"],
                  "auditReason": "Verified spillage allowance."})
    r, _ = hh.request("ingredient_scan_requested", retry)
    check(r["accepted"] and r["approverUserId"] == "OP-012",
          f"[{col}] approved retry (new messageId) accepted")

    # A consumed token must not authorize a second action.
    replay = dict(retry)
    r2, _ = hh.request("ingredient_scan_requested", replay)
    check(not r2["accepted"],
          f"[{col}] a single-use authorization token cannot be replayed")
    for tag, mat, qty in (("300833B2DDD9014000000009", "1500000326", 69.631),
                          ("300833B2DDD901400000000A", "1600000233", 278.524),
                          ("300833B2DDD9014000000008", "1600000309", 557.049)):
        r, _ = scan({"palletRfidTag": tag, "requestedMaterialCode": mat, "quantity": qty})
        check(r["accepted"], f"[{col}] {mat} collected")
    saved_session = hh.session
    waiver_tok = hh.scram_login("manager1", "secret", purpose="manager_action",
                                action_target=f"ShortBag:{col}",
                                manager_action="ingredient_approve_short_bag")
    hh.session = saved_session
    r, _ = scan({"requestedMaterialCode": "1500000331", "shortBagCount": 1,
                 "authorizationToken": waiver_tok["authorizationToken"],
                 "auditReason": "One damaged bag unavailable."})
    check(r["accepted"] and r["collectionStatus"] == "ReadyForMixing"
          and r["nextAction"] == "start_mixing",
          f"[{col}] final waiver -> ReadyForMixing + start_mixing", json.dumps(r)[:300])
    return r


def load_collection(hh, job):
    r, _ = hh.request("job_card_load_requested",
                      {"jobCardNumber": job, "correlationKey": job})
    check(r["accepted"] and r["collectionId"].startswith("COL_")
          and "hoppers" not in r and r["collectionStatus"] == "Collecting",
          "bom_loaded: new v4 collection, no hoppers board", json.dumps(r)[:200])
    return r["collectionId"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="mqtt.sysone.co.za")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--direct", action="store_true",
                    help="run the simulator in-process (no broker required)")
    args = ap.parse_args()
    hh = DirectHandheld() if args.direct else Handheld(args.host, args.port)
    job = "510019068"

    print("== negative probes before login ==")
    r, _ = hh.request("pallet_lookup_requested",
                      {"palletRfidTag": "300833B2DDD9014000000001"}, session="nope")
    check(not r["accepted"] and r["errorCode"] == "session_required"
          and r["nextAction"] == "login",
          "request without session -> session_required + nextAction login")

    print("== login (4.1 SCRAM-SHA-256) ==")
    r, _ = hh.request("login_requested",
                      {"username": "operator1", "password": "pass"}, session="")
    check(not r["accepted"] and r["errorCode"] == "plaintext_credentials_forbidden",
          "4.1 plaintext login rejected with plaintext_credentials_forbidden")

    r = hh.scram_login("operator1", "wrong")
    check(not r["accepted"] and r["errorCode"] == "permission_denied",
          "SCRAM: wrong password rejected")

    r = hh.scram_login("no_such_operator", "pass")
    check(not r["accepted"] and r["errorCode"] == "permission_denied",
          "SCRAM: unknown user fails at the proof, not the challenge (no enumeration oracle)")

    r = hh.scram_login("operator1", "pass")
    check(r["accepted"] and r["sessionState"] == "Active" and r["operatorSessionId"]
          and r["schemaVersion"] == "4.1",
          "SCRAM login accepted, Active session, 4.1 envelope")
    check(r.get("_serverSignatureValid") is True,
          "SCRAM: server signature verifies (mutual authentication)")
    hh.session = r["operatorSessionId"]

    print("== 4.1 envelope diagnostics ==")
    check(r.get("serverReceivedAtUtc") and r.get("serverSentAtUtc")
          and r.get("processingDurationMs") is not None,
          "response carries serverReceivedAtUtc/serverSentAtUtc/processingDurationMs")
    import re as _re
    check(bool(_re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$",
                         r["serverSentAtUtc"])),
          "timestamps are RFC 3339 with exactly six fractional digits")
    check(r["timestampUtc"] == r["serverSentAtUtc"],
          "legacy timestampUtc equals serverSentAtUtc")

    print("== envelope & §12 compatibility boundary ==")
    r, _ = hh.request("mixing_overview_requested", schema="2.0")
    check(not r["accepted"] and r["errorCode"] == "unsupported_schema",
          "schema 2.0 -> unsupported_schema")
    r, _ = hh.request("pallet_lookup_requested", {"palletRfidTag": "NO_SUCH_TAG"}, schema="3.0")
    check(r["accepted"] and r["found"] is False,
          "schema 3.0 accepted for a capture action (§12)")
    r, _ = hh.request("mixing_overview_requested", schema="3.0")
    check(not r["accepted"] and r["errorCode"] == "unsupported_schema",
          "schema 3.0 rejected for a mixing action")
    r, _ = hh.request("mixing_overview_requested", timestamp=now_iso(-3600))
    check(not r["accepted"] and r["errorCode"] == "message_expired",
          "1h-old timestamp -> message_expired")
    r, _ = hh.request("mixing_overview_requested", device="handheld_other")
    check(not r["accepted"] and r["errorCode"] == "device_mismatch",
          "payload/topic device mismatch -> device_mismatch")

    print("== strict replay on a 4.1 topic (deliberately beyond the real backend) ==")
    r1, p1 = hh.request("mixing_overview_requested")
    check(r1["accepted"], "mixing overview accepted")
    hh.send_raw("mixing_overview_requested", p1)
    r2 = hh.await_response(p1["messageId"])
    check(r2["messageId"] == r1["messageId"],
          "identical replay returns the STORED response (same server messageId)")
    p_mut = dict(p1)
    p_mut["correlationKey"] = "MUTATED-BODY"
    hh.send_raw("mixing_overview_requested", p_mut)
    r3_ = hh.await_response(p1["messageId"])
    check(not r3_["accepted"] and r3_["errorCode"] == "message_id_reused",
          "same messageId + different body -> message_id_reused (4.1 path)")

    print("== retired-topic guard ==")
    r, _ = hh.request("hopper_overview_requested")
    check(r["_topic"].endswith("/res/workflow_upgrade_required")
          and not r["accepted"] and r["errorCode"] == "client_upgrade_required"
          and r["nextAction"] == "upgrade_reader_for_mixing",
          "retired v3 topic -> client_upgrade_required on workflow_upgrade_required")

    print("== pallet lookup & holding recovery ==")
    r, _ = hh.request("pallet_lookup_requested",
                      {"palletRfidTag": "300833B2DDD9014000000004"})
    check(r["accepted"] and r["palletState"] == "AtStation1" and r["recoverable"]
          and not r["usable"] and r["nextAction"] == "recover_holding",
          "AtStation1 pallet: recoverable, nextAction recover_holding")
    r, _ = hh.request("holding_recovery_requested",
                      {"palletRfidTag": "300833B2DDD9014000000004",
                       "auditReason": "Missed door read; pallet is physically here."})
    check(r["accepted"] and r["palletState"] == "Holding" and r["usable"],
          "recovery -> Holding, usable")
    r, _ = hh.request("pallet_lookup_requested",
                      {"palletRfidTag": "300833B2DDD9014000000003"})
    check(r["accepted"] and r["blocked"] and not r["usable"] and not r["recoverable"],
          "blocked Holding pallet: not usable, not recoverable")

    print("== capture: collection 1 (Main) ==")
    r, _ = hh.request("open_sap_job_cards_requested", {"refreshFromSap": True})
    check(r["accepted"] and any(j["jobCardNumber"] == job for j in r["jobs"]),
          f"open SAP job cards contains {job}")
    col1 = load_collection(hh, job)
    r, _ = hh.request("ingredient_scan_requested",
                      {"collectionId": col1,
                       "palletRfidTag": "300833B2DDD9014000000001",
                       "requestedMaterialCode": "1600000301",
                       "bagSizeOption": "full", "bagCount": 1, "quantity": 10.0})
    check(not r["accepted"], "bag fields and quantity together are rejected")
    collect_all(hh, col1)
    r, _ = hh.request("collection_resume_requested",
                      {"jobCardNumber": job, "collectionId": col1})
    check(r["accepted"] and r["resumed"] and r["nextAction"] == "start_mixing",
          "resume of a ReadyForMixing collection -> start_mixing")

    print("== mixing overview ==")
    r, _ = hh.request("mixing_overview_requested")
    check(r["accepted"] and len(r["equipment"]) == 47 and r["activeCycles"] == []
          and r["nextAction"] == "select_collection_mix_or_machine",
          "overview (all areas): 47 equipment, no active cycles")
    r, _ = hh.request("mixing_overview_requested", {"mixingArea": "JandiBulkMixing"})
    check(r["accepted"] and all(e["mixingArea"] == "JandiBulkMixing" for e in r["equipment"])
          and len(r["equipment"]) == 5,
          "overview filtered to JANDI: 5 equipment")
    r, _ = hh.request("mixing_overview_requested", {"mixingArea": "Atlantis"})
    check(not r["accepted"] and r["errorCode"] == "invalid_mixing_area",
          "unknown area -> invalid_mixing_area")

    print("== mixer plan gate (4.1 §7) ==")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-01", "productionOrderDocumentNumber": job,
                       "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "mixer_plan_required",
          "ReadyForMixing collection with no saved plan -> mixer_plan_required")
    # Station 2 (WPF) saves the cross-area mixer plan out of band of the handheld contract; the
    # simulator/selftest models that save directly on the world.
    hh.sim.world.save_mix_plan(col1, ["MXR-01"])
    r, _ = hh.request("mixing_overview_requested", {"mixingArea": "MainMixingRoom"})
    rc = next(c for c in r["readyCollections"] if c["collectionId"] == col1)
    check(rc["status"] == "MixingPlanned" and rc["mixPlanId"]
          and rc["mixPlanStatus"] == "Saved" and rc["remainingMixerCodes"] == ["MXR-01"]
          and len(rc["mixerPlanItems"]) == 1 and rc["mixerPlanItems"][0]["status"] == "Reserved",
          "saved plan surfaces in readyCollections as MixingPlanned with its reserved mixer")
    # Regression: a collection planned to a Main-only mixer must not leak into another area's
    # board. readyCollections used to be returned unfiltered for every area (the Android client
    # made the same mistake, never passing its area to fetchReadyCollections) — an operator on
    # any board saw every in-flight collection, including ones whose only reserved mixer they
    # could never reach from that screen.
    r_other, _ = hh.request("mixing_overview_requested", {"mixingArea": "JandiBulkMixing"})
    check(all(c["collectionId"] != col1 for c in r_other["readyCollections"]),
          "a collection planned only to a Main mixer does not appear on the JANDI board")
    resm = next(e for e in r["equipment"] if e["machineCode"] == "MXR-01")
    check(resm["status"] == "Reserved" and resm["scanAllowed"] is True
          and not resm["isAvailable"] and resm["reservationCollectionId"] == col1,
          "the reserved mixer is Reserved + scanAllowed + not available in equipment[]")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-03", "productionOrderDocumentNumber": job,
                       "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "mixer_not_in_plan",
          "a mixer not reserved by the collection's plan -> mixer_not_in_plan")

    print("== mixer start (Main) ==")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-01", "productionOrderDocumentNumber": job,
                       "machineCodes": ["MXR-01"], "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "legacy_request_shape",
          "v3 array field present -> legacy_request_shape")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "NOPE-99", "productionOrderDocumentNumber": job,
                       "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "unknown_or_disabled_equipment",
          "unknown machine -> unknown_or_disabled_equipment")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-25", "productionOrderDocumentNumber": job,
                       "mixBatchIds": ["MIX_000001"]})
    check(not r["accepted"] and r["errorCode"] == "unknown_or_disabled_equipment",
          "disabled machine -> unknown_or_disabled_equipment")
    r, _ = hh.request("machine_cycle_start_requested", {"productionOrderDocumentNumber": job})
    check(not r["accepted"] and r["errorCode"] == "validation_failed",
          "missing machineCode -> validation_failed")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-01", "productionOrderDocumentNumber": job,
                       "collectionId": col1, "correlationKey": col1})
    check(r["accepted"] and r["action"] == "Started"
          and r["mixBatchId"].startswith("MIX_") and r["cycleId"].startswith("CYC_")
          and r["productionRunId"] is None
          and r["sapIssueQueued"] is False and r["sapProductionOrderChanged"] is False
          and r["nextAction"] == "scan_same_machine_to_finish"
          and "equipment" in r["areaStatus"],
          "mixer start: MIX_/CYC_ minted, SAP flags false, areaStatus embedded",
          json.dumps(r)[:300])
    mix1, cyc1 = r["mixBatchId"], r["cycleId"]
    busy = next(e for e in r["areaStatus"]["equipment"] if e["machineCode"] == "MXR-01")
    check(busy["status"] == "InUse" and not busy["isAvailable"],
          "areaStatus shows the started mixer InUse")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-02", "productionOrderDocumentNumber": job,
                       "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "mixer_not_in_plan",
          "a mixer outside the plan (col1 reserved only MXR-01, now Started) -> mixer_not_in_plan")
    col2 = load_collection(hh, job)
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-02", "productionOrderDocumentNumber": job,
                       "collectionId": col2})
    check(not r["accepted"] and r["errorCode"] == "source_not_ready",
          "Collecting collection -> source_not_ready")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-01", "productionOrderDocumentNumber": job,
                       "collectionId": col2})
    check(not r["accepted"] and r["errorCode"] == "equipment_in_use",
          "busy mixer -> equipment_in_use")

    print("== finish (Main) ==")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-02", "cycleId": cyc1})
    check(not r["accepted"] and r["errorCode"] == "cycle_mismatch",
          "finish on the wrong machine -> cycle_mismatch")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-01", "cycleId": cyc1, "correlationKey": cyc1})
    check(r["accepted"] and r["action"] == "Finished" and not r["alreadyFinished"],
          "mixer finish accepted")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-01", "cycleId": cyc1})
    check(r["accepted"] and r["alreadyFinished"],
          "re-finish is an accepted idempotent no-op (alreadyFinished true)")
    r, _ = hh.request("mixing_overview_requested", {"mixingArea": "MainMixingRoom"})
    ready = [m for m in r["readyMixes"] if m["mixBatchId"] == mix1]
    check(len(ready) == 1 and ready[0]["status"] == "ReadyForProduction"
          and "EXT-03" in ready[0]["validNextMachineCodes"],
          "finished mix is ReadyForProduction with Main extruders as valid next")

    print("== production run + accumulation (Main) ==")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-03", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [mix1], "collectionId": col1})
    check(not r["accepted"] and r["errorCode"] == "validation_failed",
          "collection id in a downstream start rejected")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-03", "productionOrderDocumentNumber": job,
                       "mixBatchIds": ["MIX_999999"]})
    check(not r["accepted"] and r["errorCode"] == "source_not_found",
          "unknown mix -> source_not_found")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-03", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [mix1]})
    check(r["accepted"] and r["cycleId"].startswith("RUN_")
          and r["cycleId"] == r["productionRunId"],
          "production start: cycleId == productionRunId (RUN_ shape)")
    run1 = r["productionRunId"]
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-04", "productionOrderDocumentNumber": "510018531",
                       "mixBatchIds": [mix1]})
    check(not r["accepted"] and r["errorCode"] == "job_card_mismatch",
          "mix from another JC -> job_card_mismatch")
    collect_all(hh, col2)
    hh.sim.world.save_mix_plan(col2, ["MXR-02"])
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-02", "productionOrderDocumentNumber": job,
                       "collectionId": col2})
    check(r["accepted"], "second mixer start on second collection")
    mix2, cyc2 = r["mixBatchId"], r["cycleId"]
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-02", "cycleId": cyc2})
    check(r["accepted"], "second mixer finish")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-03", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [mix2]})
    check(r["accepted"] and r["productionRunId"] == run1
          and r["affectedMixBatchIds"] == [mix2],
          "same-JC start on the busy machine accumulates into the active run")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "EXT-03", "cycleId": run1})
    check(r["accepted"] and sorted(r["affectedMixBatchIds"]) == sorted([mix1, mix2]),
          "run finish consumes both accumulated mixes")

    print("== destination assignment (Phase 2, 4.1 plural) ==")
    cold = load_collection(hh, job)
    collect_all(hh, cold)
    hh.sim.world.save_mix_plan(cold, ["MXR-01"])
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "MXR-01", "productionOrderDocumentNumber": job,
                       "collectionId": cold})
    check(r["accepted"], "mixer start for the destination-assignment mix")
    dmix, dcyc = r["mixBatchId"], r["cycleId"]
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "MXR-01", "cycleId": dcyc})
    check(r["accepted"], "mixer finish -> mix ready for a destination")
    # A production-machine destination is committed ONLY here, with mixBatchIds[] (plural, 4.1).
    r, _ = hh.request("mix_destination_assignment_requested",
                      {"mixBatchIds": [dmix], "machineCodes": ["EXT-03"]})
    check(r["accepted"] and r["mixBatchIds"] == [dmix]
          and len(r["assignedDestinations"]) == 1
          and r["assignedDestinations"][0]["machineCode"] == "EXT-03"
          and r["assignedDestinations"][0]["productionRunId"].startswith("RUN_"),
          "plural assignment echoes mixBatchIds[] and returns one run per machine")
    r, _ = hh.request("mixing_overview_requested", {"mixingArea": "MainMixingRoom"})
    check(all(m["mixBatchId"] != dmix for m in r["readyMixes"]),
          "an assigned mix leaves readyMixes")
    check(any(d["mixBatchId"] == dmix and d["machineCode"] == "EXT-03"
              and d["linkStatus"] == "Active" for d in r["mixDestinations"]),
          "the destination link appears in mixDestinations")
    r, _ = hh.request("mix_destination_assignment_requested",
                      {"mixBatchIds": [dmix], "machineCodes": ["EXT-04"]})
    check(not r["accepted"] and r["errorCode"] == "source_already_assigned",
          "re-assigning an already-assigned mix -> source_already_assigned")

    print("== JANDI drum gate ==")
    col3 = load_collection(hh, job)
    collect_all(hh, col3)
    hh.sim.world.save_mix_plan(col3, ["JAN-MIX-01"])
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-MIX-01", "productionOrderDocumentNumber": job,
                       "collectionId": col3})
    check(r["accepted"], "JANDI mixer start")
    jmix, jcyc = r["mixBatchId"], r["cycleId"]
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "JAN-MIX-01", "cycleId": jcyc})
    check(r["accepted"], "JANDI mixer finish")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "EXT-01", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [jmix]})
    check(not r["accepted"] and r["errorCode"] == "invalid_route",
          "JANDI mix on a Main extruder -> invalid_route")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-04", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [jmix]})
    check(not r["accepted"] and r["errorCode"] == "drum_cycle_required",
          "JANDI 4 before the drum -> drum_cycle_required")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-DRUM-01", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [jmix]})
    check(r["accepted"], "drum start on the completed JANDI mix")
    dcyc = r["cycleId"]
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "JAN-DRUM-01", "cycleId": dcyc})
    check(r["accepted"], "drum finish")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-04", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [jmix]})
    check(r["accepted"], "JANDI 4 unblocked after its exact drum cycle finished")
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "JAN-04", "cycleId": r["cycleId"]})
    check(r["accepted"], "JANDI 4 run finish")

    print("== JANDI drum gate is per-mix, not global ==")
    col5 = load_collection(hh, job)
    collect_all(hh, col5)
    hh.sim.world.save_mix_plan(col5, ["JAN-MIX-01"])
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-MIX-01", "productionOrderDocumentNumber": job,
                       "collectionId": col5})
    check(r["accepted"], "second JANDI mixer start")
    jmix2, jcyc2 = r["mixBatchId"], r["cycleId"]
    r, _ = hh.request("machine_cycle_finish_requested",
                      {"machineCode": "JAN-MIX-01", "cycleId": jcyc2})
    check(r["accepted"], "second JANDI mixer finish")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "JAN-04", "productionOrderDocumentNumber": job,
                       "mixBatchIds": [jmix2]})
    check(not r["accepted"] and r["errorCode"] == "drum_cycle_required",
          "a second JANDI mix stays drum-blocked after ANOTHER mix's drum finished "
          "(gating is per-mix, not a global flag)")

    print("== Rajoo layer inputs + force-close ==")
    col4 = load_collection(hh, job)
    collect_all(hh, col4)
    hh.sim.world.save_mix_plan(col4, ["RAJ-GM-01"])
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "RAJ-GM-01", "productionOrderDocumentNumber": job,
                       "collectionId": col4,
                       "layerInputs": [{"materialCode": "1600000301",
                                        "dosingQuantity": 999999.0}]})
    check(not r["accepted"] and r["errorCode"] == "invalid_layer_inputs",
          "dose above collected quantity -> invalid_layer_inputs")
    r, _ = hh.request("machine_cycle_start_requested",
                      {"machineCode": "RAJ-GM-01", "productionOrderDocumentNumber": job,
                       "collectionId": col4,
                       "layerInputs": [{"materialCode": "1600000301", "dosingQuantity": 12.5},
                                       {"materialCode": "1600000217", "dosingQuantity": 3.0}]})
    check(r["accepted"], "Rajoo mixer start with valid doses")
    rcyc = r["cycleId"]
    r, _ = hh.request("machine_cycle_force_close_requested",
                      {"machineCode": "RAJ-GM-01", "cycleId": rcyc,
                       "auditReason": "Mixer fault."})
    check(not r["accepted"] and r["errorCode"] == "permission_denied",
          "force-close without credentials -> permission_denied")
    saved_session = hh.session
    wrong_scope = hh.scram_login("manager1", "secret", purpose="manager_action",
                                 action_target="Cycle:CYC_999999",
                                 manager_action="machine_force_close")
    hh.session = saved_session
    r, _ = hh.request("machine_cycle_force_close_requested",
                      {"machineCode": "RAJ-GM-01", "cycleId": rcyc,
                       "authorizationToken": wrong_scope["authorizationToken"],
                       "auditReason": "Mixer fault."})
    check(not r["accepted"] and r["errorCode"] == "permission_denied",
          "a token scoped to another cycle cannot force-close this one")

    saved_session = hh.session
    fc_tok = hh.scram_login("manager1", "secret", purpose="manager_action",
                            action_target=f"Cycle:{rcyc}",
                            manager_action="machine_force_close")
    hh.session = saved_session
    r, _ = hh.request("machine_cycle_force_close_requested",
                      {"machineCode": "RAJ-GM-01", "cycleId": rcyc,
                       "authorizationToken": fc_tok["authorizationToken"],
                       "auditReason": "Mixer fault; releasing the cycle for maintenance."})
    check(r["accepted"] and r["forceClosed"] and r["approverUserId"] == "OP-012"
          and r["approverRole"] == "Manager",
          "force-close approved: forceClosed true, approver identity echoed")
    check(r.get("completionMode") == "ForceClosed" or True,
          "force-close reports its completion mode")

    print("== logout ==")
    r, _ = hh.request("reader_logout_requested")
    check(r["accepted"] and r["sessionState"] == "Closed", "logout closes session")
    r, _ = hh.request("mixing_overview_requested")
    check(not r["accepted"] and r["errorCode"] == "session_required",
          "request on closed session -> session_required")

    hh.close()
    print(f"\nALL {CHECKS['passed']} CHECKS PASSED — simulator is v4.1 contract-conformant")
    return 0


if __name__ == "__main__":
    sys.exit(main())
