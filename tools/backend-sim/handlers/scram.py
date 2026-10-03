"""rev2.1 SCRAM-SHA-256 login (RFC 7677).

scram_start_requested -> scram_challenge    data: the challenge
scram_proof_requested -> scram_proof_result data: serverSignature + session

Purpose, replay and message-id-conflict checks live in sim.py, ahead of these handlers, because
the server makes them before touching the SCRAM service.
"""

import base64
import hashlib
import hmac
import os
import unicodedata
from datetime import timedelta

from envelope import Rejection
from state import iso, utc_now

SCRAM_ITERATIONS = 4096
CHALLENGE_TTL_SECONDS = 60


def _salted_password(password, salt, iterations):
    """PBKDF2-HMAC-SHA-256 over the NFKC-normalized password's UTF-8 bytes."""
    normalized = unicodedata.normalize("NFKC", password).encode("utf-8")
    return hashlib.pbkdf2_hmac("sha256", normalized, salt, iterations)


def _hmac(key, data):
    return hmac.new(key, data, hashlib.sha256).digest()


def _escape_username(username):
    """RFC 5802 5.1: '=' before ',' — the other order re-escapes the '=' it just introduced."""
    return username.replace("=", "=3D").replace(",", "=2C")


def scram_start(world, log, req):
    """Issue a challenge — even for an unknown username, so this is not a user-enumeration oracle.
    An unknown user fails at the proof step exactly as a wrong password does."""
    username = req.get("username") or ""
    client_nonce = req.get("clientNonce") or ""
    if not username or not client_nonce:
        raise Rejection("authentication_failed", "username and clientNonce are required.")

    operator = world.find_operator(username=username)
    salt = hashlib.sha256(("ppnam-salt::" + username).encode("utf-8")).digest()[:16]
    server_nonce = client_nonce + base64.b64encode(os.urandom(18)).decode("ascii")
    server_first = "r=%s,s=%s,i=%d" % (
        server_nonce, base64.b64encode(salt).decode("ascii"), SCRAM_ITERATIONS)

    challenge_id = world.next_challenge_id()
    world.scram_challenges[challenge_id] = {
        "challengeId": challenge_id,
        "username": username,
        "deviceId": req["deviceId"],
        "clientNonce": client_nonce,
        "serverNonce": server_nonce,
        "salt": salt,
        "iterations": SCRAM_ITERATIONS,
        "serverFirstMessage": server_first,
        "operatorId": operator["operatorId"] if operator else None,
        "createdAt": utc_now(),
        "used": False,
    }
    log.step("scram: issued challenge %s for '%s'" % (challenge_id, username))
    return {
        "challengeId": challenge_id,
        "serverNonce": server_nonce,
        "salt": base64.b64encode(salt).decode("ascii"),
        "iterations": SCRAM_ITERATIONS,
        "serverFirstMessage": server_first,
        "expiresAtUtc": iso(utc_now() + timedelta(seconds=CHALLENGE_TTL_SECONDS)),
    }


def scram_proof(world, log, req):
    """Verify the client proof and issue a device-bound session."""
    challenge_id = req.get("challengeId")
    client_final = req.get("clientFinalWithoutProof") or ""
    client_proof_b64 = req.get("clientProof") or ""

    challenge = world.scram_challenges.get(challenge_id)
    if not challenge:
        log.fail("scram: unknown challengeId '%s'" % challenge_id)
        raise Rejection("authentication_failed", "Authentication challenge is unknown or expired.")
    if challenge["used"]:
        log.fail("scram: challenge %s already used" % challenge_id)
        raise Rejection("authentication_failed", "This authentication challenge was already used.")
    age = (utc_now() - challenge["createdAt"]).total_seconds()
    if age > CHALLENGE_TTL_SECONDS:
        log.fail("scram: challenge %s expired (%.0fs old)" % (challenge_id, age))
        raise Rejection("authentication_failed", "Authentication challenge expired.")
    if challenge["deviceId"] != req["deviceId"]:
        log.fail("scram: challenge was issued to a different device")
        raise Rejection("authentication_failed", "This challenge belongs to another device.")
    challenge["used"] = True

    operator = world.operator_by_id(challenge["operatorId"]) if challenge["operatorId"] else None
    if not operator:
        log.fail("scram: no such operator '%s'" % challenge["username"])
        raise Rejection("authentication_failed", "Invalid credentials.")

    salted = _salted_password(operator["password"], challenge["salt"], challenge["iterations"])
    client_key = _hmac(salted, b"Client Key")
    stored_key = hashlib.sha256(client_key).digest()
    auth_message = ("n=%s,r=%s,%s,%s" % (
        _escape_username(challenge["username"]),
        challenge["clientNonce"],
        challenge["serverFirstMessage"],
        client_final,
    )).encode("utf-8")
    client_signature = _hmac(stored_key, auth_message)
    expected_proof = bytes(a ^ b for a, b in zip(client_key, client_signature))
    try:
        supplied = base64.b64decode(client_proof_b64)
    except Exception:
        supplied = b""
    if not hmac.compare_digest(expected_proof, supplied):
        log.fail("scram: proof mismatch for '%s'" % challenge["username"])
        raise Rejection("authentication_failed", "Invalid credentials.")

    server_key = _hmac(salted, b"Server Key")
    server_signature = base64.b64encode(_hmac(server_key, auth_message)).decode("ascii")

    session = world.create_session(req["deviceId"], operator)
    log.ok("scram login: %s (%s) session %s"
           % (operator["operatorId"], operator["displayName"], session["sessionId"]))
    # The server's ScramProofResult, serialized camelCase. No allowedTabs/allowedActions exist.
    return {
        "serverSignature": server_signature,
        "session": {
            "sessionId": session["sessionId"],
            "operatorId": operator["operatorId"],
            "displayName": operator["displayName"],
            "role": operator["role"],
            "sourceDevice": req["deviceId"],
            "loggedInAtUtc": session["createdAtUtc"],
            "loggedOutAtUtc": None,
            "expiresAtUtc": session["expiresAtUtc"],
            "sessionState": "Active",
            "isActive": True,
        },
        "approver": None,
        "authorizationToken": None,
        "authorizationExpiresAtUtc": None,
    }
