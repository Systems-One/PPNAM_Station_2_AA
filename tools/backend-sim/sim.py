"""Station 2 backend simulator — answers the Android handheld's rev2.1 MQTT traffic
exactly like the real WPF backend would, with extensive
logging (wire.jsonl, sim.log, state snapshots) as a second source of truth.

Usage:
    python sim.py [--host mqtt.sysone.co.za] [--port 443]
                  [--transport websockets] [--ws-path /mqtt] [--tls | --no-tls]
                  [--username admin] [--password admin]
                  [--window 300] [--yield-to-real]

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
from envelope import Rejection
from handlers import rev2_general, scram
from simlog import SimLogger
from state import World, iso, utc_now

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
        if getattr(args, "demo_collections", False):
            rev2_general.seed_demo_jobs(self.world, self.log)
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
        self.client.publish(topic, json.dumps(response, ensure_ascii=False), qos=1, retain=False)
        self.log.wire("out", topic, response)
        outcome = "SUCCESS" if response.get("success") else f"FAILED ({response.get('error')})"
        self.log.tx(f"res/{response_type} -> {device_id}: {outcome} "
                    f"inResponseTo={response.get('inResponseToMessageId')!r}")
        return topic

    def publish_invalidations(self):
        """The server's PreparationsChanged push, to every device that has read General."""
        for device_id in sorted(self.world.general_readers):
            self.publish_response(device_id, "active_job_cards_invalidated", {
                "schemaVersion": envelope.SCHEMA_VERSION,
                "deviceId": device_id,
                "messageId": f"rev2-preparations-{os.urandom(16).hex()}",
                "timestampUtc": iso(utc_now()),
                "mode": "General",
                "reason": "preparation_created",
                "nextAction": "read",
            })

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
        if kind == "invalidate":
            self.publish_invalidations()
            self.log.ok("sim-control: INVALIDATE — active_job_cards_invalidated pushed")
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
        if self._faults and self._take_fault(request_type, ("withhold",)):
            self.log.warn(f"sim-control: FAULT withhold — dropping req/{request_type} "
                          f"(no processing, no response)")
            return
        received_at = utc_now()
        ctx = {"messageId": ""}
        try:
            req = envelope.validate(device_id, request_type, payload, ctx)
            response = self._dispatch(device_id, request_type, payload, req, received_at)
        except Rejection as rej:
            self.log.fail(f"req/{request_type} from {device_id}: {rej.error} — {rej.message}")
            response = envelope.reply(device_id, ctx["messageId"], received_at, False,
                                      rej.message, rej.error, rej.data)
        except Exception:  # noqa: BLE001 — mirrors the server's outcome_unconfirmed catch-all
            import traceback
            self.log.fail("unhandled error:\n" + traceback.format_exc())
            response = envelope.reply(
                device_id, ctx["messageId"], received_at, False,
                "The result could not be confirmed. Retry the identical request and message ID.",
                "outcome_unconfirmed")
        self.publish_response(device_id, envelope.response_suffix(request_type), response)

    def _dispatch(self, device_id, request_type, payload, req, received_at):
        message_id = req["messageId"]
        if request_type.startswith("scram_"):
            key = (device_id, request_type, message_id)
            self.world.expire_auth_replies(received_at)
            prior = self.world.auth_replies.get(key)
            if prior:
                if prior["body"] == payload:
                    return envelope.reply(device_id, message_id, received_at, True,
                                          "Authentication response replayed.", data=prior["data"])
                raise Rejection("message_id_conflict", "Message ID body mismatch.")
            if req.get("purpose") != "login":
                raise Rejection("purpose_not_enabled", "Only operator login is enabled.")
            if request_type == "scram_start_requested":
                data, text = scram.scram_start(self.world, self.log, req), "Challenge issued."
            else:
                data, text = scram.scram_proof(self.world, self.log, req), "Signed in."
            self.world.auth_replies[key] = {"body": payload, "data": data,
                                            "expires": received_at + timedelta(seconds=60)}
            return envelope.reply(device_id, message_id, received_at, True, text, data=data)

        if request_type == "rev2_rajoo_requested":
            raise Rejection("action_not_allowed", "The simulator does not implement the Rajoo family.")
        action = rev2_general.check_action(req)
        session, err = self.world.get_session(device_id, req.get("sessionId") or "")
        if not session:
            self.log.fail(f"session: {err}")
            raise Rejection("operator_session_invalid", "Sign in on this device before continuing.")
        result, snap = rev2_general.handle(self.world, self.log, req, action)
        return envelope.reply(device_id, message_id, received_at, result["success"],
                              result["message"], "" if result["success"] else "rev2_rejected", snap)

    # ------------------------------------------------------------- run ----
    def run(self):
        self.log.ok(f"Station 2 backend simulator starting "
                    f"(schema {envelope.SCHEMA_VERSION}) — "
                    f"window ±{self.world.config['timestampWindowSeconds']}s")
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
    parser = argparse.ArgumentParser(description="Station 2 MQTT backend simulator (rev2.1)")
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
                        default=True, help="hold one job with a preparation at startup "
                        "(default: on)")
    parser.add_argument("--no-demo-collections", dest="demo_collections", action="store_false",
                        help="start with no held jobs (clean world)")
    parser.add_argument("--window", type=int, default=None,
                        help="timestamp acceptance window in seconds (default from seed: 300)")
    parser.add_argument("--yield-to-real", action="store_true",
                        help="exit instead of announcing if the real Station 2 is online")
    parser.add_argument("--no-color", action="store_true", help="disable console colours")
    args = parser.parse_args()
    if args.port is None:
        args.port = 443 if args.transport == "websockets" else 1883
    sys.exit(Simulator(args).run())


if __name__ == "__main__":
    main()
