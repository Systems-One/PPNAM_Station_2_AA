"""Station 2 backend simulator — answers the Android handheld's MQTT v3
contract traffic exactly like the real WPF backend would, with extensive
logging (wire.jsonl, sim.log, state snapshots) as a second source of truth.

Usage:
    python sim.py [--host mqtt.sysone.co.za] [--port 443]
                  [--transport websockets] [--ws-path /mqtt] [--tls | --no-tls]
                  [--username admin] [--password admin]
                  [--window 300] [--tolerance 1.0] [--yield-to-real]

Defaults: wss on port 443, path /mqtt, admin/admin — per broker config as of
2026-07-23. NOTE: the app's AppSettings.kt hardcodes port 8884, which
disagrees with this; reconcile with whichever is actually live before
testing against the real broker. For a plain factory broker (e.g.
10.1.50.1:1883, no TLS/auth), pass --transport tcp --no-tls --username "".

The simulator plays the role of station_2 (per-station namespace, 2026-08-17):
  - retained `online` on the base topic PPNAM/station_2 (LWT: retained `offline`)
  - subscribes PPNAM/station_2/+/req/+ and PPNAM/station_2/+ (device presence)
  - one worker thread processes requests strictly in arrival order, which is
    exactly the serialization the contract requires of Station 2.
"""

import argparse
import json
import os
import queue
import sys
import threading
import time

from datetime import timedelta

import paho.mqtt.client as mqtt
from paho.mqtt.packettypes import PacketTypes
from paho.mqtt.properties import Properties

import envelope
from envelope import Rejection, Replay, build_response
from handlers import REGISTRY, RETIRED_REQUEST_TYPES
from simlog import SimLogger
from state import World

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATION_ID = "station_2"
STATION_BASE = f"PPNAM/{STATION_ID}"
# Presence lives on the base topic node — no /status sub-topic (2026-08-17 restructure).
STATUS_TOPIC = STATION_BASE

# --- Fault injection (§4.1b / §4.4c / E24 test support) -------------------------------------
# A guarded, opt-in control plane: the sim subscribes to CONTROL_TOPIC and the harness publishes
# a JSON command to arm a one-shot (or N-shot) fault before triggering the app action under test.
# Faults are stored per-Simulator and default to empty, so selftest (--direct, which never
# publishes control) and normal runs behave exactly as before.
CONTROL_TOPIC = "PPNAM/_sim/control"


def _redacted(payload):
    """Wire-log payloads with credentials masked. The workflow still receives the
    original bytes — only the log copy is redacted (contract: credentials are
    redacted from application and MQTT logs)."""
    try:
        body = json.loads(payload)
    except (ValueError, UnicodeDecodeError):
        return payload
    if not isinstance(body, dict):
        return payload
    masked = False
    for key in ("password", "managerPassword"):
        if body.get(key) is not None:
            body[key] = "***"
            masked = True
    return json.dumps(body, ensure_ascii=False) if masked else payload


class Simulator:
    def __init__(self, args):
        self.args = args
        self.log = SimLogger(BASE_DIR, color=not args.no_color)
        self.world = World(os.path.join(BASE_DIR, "seed"), self.log)
        if args.window is not None:
            self.world.config["timestampWindowSeconds"] = args.window
        if args.tolerance is not None:
            self.world.config["overCollectionToleranceBags"] = args.tolerance
        if getattr(args, "demo_collections", False):
            from handlers.jobcards import seed_demo_collections
            seed_demo_collections(self.world, self.log)
        self.queue = queue.Queue()
        self.announced = False
        # Armed fault-injection commands (list of dicts). Empty by default -> zero behavioural
        # change. Guarded by a lock because control frames arrive on the network thread while the
        # worker thread consumes faults.
        self._faults = []
        self._faults_lock = threading.Lock()
        self.real_station_seen = threading.Event()
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id="station2-simulator",
                                  protocol=mqtt.MQTTv5,
                                  transport=args.transport)
        if args.transport == "websockets":
            self.client.ws_set_options(path=args.ws_path)
        if args.tls:
            self.client.tls_set()
        if args.username:
            self.client.username_pw_set(args.username, args.password)
        self.client.will_set(STATUS_TOPIC, "offline", qos=1, retain=True)
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.client.on_disconnect = self.on_disconnect

    # ------------------------------------------------------------ MQTT ----
    def on_connect(self, client, userdata, flags, reason_code, properties):
        self.log.ok(f"connected to {self.args.host}:{self.args.port} (rc={reason_code})")
        # Collision guard: watch for a real Station 2 already online before we
        # claim the retained status topic ourselves.
        client.subscribe([(STATUS_TOPIC, 1)])

    def announce(self):
        self.client.publish(STATUS_TOPIC, "online", qos=1, retain=True)
        self.log.wire("out", STATUS_TOPIC, "online")
        self.client.subscribe([(f"{STATION_BASE}/+/req/+", 1), (f"{STATION_BASE}/+", 1),
                               (CONTROL_TOPIC, 1)])
        self.announced = True
        self.log.ok("presence 'online' published (retained, LWT registered); "
                    f"subscribed {STATION_BASE}/+/req/+ and {STATION_BASE}/+ — simulating {STATION_ID}")

    def on_disconnect(self, client, userdata, flags, reason_code, properties):
        self.log.warn(f"disconnected from broker (rc={reason_code}); paho will auto-reconnect")

    def on_message(self, client, userdata, msg):
        parts = msg.topic.split("/")
        if msg.topic == CONTROL_TOPIC:
            # Control frames are applied inline on the network thread so a fault is armed the
            # instant it is published, before the request it is meant to affect can arrive.
            self._apply_control(msg.payload)
            return
        is_req = (len(parts) == 5 and parts[0] == "PPNAM" and parts[1] == STATION_ID
                  and parts[3] == "req")
        if not is_req:
            # req messages are wire-logged in handle_request (shared with the
            # selftest's direct in-process transport)
            self.log.wire("in", msg.topic, msg.payload, qos=msg.qos)
        if msg.topic == STATUS_TOPIC:
            # Our own base-node presence (collision guard for a real Station 2).
            if not self.announced and msg.payload.decode(errors="replace") == "online":
                self.real_station_seen.set()
            return
        if (len(parts) == 3 and parts[0] == "PPNAM" and parts[1] == STATION_ID
                and parts[2] not in ("req", "res")):
            # Device presence on the device's base node (PPNAM/station_2/{deviceId}).
            self.queue.put(("status", parts[2], msg.payload))
        elif is_req:
            self.queue.put(("req", parts[2], parts[4], msg.payload))
        else:
            self.log.warn(f"unknown topic '{msg.topic}' — no workflow side effect (per contract)")

    def publish_response(self, device_id, response_type, response):
        topic = f"{STATION_BASE}/{device_id}/res/{response_type}"
        payload = json.dumps(response, ensure_ascii=False)
        self.client.publish(topic, payload, qos=1, retain=False)
        self.log.wire("out", topic, response)
        outcome = "ACCEPTED" if response.get("accepted") else \
            f"REJECTED ({response.get('errorCode')})"
        self.log.tx(f"res/{response_type} -> {device_id}: {outcome} "
                    f"msgId={response.get('messageId')} "
                    f"inResponseTo={response.get('inResponseToMessageId')} "
                    f"nextAction={response.get('nextAction')!r}")
        return topic

    # ------------------------------------------------ fault injection ----
    def _apply_control(self, payload):
        """Handle a control frame on CONTROL_TOPIC. Arms a fault, or performs an immediate
        side effect (presence override / reset)."""
        try:
            cmd = json.loads(payload)
            if not isinstance(cmd, dict):
                raise ValueError("control frame is not an object")
        except (ValueError, UnicodeDecodeError) as e:
            self.log.warn(f"sim-control: unparseable control frame: {e}")
            return
        kind = cmd.get("cmd")
        if kind == "reset":
            with self._faults_lock:
                self._faults.clear()
            # Restore a clean retained 'online' presence (A9 teardown).
            self.client.publish(STATUS_TOPIC, "online", qos=1, retain=True)
            self.log.wire("out", STATUS_TOPIC, "online")
            self.log.ok("sim-control: RESET — all faults cleared, presence restored to 'online'")
            return
        if kind == "presence":
            value = str(cmd.get("value", "online"))
            self.client.publish(STATUS_TOPIC, value, qos=1, retain=True)
            self.log.wire("out", STATUS_TOPIC, value)
            self.log.warn(f"sim-control: FAULT presence override -> retained '{value}' "
                          f"on {STATUS_TOPIC}")
            return
        if kind == "save_mix_plan":
            # Test-only stand-in for the WPF/Core save Station 2 normally performs out of band
            # of the MQTT handheld contract (see state.World.save_mix_plan) — the only way to
            # get a collection into MixingPlanned from outside --direct selftest, so on-device
            # runs can exercise the per-area readyCollections scoping without a live Station 2.
            col_id = cmd.get("collectionId")
            mixer_codes = cmd.get("mixerCodes", [])
            try:
                plan = self.world.save_mix_plan(col_id, mixer_codes)
                self.log.ok(f"sim-control: SAVE_MIX_PLAN {col_id} -> {plan['mixPlanId']} "
                            f"reserving {mixer_codes}")
            except KeyError as e:
                self.log.warn(f"sim-control: SAVE_MIX_PLAN failed: {e}")
            return
        if kind in ("withhold", "malformed", "uncorrelated", "reject", "login_mangle"):
            fault = {
                "cmd": kind,
                "match": cmd.get("match", "*"),
                "remaining": int(cmd.get("count", 1)),
            }
            for extra in ("errorCode", "reason", "nextAction", "session", "mode"):
                if extra in cmd:
                    fault[extra] = cmd[extra]
            with self._faults_lock:
                self._faults.append(fault)
            self.log.warn(f"sim-control: FAULT armed {fault}")
            return
        self.log.warn(f"sim-control: unknown cmd {kind!r} — ignored")

    def _take_fault(self, request_type, kinds):
        """Pop and return the first armed fault matching request_type whose cmd is in `kinds`.
        Decrements its shot counter; removes it when exhausted. Returns None if none match."""
        with self._faults_lock:
            for f in self._faults:
                if f["cmd"] in kinds and f["match"] in ("*", request_type):
                    f["remaining"] -= 1
                    if f["remaining"] <= 0:
                        self._faults.remove(f)
                    return f
        return None

    def _fault_reject(self, device_id, request_type, payload, fault):
        """Publish a fully-correlated rejection response as demanded by a `reject` fault
        (A18/A19 session_required, A20 client_upgrade_required, D32 forced recovery failure)."""
        try:
            body = json.loads(payload)
            if not isinstance(body, dict):
                body = {}
        except (ValueError, UnicodeDecodeError):
            body = {}
        body.setdefault("deviceId", device_id)
        error_code = fault.get("errorCode", "state_conflict")
        # session id echoed back: 'current' mirrors the request's session (app must log out);
        # 'old' fabricates a stale one (app must stay put); a literal string is used verbatim.
        sess_mode = fault.get("session")
        if sess_mode == "current":
            session_id = body.get("operatorSessionId")
        elif sess_mode == "old":
            session_id = "SES-STALE-00000000000000"
        elif sess_mode is not None:
            session_id = sess_mode
        else:
            session_id = body.get("operatorSessionId", "")
        entry = REGISTRY.get(request_type)
        response_type = entry["response"] if entry else request_type
        response = build_response(
            self.world, body, accepted=False,
            error_code=error_code,
            reason=fault.get("reason", f"Injected fault: {error_code}."),
            next_action=fault.get("nextAction", ""),
            session_id=session_id)
        self.log.warn(f"sim-control: FAULT reject -> {error_code} "
                      f"(session={session_id!r}) on res/{response_type}")
        self.publish_response(device_id, response_type, response)

    def _mangle_login(self, response, mode):
        """Corrupt an accepted operator_context to exercise B3/B4/B5 client tolerance."""
        if mode == "blank_session":
            response["operatorSessionId"] = ""
        elif mode == "session_closed":
            response["sessionState"] = "Closed"
        elif mode == "bad_expiry":
            response["sessionExpiresAtUtc"] = "not-a-timestamp"
        self.log.warn(f"sim-control: FAULT login_mangle mode={mode} applied to operator_context")

    # ---------------------------------------------------------- worker ----
    def worker(self):
        while True:
            item = self.queue.get()
            if item is None:
                return
            try:
                if item[0] == "status":
                    _, device_id, payload = item
                    status = payload.decode(errors="replace") if payload else ""
                    if status in ("online", "offline"):
                        self.world.presence_change(device_id, status)
                    elif payload:
                        self.log.warn(f"presence {device_id}: unexpected payload {status!r}")
                else:
                    _, device_id, request_type, payload = item
                    self.handle_request(device_id, request_type, payload)
            except Exception:  # noqa: BLE001 — a bad message must never kill the worker
                import traceback
                self.log.fail("unhandled error in worker:\n" + traceback.format_exc())

    def handle_request(self, device_id, request_type, payload):
        self.log.wire("in", f"{STATION_BASE}/{device_id}/req/{request_type}", _redacted(payload))

        # -- fault injection (opt-in; no faults armed -> this block is a no-op) --------------
        if self._faults:
            fault = self._take_fault(request_type, ("withhold", "malformed", "reject"))
            if fault:
                if fault["cmd"] == "withhold":
                    self.log.warn(f"sim-control: FAULT withhold — dropping req/{request_type} "
                                  f"(no processing, no response)")
                    return
                if fault["cmd"] == "malformed":
                    entry = REGISTRY.get(request_type)
                    response_type = entry["response"] if entry else request_type
                    topic = f"{STATION_BASE}/{device_id}/res/{response_type}"
                    # The app correlates ONLY on inResponseToMessageId, so a totally broken frame is
                    # dropped as uncorrelated and the request just times out. To exercise
                    # FailureKind.MalformedResponse the frame must correlate (valid envelope) but
                    # fail the body DTO parse — so we keep the envelope fields valid and give
                    # several body fields the WRONG json type (a number where the DTO wants a
                    # List/object). Whichever field the matched response DTO owns throws
                    # "Expected BEGIN_ARRAY/OBJECT but was NUMBER" inside parseOutcome().
                    try:
                        body = json.loads(payload)
                        req_id = body.get("messageId") if isinstance(body, dict) else None
                    except (ValueError, UnicodeDecodeError):
                        req_id = None
                    broken = json.dumps({
                        "messageId": "S2-MALFORMED", "inResponseToMessageId": req_id,
                        "schemaVersion": envelope.SCHEMA_VERSION, "deviceId": device_id,
                        "accepted": True, "nextAction": "",
                        # type-broken body fields spanning the mutating response DTOs:
                        "equipment": 42, "readyMixes": 42, "areaStatus": 42, "ingredients": 42,
                        "jobs": 42, "assignedDestinations": 42, "collectionSummary": 42,
                    })
                    self.client.publish(topic, broken, qos=1, retain=False)
                    self.log.warn(f"sim-control: FAULT malformed — published a correlated but "
                                  f"DTO-broken frame on res/{response_type} "
                                  f"(inResponseTo={req_id})")
                    return
                if fault["cmd"] == "reject":
                    self._fault_reject(device_id, request_type, payload, fault)
                    return
            unc = self._take_fault(request_type, ("uncorrelated",))
            if unc:
                # Two junk res frames the app must drop: one with no inResponseToMessageId,
                # one for an unknown id. The real response then follows normally.
                entry = REGISTRY.get(request_type)
                response_type = entry["response"] if entry else request_type
                topic = f"{STATION_BASE}/{device_id}/res/{response_type}"
                self.client.publish(topic, json.dumps({
                    "messageId": "S2-UNCORR-1", "accepted": True,
                    "schemaVersion": envelope.SCHEMA_VERSION, "deviceId": device_id,
                    "note": "no inResponseToMessageId — must be dropped"}), qos=1)
                self.client.publish(topic, json.dumps({
                    "messageId": "S2-UNCORR-2", "inResponseToMessageId": "MSG-DOES-NOT-EXIST",
                    "accepted": True, "schemaVersion": envelope.SCHEMA_VERSION,
                    "deviceId": device_id,
                    "note": "unknown correlation id — must be dropped"}), qos=1)
                self.log.warn(f"sim-control: FAULT uncorrelated — published 2 junk res/"
                              f"{response_type} frames; real response follows")

        if request_type in RETIRED_REQUEST_TYPES:
            self.log.fail(f"req/{request_type} from {device_id}: RETIRED v3 production request "
                          f"— answering client_upgrade_required (v4 tripwire)")
            try:
                body = json.loads(payload)
                if not isinstance(body, dict):
                    body = {}
            except (ValueError, UnicodeDecodeError):
                body = {}
            body.setdefault("deviceId", device_id)
            response = build_response(
                self.world, body, accepted=False,
                error_code="client_upgrade_required",
                reason="This request was retired by contract v4.0. Upgrade the reader "
                       "build for the unified Mixing workflow.",
                next_action="upgrade_reader_for_mixing")
            self.publish_response(device_id, "workflow_upgrade_required", response)
            return
        entry = REGISTRY.get(request_type)
        if not entry:
            self.log.warn(f"req/{request_type} from {device_id}: unknown request type — "
                          f"no workflow side effect, no response (per contract)")
            return
        self.log.rx(f"req/{request_type} from {device_id} ({len(payload)} bytes)")

        ctx, req, session, response = {}, {}, None, None
        try:
            req, session = envelope.validate(self.world, self.log, device_id,
                                             request_type, payload, entry["is_login"],
                                             ctx)
            response = entry["handler"](self.world, self.log, req, session)
        except Replay as rep:
            self.publish_response(device_id, entry["response"], rep.response)
            return
        except Rejection as rej:
            extras = dict(rej.extra)
            if entry["reject_extras"]:
                base = entry["reject_extras"](self.world)
                base.update(extras)
                extras = base
            source = req or ctx
            fallback = {"messageId": source.get("messageId"), "deviceId": device_id,
                        "operatorSessionId": source.get("operatorSessionId", ""),
                        "correlationKey": source.get("correlationKey")}
            response = build_response(
                self.world, fallback,
                accepted=False, error_code=rej.error_code, reason=rej.reason,
                next_action=rej.next_action, response_extras=extras,
                session_id=session["sessionId"] if session else None)
            req = source

        # login-response mangling fault (B3/B4/B5): corrupt an accepted operator_context.
        if self._faults and response is not None and response.get("accepted"):
            lm = self._take_fault(request_type, ("login_mangle",))
            if lm:
                self._mangle_login(response, lm.get("mode", "blank_session"))

        # step 8: replay storage (only when a replay identity existed)
        topic = self.publish_response(device_id, entry["response"], response)
        if req.get("_bodyHash"):
            self.world.replay_store(device_id, request_type, req["messageId"],
                                    req["_bodyHash"], topic, response)
        if response.get("accepted") and entry["mutating"]:
            self.world.log = None  # never serialize the logger
            snapshot = self.world.to_dict()
            self.world.log = self.log
            self.log.snapshot(snapshot, f"{request_type}-{req.get('messageId', 'na')}")

    # ------------------------------------------------------------- run ----
    def run(self):
        self.log.ok(f"Station 2 backend simulator starting "
                    f"(schema {envelope.SCHEMA_VERSION}, contract v4) — "
                    f"window ±{self.world.config['timestampWindowSeconds']}s, "
                    f"tolerance {self.world.config['overCollectionToleranceBags']} bag(s)")
        self.log.ok(f"logs: {self.log.run_dir}")
        self.client.connect(self.args.host, self.args.port, keepalive=30)
        self.client.loop_start()

        # collision guard: give a retained 'online' from the real backend 2s to arrive
        if self.real_station_seen.wait(timeout=2.0):
            self.log.fail("REAL STATION 2 APPEARS ONLINE on this broker "
                          f"({STATUS_TOPIC} is retained 'online'). Both backends would "
                          f"answer every request!")
            if self.args.yield_to_real:
                self.log.fail("--yield-to-real set: exiting without announcing.")
                self.client.loop_stop()
                self.client.disconnect()
                return 1
            self.log.warn("continuing anyway (no --yield-to-real); expect duplicate responses "
                          "if the real backend is truly alive")
        self.announce()

        worker = threading.Thread(target=self.worker, daemon=True, name="sim-worker")
        worker.start()
        self.log.ok("ready — waiting for handheld traffic (Ctrl+C to stop)")
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            self.log.warn("shutting down: publishing retained 'offline'")
            props = Properties(PacketTypes.PUBLISH)
            self.client.publish(STATUS_TOPIC, "offline", qos=1, retain=True,
                                properties=props).wait_for_publish(timeout=3)
            self.log.wire("out", STATUS_TOPIC, "offline")
            self.queue.put(None)
            self.client.loop_stop()
            self.client.disconnect()
            self.log.close()
        return 0


def main():
    parser = argparse.ArgumentParser(description="Station 2 MQTT backend simulator (contract v4)")
    parser.add_argument("--host", default="mqtt.sysone.co.za", help="MQTT broker host")
    parser.add_argument("--port", type=int, default=None,
                        help="MQTT broker port (default: 443 for websockets, 1883 for tcp)")
    parser.add_argument("--transport", choices=["tcp", "websockets"], default="websockets",
                        help="matches the app's AppSettings default (websockets); pass "
                             "--transport tcp for a plain factory broker")
    parser.add_argument("--ws-path", default="/mqtt", help="WebSocket path (websockets transport only)")
    parser.add_argument("--tls", dest="tls", action="store_true", default=True,
                        help="enable TLS with system CA validation (default: on)")
    parser.add_argument("--no-tls", dest="tls", action="store_false", help="disable TLS")
    parser.add_argument("--username", default="admin", help="broker auth username (blank to disable auth)")
    parser.add_argument("--password", default="admin", help="broker auth password")
    parser.add_argument("--demo-collections", dest="demo_collections", action="store_true",
                        default=True, help="pre-load a few job-card collections at startup, "
                        "one already ReadyForMixing (default: on)")
    parser.add_argument("--no-demo-collections", dest="demo_collections", action="store_false",
                        help="start with no pre-loaded collections (clean world)")
    parser.add_argument("--window", type=int, default=None,
                        help="timestamp acceptance window in seconds (default from seed: 300)")
    parser.add_argument("--tolerance", type=float, default=None,
                        help="over-collection tolerance in full bags (default from seed: 1.0)")
    parser.add_argument("--yield-to-real", action="store_true",
                        help="exit instead of announcing if the real Station 2 is online")
    parser.add_argument("--no-color", action="store_true", help="disable console colours")
    args = parser.parse_args()
    if args.port is None:
        args.port = 443 if args.transport == "websockets" else 1883
    sys.exit(Simulator(args).run())


if __name__ == "__main__":
    main()
