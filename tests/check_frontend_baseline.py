import hashlib
import json
from pathlib import Path


baseline_path = Path("/app/test_reports/vesper_native_parity/frontend-before.json")
frontend_root = Path("/app/frontend")

baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
mismatches = []
missing = []

for rel, expected_hash in baseline.items():
    file_path = frontend_root / rel
    if not file_path.exists():
        missing.append(rel)
        continue
    digest = hashlib.sha256(file_path.read_bytes()).hexdigest()
    if digest != expected_hash:
        mismatches.append(rel)

print(f"baseline_entries={len(baseline)}")
print(f"missing={len(missing)}")
print(f"mismatches={len(mismatches)}")
if missing:
    print("missing_examples=" + ",".join(missing[:5]))
if mismatches:
    print("mismatch_examples=" + ",".join(mismatches[:5]))
raise SystemExit(1 if missing or mismatches else 0)