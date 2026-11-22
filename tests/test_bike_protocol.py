"""Checks protocol helpers against sanitized sample frames.

Run:  PYTHONPATH=scripts python3 tests/test_bike_protocol.py
"""

from datetime import datetime
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from bike_protocol import (
    clock_status_packet,
    display_name_packet,
    rider_name_packet,
)

# Sanitized sample text is used so test fixtures do not publish personal names.
CAPTURED = {
    "sample rider name": "5b52b8838e8f98eaeaeaeaeaeaeaeaeaeaeaeaff",
    "sample caller display": "5b43a98b86868f98eaeaeaeaeaeaeaeaeaeaeaff",
    "clock 17:58:58 signal 3, battery bucket 3, network type 0 @79.1s": "5b4ad992eaeaefd0d0ebeaeae4e1f0eaeaeaeaff",
    "clock 17:59:00 signal 2, battery bucket 3, network type 0 @81.1s": "5b4ac992eaeaefd1eaebeaeae4e1f0eaeaeaeaff",
}

BUILT = {
    "sample rider name": rider_name_packet("Rider"),
    "sample caller display": display_name_packet("Caller"),
    "clock 17:58:58 signal 3, battery bucket 3, network type 0 @79.1s": clock_status_packet(datetime(2026, 11, 14, 17, 58, 58), signal_level=3, phone_battery_percent=50, network_type=0),
    "clock 17:59:00 signal 2, battery bucket 3, network type 0 @81.1s": clock_status_packet(datetime(2026, 11, 14, 17, 59, 0), signal_level=2, phone_battery_percent=50, network_type=0),
}

failed = 0
for name, expected in CAPTURED.items():
    got = BUILT[name].hex()
    ok = got == expected
    failed += not ok
    print(("PASS" if ok else "FAIL"), name)
    if not ok:
        print("  expected", expected)
        print("  got     ", got)

raise SystemExit(1 if failed else 0)
