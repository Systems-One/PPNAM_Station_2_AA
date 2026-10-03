"""rev2.1 operator directory (pre-login), October 3 fleet-parity amendment.

operator_list_requested -> operator_list    data: {"operators": [{"username", "displayName"}, ...]}

The request is the bare envelope: no sessionId, no purpose, no other fields. Replay and
message-id-conflict checks live in sim.py, in the same 60 s authentication cache as SCRAM.

Content, after the Station 1 contract (3.2.0 §4.5): the active accounts that could complete a
SCRAM password login here -- in the simulator, every seeded operator with a password. Exactly two
properties per entry, displayName falling back to username, sorted by displayName
case-insensitively. An empty list is a valid success.
"""


def operator_list(world, log, req):
    entries = []
    for op in world.operators:
        username = (op.get("username") or "").strip()
        if not username or not op.get("password"):
            continue
        display = (op.get("displayName") or "").strip() or username
        entries.append({"username": username, "displayName": display})
    entries.sort(key=lambda e: e["displayName"].lower())
    log.step("operator_list: %d operator(s) for %s" % (len(entries), req["deviceId"]))
    return {"operators": entries}
