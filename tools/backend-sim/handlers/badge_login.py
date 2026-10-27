"""badge_login_requested -> badge_login_result: contract 3.1 Android card login.

The common envelope plus `badgeTag` (the complete card EPC), nothing else: a sessionId, action,
holder, role or username is refused. The tag is trimmed and matched case-insensitively against the
seeded operators' `badgeTag`.

Durable receipts keyed (device, message id), as station2_badge_login_receipts: an exact retry
returns the same still-valid session without creating another; a changed body is
message_id_conflict; a session that has since closed or expired is operator_session_invalid; a
recorded rejection stays rejected under that id.
"""

from envelope import Rejection
from handlers.scram import session_dto

FORBIDDEN = ("sessionid", "action", "username", "operatorid", "holderid", "role")


def badge_login(world, log, req, raw):
    """Return the `data` for a successful card login, or raise Rejection."""
    key = (req["deviceId"], req["messageId"])
    prior = world.badge_receipts.get(key)
    if prior is not None:
        if prior["body"] != raw:
            log.fail("badge login %s: same message id, different body" % key[1])
            raise Rejection("message_id_conflict", "Message ID body mismatch.")
        if prior["sessionId"] is None:
            log.fail("badge login %s: retry of a recorded rejection" % key[1])
            raise Rejection("badge_rejected", "Card is unavailable, revoked or ambiguous.")
        session, err = world.get_session(req["deviceId"], prior["sessionId"])
        if not session:
            log.fail("badge login %s: retry, but the saved session is unusable (%s)" % (key[1], err))
            raise Rejection("operator_session_invalid", "Sign in on this device before continuing.")
        log.step("badge login %s: exact retry, saved session %s returned" % (key[1], session["sessionId"]))
        return {"session": session_dto(session, world.operator_by_id(session["operatorId"])),
                "capabilities": {"androidBadgeLogin": True}}

    present = [name for name in req if name.lower() in FORBIDDEN]
    tag = req.get("badgeTag")
    if present or not isinstance(tag, str) or not tag.strip():
        raise Rejection("invalid_envelope",
                        "Card login takes only the common envelope and a nonblank badgeTag.")
    tag = tag.strip().upper()
    operator = next((op for op in world.operators
                     if (op.get("badgeTag") or "").strip().upper() == tag), None)
    if operator is None:
        world.badge_receipts[key] = {"body": raw, "sessionId": None}
        log.fail("badge login: no operator holds card %r" % tag)
        raise Rejection("badge_rejected", "Card is unavailable, revoked or ambiguous.")
    session = world.create_session(req["deviceId"], operator)
    world.badge_receipts[key] = {"body": raw, "sessionId": session["sessionId"]}
    log.ok("badge login: %s (%s) session %s"
           % (operator["operatorId"], operator["displayName"], session["sessionId"]))
    return {"session": session_dto(session, operator), "capabilities": {"androidBadgeLogin": True}}
