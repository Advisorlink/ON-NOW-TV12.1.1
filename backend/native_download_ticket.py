"""Five-minute APK-only bearer tickets; the original capability never enters a URL."""
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import time

TICKET_TTL = 300
TICKET_PATH = "/api/download/vesper-native-file"


class TicketLogFilter(logging.Filter):
    def filter(self, record):
        if isinstance(record.args, tuple) and len(record.args) == 5:
            address, method, target, protocol, status = record.args
            if isinstance(target, str) and "?" in target and target.partition("?")[0] in (TICKET_PATH, "/api/download/vesper-native-apk"):
                record.args = (address, method, target.partition("?")[0] + "?[redacted]", protocol, status)
        return True


access_logger = logging.getLogger("uvicorn.access")
if not any(isinstance(item, TicketLogFilter) for item in access_logger.filters):
    access_logger.addFilter(TicketLogFilter())


def authorized_capability(value):
    expected = os.environ.get("APK_DOWNLOAD_CAPABILITY_TOKEN")
    return bool(expected and value and secrets.compare_digest(value.encode(), expected.encode()))


def _encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _signature(body):
    capability = os.environ.get("APK_DOWNLOAD_CAPABILITY_TOKEN")
    if not capability:
        return None
    key = hashlib.sha256(capability.encode()).digest()
    return _encode(hmac.new(key, body.encode("ascii"), hashlib.sha256).digest())


def artifact_identity(stat):
    return f"{stat.st_size}:{stat.st_mtime_ns}"


def issue_ticket(stat):
    payload = {"v": 1, "aud": "vesper-native-private-apk", "exp": int(time.time()) + TICKET_TTL,
               "artifact": artifact_identity(stat), "jti": secrets.token_urlsafe(16)}
    body = _encode(json.dumps(payload, separators=(",", ":")).encode())
    signature = _signature(body)
    if not signature:
        raise ValueError("Private downloads unavailable")
    return f"{body}.{signature}"


def valid_ticket(ticket, stat):
    if not isinstance(ticket, str) or len(ticket) > 2048 or not re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}", ticket):
        return False
    body, supplied = ticket.split(".")
    expected = _signature(body)
    if not expected or not hmac.compare_digest(supplied, expected):
        return False
    try:
        payload = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
        return (isinstance(payload, dict) and payload.get("v") == 1 and
                payload.get("aud") == "vesper-native-private-apk" and
                type(payload.get("exp")) is int and int(time.time()) < payload["exp"] <= int(time.time()) + TICKET_TTL and
                payload.get("artifact") == artifact_identity(stat))
    except (ValueError, TypeError, UnicodeError):
        return False