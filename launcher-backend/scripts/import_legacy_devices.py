"""
import_legacy_devices.py — One-shot bulk import of 568 already-
registered devices from the user's previous launcher backend.

Reads `legacy_devices.tsv` (tab-separated: #, name, model, device_id,
registered_at, status), merges into `data/store.json`, preserving:
  * User-facing name verbatim
  * Original device-id verbatim (32-char uppercase hex)
  * Original registration timestamp (parsed as UTC)
  * Status — "Blocked" → blocked, "Normal" → active
  * `not_hk1: true` tag for any device whose model isn't the
    standard "Amlogic HK1 BOX S905X3"

Idempotent: re-running will UPDATE in-place rather than duplicate
(matched by device-id key).
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

ROOT       = Path(__file__).resolve().parent.parent
TSV_PATH   = ROOT / "scripts" / "legacy_devices.tsv"
STORE_PATH = ROOT / "data"    / "store.json"
HK1_MODEL  = "Amlogic HK1 BOX S905X3"


def parse_ts(raw: str) -> int:
    """Convert 'YYYY-MM-DD HH:MM:SS' (UTC) → Unix epoch SECONDS."""
    return int(
        datetime.strptime(raw.strip(), "%Y-%m-%d %H:%M:%S")
        .replace(tzinfo=timezone.utc)
        .timestamp()
    )


def main() -> None:
    store = json.loads(STORE_PATH.read_text())
    registered = store.setdefault("registered_devices", {})
    before = len(registered)

    imported = updated = 0
    blocked = active = not_hk1 = 0

    for line in TSV_PATH.read_text().splitlines():
        if not line.strip():
            continue
        cols = line.split("\t")
        if len(cols) < 6:
            continue
        _no, name, model, device_id, ts_raw, status_raw = cols[:6]
        device_id = device_id.strip()
        if len(device_id) != 32:
            continue  # malformed row — skip silently

        status = "blocked" if status_raw.strip().lower() == "blocked" else "active"
        ts = parse_ts(ts_raw)
        is_not_hk1 = model.strip() != HK1_MODEL

        if status == "blocked":
            blocked += 1
        else:
            active += 1
        if is_not_hk1:
            not_hk1 += 1

        existed = device_id in registered
        record = {
            "id":            device_id,
            "name":          name.strip(),
            "model":         model.strip(),
            "status":        status,
            "registered_at": ts,
            "last_seen_at":  ts,        # we have no last-seen for legacy rows
            "last_ip":       None,
            "legacy":        True,      # flag so the admin UI can hide the long id
            "not_hk1":       is_not_hk1,
        }
        if existed:
            # Update in place, but never override a manual change the
            # admin has made AFTER the first import: keep current
            # status if the existing record was already manually
            # touched.  Simple heuristic: if `legacy` flag is absent
            # on the existing record, the admin has edited it →
            # leave status alone.
            cur = registered[device_id]
            if not cur.get("legacy"):
                record["status"] = cur.get("status", status)
            updated += 1
        registered[device_id] = record

    STORE_PATH.write_text(json.dumps(store, indent=2))
    after = len(registered)

    print(f"Imported / updated {updated} legacy rows.")
    print(f"Total registered_devices: {before} → {after}.")
    print(f"  Active:  {active}")
    print(f"  Blocked: {blocked}")
    print(f"  NOT HK1: {not_hk1}")


if __name__ == "__main__":
    main()
