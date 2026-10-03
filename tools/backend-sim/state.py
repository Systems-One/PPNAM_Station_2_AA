"""In-memory world state for the Station 2 backend simulator (contract v4.0).

Everything is plain dicts so the whole world can be JSON-snapshotted after
each mutation. State lives only for the process lifetime — a restart is a
fresh, repeatable test world.
"""

import glob
import hashlib
import json
import os
import secrets
from datetime import datetime, timedelta, timezone


def utc_now():
    return datetime.now(timezone.utc)


def iso(dt):
    """Contract v4.1: UTC RFC 3339 with EXACTLY six fractional digits and 'Z'.

    strftime has no microsecond-only directive that also handles the zero case the way we need
    (%f is 6 digits, but only via the datetime's own microsecond field), so this formats the
    seconds and the fraction separately rather than relying on isoformat(), which drops the
    fractional part entirely when microsecond == 0 — the same trap Instant.toString() has on the
    Android side.
    """
    if dt is None:
        return None
    return "%s.%06dZ" % (dt.strftime("%Y-%m-%dT%H:%M:%S"), dt.microsecond)


def parse_iso(s):
    """Accept 'Z' or offset ISO 8601."""
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    return datetime.fromisoformat(s)


class World:
    # The five fixed v4.0 mixing areas (contract §6). Server-authoritative.
    MIXING_AREAS = ("DolciBulkMixing", "MainMixingRoom", "JandiBulkMixing",
                    "MackieBulkMixing", "RajooMachineMixing")

    def __init__(self, seed_dir, log):
        self.log = log
        with open(os.path.join(seed_dir, "seed.json"), encoding="utf-8") as f:
            seed = json.load(f)
        self.config = seed["config"]
        self.operators = seed["operators"]
        self.materials = seed.get("materials", {})

        self.equipment = {}
        for e in seed["equipment"]:
            self.equipment[e["machineCode"]] = {
                "machineCode": e["machineCode"],
                "displayName": e["displayName"],
                "mixingArea": e["mixingArea"],
                "equipmentRole": e["equipmentRole"],
                "productLayer": e.get("productLayer"),
                "validDestinationMachineCodes": list(e.get("validDestinationMachineCodes", [])),
                "routeDescription": e.get("routeDescription", ""),
                "isEnabled": e.get("isEnabled", True),
                "status": e.get("status", "Available"),
                "inactiveReason": e.get("inactiveReason"),
                "currentCycleId": None,
                "currentProductionOrderDocumentNumber": None,
                "currentMixBatchIds": [],
            }
        # Main-room rule ("any of exactly 25 production extruders"): a mixer that
        # names no explicit destinations may feed every production machine in its
        # own area. Fixed routes (DOLCI pairs, JANDI, Mackie, Rajoo) stay in seed.
        for eq in self.equipment.values():
            if eq["equipmentRole"] == "Mixer" and not eq["validDestinationMachineCodes"]:
                eq["validDestinationMachineCodes"] = sorted(
                    code for code, other in self.equipment.items()
                    if other["mixingArea"] == eq["mixingArea"]
                    and other["equipmentRole"] == "ProductionMachine")

        self.pallets = {p["palletRfidTag"]: p for p in seed["pallets"]}

        # SAP production orders from sample dumps
        self.sap_orders = {}
        for path in sorted(glob.glob(os.path.join(seed_dir, "prod-*.json"))):
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            for order in data.get("value", []):
                if "DocumentNumber" in order:
                    self.sap_orders[str(order["DocumentNumber"])] = order
        log.step(f"seed loaded: {len(self.operators)} operators, "
                 f"{len(self.equipment)} equipment across {len(self.MIXING_AREAS)} areas, "
                 f"{len(self.pallets)} pallets, {len(self.sap_orders)} SAP orders "
                 f"({', '.join(self.sap_orders)})")

        # dynamic state
        self.sessions = {}          # deviceId -> session dict
        self.presence = {}          # deviceId -> "online"/"offline"
        self.collections = {}       # COL_ -> collection dict
        self.mix_batches = {}       # MIX_ -> mix batch dict
        self.cycles = {}            # CYC_/RUN_ -> cycle dict (production cycles use their RUN_ id)
        self.runs = {}              # RUN_ -> production run dict
        self.replay = {}            # (device, reqType, messageId) -> {"hash", "topic", "response"}
        self.known_devices = set()  # auto-registered handheld ids
        # 4.1 SCRAM: in-flight challenges and issued single-use manager tokens.
        self.scram_challenges = {}  # challengeId -> challenge dict
        self.auth_tokens = {}       # token -> {operator, deviceId, managerAction, actionTarget, ...}
        # rev2.1
        self.rev2_jobs = {}           # JC -> job dict (the General `job` snapshot shape)
        self.rev2_preparations = {}   # PREP_ id -> preparation dict
        self.general_readers = set()  # devices that made an authenticated General request
        self.auth_replies = {}        # (device, suffix, messageId) -> {"body", "data", "expires"}
        # 4.1 cross-area mixer plans. Keyed by collectionId; saved in Station 2 (WPF), never by
        # the handheld — the simulator seeds/creates them the same way.
        self.mix_plans = {}         # collectionId -> plan dict
        self.mix_destinations = []  # durable mix -> production machine links, including history
        self._counters = {"COL": 0, "MIX": 0, "CYC": 0, "RUN": 0, "RSP": 0, "CHL": 0, "TOK": 0,
                          "MPL": 0}

    # ---- ids ------------------------------------------------------------
    def next_id(self, kind):
        self._counters[kind] += 1
        return f"{kind}_{self._counters[kind]:06d}"

    def next_response_id(self):
        self._counters["RSP"] += 1
        return f"S2-{self._counters['RSP']:06d}"

    def active_jobs_revision(self):
        """A snapshot revision for the active-collection queue (4.1 paging).

        Derived from the collections' identities and statuses rather than a mutation counter, so
        it changes exactly when a page's contents could have changed and stays stable when
        nothing relevant moved.
        """
        material = ";".join(
            f"{c['collectionId']}:{c['status']}"
            for c in sorted(self.collections.values(), key=lambda c: c["collectionId"])
        )
        return hashlib.sha256(material.encode("utf-8")).hexdigest()[:12]

    def expire_auth_replies(self, now):
        for key in [k for k, v in self.auth_replies.items() if v["expires"] < now]:
            del self.auth_replies[key]

    def next_challenge_id(self):
        self._counters["CHL"] += 1
        return f"CHL_{self._counters['CHL']:06d}"

    # ---- 4.1 manager authorization tokens --------------------------------
    def issue_authorization_token(self, operator, device_id, manager_action, action_target,
                                  ttl_seconds=60):
        """Mint a single-use token scoped to one device, one action and one target."""
        self._counters["TOK"] += 1
        token = f"AUTH_{self._counters['TOK']:06d}_{secrets.token_urlsafe(18)}"
        record = {
            "token": token,
            "operatorId": operator["operatorId"],
            "deviceId": device_id,
            "managerAction": manager_action,
            "actionTarget": action_target,
            "expiresAt": utc_now() + timedelta(seconds=ttl_seconds),
            "used": False,
        }
        self.auth_tokens[token] = record
        return record

    def consume_authorization_token(self, token, device_id, manager_action, target_id=None):
        """Validate and CONSUME a manager authorization token.

        Every bound property is checked, not just the token's existence: the contract's guarantee
        is that a token "cannot be reused, moved to another device, target, or manager action",
        and a check that only proves the token was once valid delivers none of that.

        Returns (approver_or_None, reason_or_None).
        """
        record = self.auth_tokens.get(token)
        if not record:
            return None, "authorization token is unknown"
        if record["used"]:
            return None, "authorization token was already used"
        if utc_now() > record["expiresAt"]:
            return None, "authorization token has expired"
        if record["deviceId"] != device_id:
            return None, "authorization token was issued to another device"
        if record["managerAction"] != manager_action:
            return None, (f"authorization token is scoped to '{record['managerAction']}', "
                          f"not '{manager_action}'")
        if target_id is not None:
            expected = record["actionTarget"].split(":", 1)[-1] if ":" in record["actionTarget"] \
                else record["actionTarget"]
            if expected != target_id:
                return None, (f"authorization token is scoped to target '{record['actionTarget']}', "
                              f"not '{target_id}'")
        record["used"] = True
        approver = self.operator_by_id(record["operatorId"])
        if not approver:
            return None, "the approving operator no longer exists"
        return approver, None

    # ---- mixing areas ----------------------------------------------------
    def equipment_in_area(self, area):
        return [e for e in self.equipment.values() if e["mixingArea"] == area]

    # ---- operators / sessions -------------------------------------------
    def find_operator(self, username=None, badge=None):
        for op in self.operators:
            if username is not None and op["username"] == username:
                return op
            if badge is not None and op.get("badgeTag") == badge:
                return op
        return None

    def operator_by_id(self, operator_id):
        for op in self.operators:
            if op["operatorId"] == operator_id:
                return op
        return None

    def create_session(self, device_id, operator):
        old = self.sessions.get(device_id)
        if old and old["state"] != "Closed":
            old["state"] = "Closed"
            self.log.transition(f"session {old['sessionId']} on {device_id} -> Closed (replaced by new login)")
        now = utc_now()
        session = {
            "sessionId": f"SES-{device_id}-{now.strftime('%Y%m%d%H%M%S')}",
            "deviceId": device_id,
            "operatorId": operator["operatorId"],
            "state": "Active",
            "createdAtUtc": iso(now),
            "expiresAtUtc": iso(now + timedelta(hours=self.config["sessionHours"])),
        }
        self.sessions[device_id] = session
        self.log.transition(f"session {session['sessionId']} on {device_id} -> Active "
                            f"(operator {operator['operatorId']} {operator['displayName']}, "
                            f"expires {session['expiresAtUtc']})")
        return session

    # ---- 4.1 cross-area mixer plans -------------------------------------
    def save_mix_plan(self, col_id, mixer_codes, plan_status="Saved"):
        """Model a WPF/Core-saved cross-area mixer plan for one collection (4.1 §7).

        The handheld NEVER saves plans; Station 2 (WPF) does, out of band of the MQTT
        handheld contract. The simulator seeds them this same way at startup and the
        selftest calls this directly. Reserves the given mixers and moves the collection
        to `MixingPlanned`. Returns the plan dict."""
        col = self.collections.get(col_id)
        if not col:
            raise KeyError(f"unknown collection {col_id}")
        self._counters["MPL"] += 1
        plan_id = f"MPL_{self._counters['MPL']:06d}"
        items = []
        for i, code in enumerate(mixer_codes, start=1):
            eq = self.equipment.get(code)
            items.append({
                "planItemId": f"{plan_id}-{i}",
                "mixingArea": eq["mixingArea"] if eq else None,
                "machineCode": code,
                "mixerDisplayName": eq["displayName"] if eq else code,
                "fixedDestinationMachineCode": None,
                "status": "Reserved",
                "mixBatchId": None,
                "cycleId": None,
            })
        plan = {
            "mixPlanId": plan_id,
            "collectionId": col_id,
            "jobCardNumber": col["jobCardNumber"],
            "status": plan_status,
            "plannedMixerCodes": list(mixer_codes),
            "startedMixerCodes": [],
            "remainingMixerCodes": list(mixer_codes),
            "items": items,
        }
        self.mix_plans[col_id] = plan
        col["status"] = "MixingPlanned"
        if self.log:
            self.log.transition(f"mix plan {plan_id} saved for collection {col_id} "
                                f"(job {col['jobCardNumber']}): reserves "
                                f"{', '.join(mixer_codes)} -> MixingPlanned")
        return plan

    def get_session(self, device_id, session_id):
        """Return the session if valid+usable for this device, resuming a Suspended one.
        Returns (session, error) where error is None or a reason string."""
        s = self.sessions.get(device_id)
        if not s or s["sessionId"] != session_id:
            return None, "no session with that id exists on this device"
        if parse_iso(s["expiresAtUtc"]) <= utc_now():
            if s["state"] != "Closed":
                s["state"] = "Closed"
                self.log.transition(f"session {s['sessionId']} on {device_id} -> Closed (expired)")
            return None, "session has expired"
        if s["state"] == "Closed":
            return None, "session is closed"
        if s["state"] == "Suspended":
            s["state"] = "Active"
            self.log.transition(f"session {s['sessionId']} on {device_id} -> Active "
                                f"(resumed by valid request; presence is a hint, not a gate)")
        return s, None

    def presence_change(self, device_id, status):
        prev = self.presence.get(device_id)
        self.presence[device_id] = status
        if prev != status:
            self.log.transition(f"presence {device_id}: {prev or '(unseen)'} -> {status}")
        s = self.sessions.get(device_id)
        if not s or s["state"] == "Closed":
            return
        if status == "offline" and s["state"] == "Active":
            s["state"] = "Suspended"
            self.log.transition(f"session {s['sessionId']} on {device_id} -> Suspended (device went offline)")
        elif status == "online" and s["state"] == "Suspended":
            s["state"] = "Active"
            self.log.transition(f"session {s['sessionId']} on {device_id} -> Active (device back online)")

    # ---- privileged approval ----------------------------------------------
    def check_approver(self, username, password, action_id):
        """Authenticate manager credentials and check the APPROVER's allowedActions.
        Returns (approver_or_None, reason_or_None)."""
        op = self.find_operator(username=username)
        if not op or op["password"] != password:
            return None, "approver credentials are invalid"
        if action_id not in op["allowedActions"]:
            return None, f"approver '{username}' does not hold action id '{action_id}'"
        return op, None

    # ---- materials / bag weights -------------------------------------------
    def full_bag_weight(self, material_code):
        """None means bulk material (no bag size)."""
        mat = self.materials.get(material_code, {})
        if mat.get("bulk"):
            return None
        return mat.get("fullBagWeight", self.config["defaultFullBagWeightKg"])

    def available_quantity(self, material_code):
        """Sum of remaining quantity across usable Holding pallets of this product."""
        total = 0.0
        for p in self.pallets.values():
            if (p["productCode"] == material_code and p["palletState"] == "Holding"
                    and not p["blocked"]):
                total += p["remainingQuantity"]
        return round(total, 3)

    # ---- pallet helpers ------------------------------------------------------
    @staticmethod
    def pallet_usable(p):
        return (p["palletState"] in ("Holding", "Mixing") and not p["blocked"]
                and p["remainingQuantity"] > 0)

    @staticmethod
    def pallet_recoverable(p):
        return p["palletState"] in ("AtStation1", "Unknown")

    # ---- replay store --------------------------------------------------------
    @staticmethod
    def body_hash(payload_bytes):
        return hashlib.sha256(payload_bytes).hexdigest()

    def replay_lookup(self, device_id, request_type, message_id):
        return self.replay.get((device_id, request_type, message_id))

    def replay_store(self, device_id, request_type, message_id, body_hash, topic, response):
        self.replay[(device_id, request_type, message_id)] = {
            "hash": body_hash, "topic": topic, "response": response,
        }

    # ---- snapshotting -----------------------------------------------------------
    def to_dict(self):
        return {
            "sessions": self.sessions,
            "presence": self.presence,
            "equipment": self.equipment,
            "pallets": self.pallets,
            "collections": self.collections,
            "mixBatches": self.mix_batches,
            "cycles": self.cycles,
            "runs": self.runs,
            "counters": self._counters,
            "knownDevices": sorted(self.known_devices),
        }
