# Local-only analysis data

This folder holds source artifacts that are intentionally not committed:

- `apk/com.tvsm.connect.apk` — original TVS Connect APK (large; keep a legally obtained copy locally).
- `captures/` — raw HCI and BLE captures; these may contain Bluetooth identifiers or pairing-related traffic.
- `session_logs/` — local client session logs, which can include device-specific state and telemetry.

The project `.gitignore` excludes everything in this directory except this note. Keep copies here when reproducing or extending the protocol analysis.
