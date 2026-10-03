"""Publish a fault-injection control frame to the backend sim (PPNAM/_sim/control).

The sim subscribes to that topic and arms the requested fault before the next matching request.
This is test-only tooling; the sim ignores control frames unless it is the backend in use.

Examples:
    python simctl.py presence maybe                      # A9 garbage presence
    python simctl.py reset                               # clear faults, restore presence
    python simctl.py withhold mixing_overview_requested 3
    python simctl.py malformed mixing_overview_requested
    python simctl.py uncorrelated mixing_overview_requested
    python simctl.py reject active_job_cards_requested session_required current
    python simctl.py reject active_job_cards_requested session_required old
    python simctl.py reject active_job_cards_requested client_upgrade_required
    python simctl.py login_mangle blank_session          # B3 (match scram_proof + login)
    python simctl.py save_plan COL_000002 MXR-01         # seed a MixingPlanned reservation
"""
import json
import sys
import time

import paho.mqtt.client as mqtt

HOST, PORT = "mqtt.sysone.co.za", 443
TOPIC = "PPNAM/_sim/control"


def build(argv):
    cmd = argv[0]
    if cmd == "reset":
        return {"cmd": "reset"}
    if cmd == "presence":
        return {"cmd": "presence", "value": argv[1]}
    if cmd in ("withhold", "malformed", "uncorrelated"):
        f = {"cmd": cmd, "match": argv[1]}
        if len(argv) > 2:
            f["count"] = int(argv[2])
        return f
    if cmd == "reject":
        f = {"cmd": "reject", "match": argv[1], "errorCode": argv[2]}
        if len(argv) > 3:
            f["session"] = argv[3]
        if argv[2] == "client_upgrade_required":
            f["nextAction"] = "upgrade_reader_for_mixing"
        return f
    if cmd == "login_mangle":
        # match both auth paths so whichever the app uses is caught
        return {"cmd": "login_mangle", "mode": argv[1], "match": "scram_proof_requested",
                "count": 1}
    if cmd == "save_plan":
        return {"cmd": "save_mix_plan", "collectionId": argv[1],
                "mixerCodes": argv[2].split(",")}
    raise SystemExit(f"unknown command {cmd!r}")


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    frame = build(sys.argv[1:])
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="ppnam_simctl",
                    transport="websockets", protocol=mqtt.MQTTv5)
    c.ws_set_options(path="/mqtt")
    c.tls_set()
    c.username_pw_set("admin", "admin")
    c.connect(HOST, PORT, keepalive=20)
    c.loop_start()
    time.sleep(1.0)
    info = c.publish(TOPIC, json.dumps(frame), qos=1)
    info.wait_for_publish(timeout=5)
    print("SENT", json.dumps(frame))
    # login_mangle for the app's login path also needs a login_requested variant armed
    if frame.get("cmd") == "login_mangle":
        alt = dict(frame, match="login_requested")
        c.publish(TOPIC, json.dumps(alt), qos=1).wait_for_publish(timeout=5)
        print("SENT", json.dumps(alt))
    time.sleep(0.6)
    c.loop_stop()
    c.disconnect()


if __name__ == "__main__":
    main()
