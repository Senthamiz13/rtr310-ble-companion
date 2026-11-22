# Contributing

Thanks for helping improve the independent RTR 310 BLE companion.

## Before opening a change

- Keep iOS code in `ios/`, Android code in `android/`, Python tools in `scripts/`, and shared protocol notes in `docs/`.
- Explain whether a protocol statement is observed, inferred, or still unknown. Include only sanitized sample frames when they help explain a change.
- Do not commit TVS Connect APKs, decompiled source, copied TVS artwork, pairing material, raw HCI captures, caller/rider data, vehicle identifiers, or document images.
- Do not add credentials, signing certificates, provisioning profiles, or local configuration.
- Avoid sending undocumented BLE writes to a motorcycle. Document the frame and its evidence before adding a write path.

## Local checks

For Python changes, install `requirements.txt` in a virtual environment and run the protocol checks:

```sh
.venv/bin/python tests/test_bike_protocol.py
.venv/bin/python tests/test_document_protocol.py
```

For iOS changes, open `ios/TVSBike.xcodeproj` in Xcode and build the `TVSBike` scheme. Bluetooth behavior requires testing on a physical device and stationary motorcycle; the simulator cannot validate BLE behavior.

## Pull request notes

Describe the behavior changed, the evidence for protocol changes, what you checked, and any unverified units or state mappings. Do not attach raw device captures; summarize them after removing identifying and personal data.
