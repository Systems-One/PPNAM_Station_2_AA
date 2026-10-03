"""Passive MQTT sniffer for PPNAM Station 2 live-backend testing.

Read-only: subscribes to PPNAM/# and records every frame to wire.jsonl,
pairing requests with responses so latency and orphaned requests are visible.
Never publishes.
"""
import json
import os
import sys
import threading
import time
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

HOST = "mqtt.sysone.co.za"
PORT = 443
OUTDIR = sys.argv[1] if len(sys.argv) > 1 else "capture"
os.makedirs(OUTDIR, exist_ok=True)

wire_path = os.path.join(OUTDIR, "wire.jsonl")
human_path = os.path.join(OUTDIR, "wire.log")
wire = open(wire_path, "a", encoding="utf-8")
human = open(human_path, "a", encoding="utf-8")
lock = threading.Lock()

# messageId -> (topic, monotonic_ts, action)
pending = {}
counts = {"total": 0, "req": 0, "res": 0, "status": 0, "other": 0}


def now_iso():
    return datetime.now(timezone.utc).isoformat()


def redact(obj):
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if k in ("password", "managerPassword") and v not in (None, ""):
                out[k] = "<redacted:present>"
            else:
                out[k] = redact(v)
        return out
    if isinstance(obj, list):
        return [redact(v) for v in obj]
    return obj


def emit(line):
    human.write(line + "\n")
    human.flush()
    print(line, flush=True)


def on_connect(c, u, f, rc, props=None):
    emit(f"[{now_iso()}] SNIFFER CONNECTED rc={rc}")
    c.subscribe("PPNAM/#", qos=1)


def on_disconnect(c, u, flags, rc, props=None):
    emit(f"[{now_iso()}] SNIFFER DISCONNECTED rc={rc}")


def on_message(c, u, m):
    ts = now_iso()
    mono = time.monotonic()
    raw = m.payload.decode("utf-8", "replace")
    try:
        payload = json.loads(raw)
        parsed = True
    except Exception:
        payload = raw
        parsed = False

    # Post 2026-08-17 restructure: PPNAM/station_2 and PPNAM/station_2/{deviceId} are
    # base-node presence; PPNAM/station_2/{deviceId}/req|res/{t} is workflow traffic;
    # PPNAM/station_2/res/{t} is a station broadcast.
    parts = m.topic.split("/")
    if len(parts) >= 5 and parts[3] in ("req", "res"):
        kind = parts[3]
    elif len(parts) == 4 and parts[2] == "res":
        kind = "res"
    elif len(parts) <= 3:
        kind = "status"
    else:
        kind = "other"

    rec = {
        "ts": ts,
        "topic": m.topic,
        "retain": bool(m.retain),
        "qos": m.qos,
        "kind": kind,
        "parsed": parsed,
        "payload": redact(payload) if parsed else payload,
    }

    latency_ms = None
    action = None
    if parsed and isinstance(payload, dict):
        mid = payload.get("messageId")
        irt = payload.get("inResponseToMessageId")
        action = payload.get("action") or payload.get("requestType") or (parts[3] if len(parts) > 3 else None)
        rec["messageId"] = mid
        rec["inResponseToMessageId"] = irt
        rec["action"] = action
        rec["success"] = payload.get("success")
        rec["errorCode"] = payload.get("errorCode")
        rec["errorMessage"] = payload.get("errorMessage")
        with lock:
            if kind == "req" and mid:
                pending[mid] = (m.topic, mono, action)
            if irt and irt in pending:
                _, t0, req_action = pending.pop(irt)
                latency_ms = round((mono - t0) * 1000)
                rec["latencyMs"] = latency_ms
                rec["requestAction"] = req_action

    with lock:
        counts["total"] += 1
        counts[kind if kind in counts else "other"] += 1
        wire.write(json.dumps(rec, ensure_ascii=False) + "\n")
        wire.flush()

    tag = {"req": "-->", "res": "<--", "status": "***"}.get(kind, "   ")
    extra = ""
    if rec.get("success") is not None:
        extra += f" success={rec['success']}"
    if rec.get("errorCode"):
        extra += f" err={rec['errorCode']}:{rec.get('errorMessage')}"
    if latency_ms is not None:
        extra += f" ({latency_ms}ms)"
    body = raw if len(raw) < 220 else raw[:220] + f"...(+{len(raw)-220}B)"
    emit(f"[{ts}] {tag} {m.topic} action={action}{extra}\n        {body}")


def report_orphans():
    while True:
        time.sleep(15)
        cutoff = time.monotonic() - 25
        with lock:
            stale = [(k, v) for k, v in pending.items() if v[1] < cutoff]
            for k, v in stale:
                pending.pop(k, None)
        for mid, (topic, _, action) in stale:
            emit(f"[{now_iso()}] !!! NO RESPONSE within 25s: action={action} topic={topic} messageId={mid}")


threading.Thread(target=report_orphans, daemon=True).start()

client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="ppnam_sniffer_jw",
                     transport="websockets", protocol=mqtt.MQTTv5)
client.ws_set_options(path="/mqtt")
client.tls_set()
client.username_pw_set("admin", "admin")
client.on_connect = on_connect
client.on_disconnect = on_disconnect
client.on_message = on_message
client.connect(HOST, PORT, keepalive=30)
emit(f"[{now_iso()}] sniffer writing to {os.path.abspath(wire_path)}")
client.loop_forever()
