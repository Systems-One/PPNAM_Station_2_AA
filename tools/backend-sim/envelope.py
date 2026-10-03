"""rev2.1 envelope handling, mirroring PPNAM.Station2.Core/Services/Rev2ScannerProcessor.cs.

Request validation, in the server's order:
  1. The suffix is one of the five subscribed request suffixes, else client_upgrade_required.
  2. The payload is at most 65,536 characters, else invalid_envelope.
  3. It is a JSON object with unique field names (case-insensitive), else invalid_envelope.
     (The server checks the top level only; this is stricter, at any depth.)
  4. No top-level field name contains "password" (case-insensitive), else password_field_forbidden.
  5. schemaVersion == "rev2.1", deviceId == topic device, messageId is 1-100 chars, timestampUtc is
     exactly yyyy-MM-ddTHH:mm:ss.ffffffZ, and the device is not "WPF", else invalid_envelope.

Direct replies carry no messageId of their own — correlation is inResponseToMessageId alone.
"""

import json
import re

from state import iso, utc_now

SCHEMA_VERSION = "rev2.1"
REQUEST_SUFFIXES = (
    "scram_start_requested",
    "scram_proof_requested",
    "rev2_general_requested",
    "rev2_rajoo_requested",
    "operator_list_requested",
)
_RESPONSE_SUFFIX = {
    "scram_start_requested": "scram_challenge",
    "scram_proof_requested": "scram_proof_result",
    "rev2_rajoo_requested": "rev2_rajoo_result",
    "operator_list_requested": "operator_list",
}
MAX_PAYLOAD_CHARS = 65536
TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")

SUCCESS_NEXT_ACTION = "Follow the saved job or preparation state."
FAILURE_NEXT_ACTION = ("Correct the request with a new message ID; uncertain transport retries "
                       "keep the identical body.")


class Rejection(Exception):
    """Raised to short-circuit into a failed reply. `data` is the snapshot to include, if any."""

    def __init__(self, error, message, data=None):
        super().__init__(message)
        self.error = error
        self.message = message
        self.data = data


def response_suffix(request_suffix):
    """Every unknown suffix answers on rev2_general_result, exactly as the server does."""
    return _RESPONSE_SUFFIX.get(request_suffix, "rev2_general_result")


def reply(device_id, message_id, received_at, success, message, error="", data=None):
    sent_at = utc_now()
    return {
        "schemaVersion": SCHEMA_VERSION,
        "deviceId": device_id,
        "inResponseToMessageId": message_id,
        "receivedAtUtc": iso(received_at),
        "sentAtUtc": iso(sent_at),
        "durationMs": (sent_at - received_at).total_seconds() * 1000.0,
        "success": success,
        "error": error,
        "operatorMessage": message,
        "nextAction": SUCCESS_NEXT_ACTION if success else FAILURE_NEXT_ACTION,
        "data": data,
    }


def _unique_fields(pairs):
    seen = set()
    for name, _ in pairs:
        if name.lower() in seen:
            raise Rejection("invalid_envelope", "Request must be a JSON object with unique fields.")
        seen.add(name.lower())
    return dict(pairs)


def validate(topic_device, suffix, payload_bytes, ctx):
    """Return the request dict, or raise Rejection.

    ctx["messageId"] receives the request's messageId as soon as it is known, so a rejection can
    still be correlated — and stays "" when the failure came before the id could be read, which is
    exactly what the server does.
    """
    if suffix not in REQUEST_SUFFIXES:
        raise Rejection("client_upgrade_required", "Use the Rev 2 scanner contract.")
    text = payload_bytes.decode("utf-8", errors="replace")
    if len(text) > MAX_PAYLOAD_CHARS:
        raise Rejection("invalid_envelope", "Request too large.")
    try:
        req = json.loads(text, object_pairs_hook=_unique_fields)
    except Rejection:
        raise
    except ValueError:
        raise Rejection("invalid_envelope", "Invalid JSON or field type.")
    if not isinstance(req, dict):
        raise Rejection("invalid_envelope", "Request must be a JSON object with unique fields.")

    message_id = req.get("messageId")
    ctx["messageId"] = message_id if isinstance(message_id, str) else ""

    if any("password" in name.lower() for name in req):
        raise Rejection("password_field_forbidden",
                        "Use SCRAM authentication; password fields are prohibited.")

    timestamp = req.get("timestampUtc")
    if (req.get("schemaVersion") != SCHEMA_VERSION
            or req.get("deviceId") != topic_device
            or not isinstance(message_id, str) or not 1 <= len(message_id) <= 100
            or not isinstance(timestamp, str) or not TIMESTAMP_RE.match(timestamp)
            or topic_device.lower() == "wpf"):
        raise Rejection("invalid_envelope",
                        "Check schemaVersion, deviceId, messageId and the six-digit UTC timestamp.")
    return req
