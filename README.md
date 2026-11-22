# RTR 310 BLE Companion

An independent project exploring Bluetooth Low Energy communication with a TVS Apache RTR 310. The repository contains a native iOS client, Python utilities for macOS, and protocol notes based on the bike owner's own observations and captures.

> **Unofficial project:** This project is not affiliated with, endorsed by, or supported by TVS Motor Company. TVS, Apache, and RTR are trademarks of their respective owners. Product names are used only to identify compatibility.
>
> **Use safely:** This is an experimental companion, not safety-critical equipment. Do not operate the app or interact with the dashboard while riding. Test BLE features with the motorcycle stationary. Protocol behavior can vary by model year and firmware.

## What's here

- **iOS app** — SwiftUI and Core Bluetooth client for connection, telemetry, phone-status frames, rider display text, and Digi Docs transfer.
- **Python tools** — read-only BLE probe, protocol packet helpers, and a local telemetry dashboard for macOS.
- **Protocol notes** — frame formats and reverse-engineering findings, with confidence and unknowns called out.
- **Android app** — planned. The original TVS Connect APK is not part of this repository; no independent Android client source is included yet.

## Repository layout

```text
ios/        Native iOS app and Xcode project
android/    Android client landing page; source project not added yet
scripts/    Python BLE probe, protocol helpers, and local web dashboard
tests/      Offline checks for Python packet and dashboard behavior
docs/       Protocol notes and app-feature analysis
local_data/ Private, local-only APKs and BLE/session captures (ignored by Git)
```

## Python dashboard

Requires Python 3.10 or newer and a Mac with Bluetooth LE. Create an isolated environment and install dependencies:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python scripts/bike_web.py
```

Open <http://127.0.0.1:8765>. The dashboard binds to localhost by default. Keep the bike nearby with ignition on and pair through macOS if prompted.

The standalone probe scans and listens without writing to the bike by default:

```sh
.venv/bin/python scripts/ble_probe.py
```

The optional `--send-rider-name` argument sends a BLE write. Use it only when you intend to test that operation.

## iOS app

Open `ios/TVSBike.xcodeproj` in Xcode, select the `TVSBike` scheme, set your own development team and bundle identifier, then install on a physical iPhone. See [`ios/README.md`](ios/README.md) for setup and current feature notes. BLE pairing, background behavior, and document transfer require a physical device and bike.

## Android app

The `android/` directory currently contains setup notes only. Add an independently developed Android Studio project there when it is ready. Do not add the proprietary TVS Connect APK or extracted APK assets.

## Protocol documentation

Start with [`docs/bike_ble_protocol.md`](docs/bike_ble_protocol.md). The notes distinguish observed packet fields from inferred meanings; some field units and state-bit polarities remain unverified. [`docs/btsnoop_analysis.md`](docs/btsnoop_analysis.md) summarizes sanitized capture findings. Raw captures stay local under `local_data/` and are not needed to build the clients.

## Data and privacy

Raw HCI captures can contain device identifiers, pairing traffic, personal display text, caller information, or document image data. Keep them local and inspect/redact them before sharing. Session logs and local datasets are excluded by `.gitignore`.

## License

No license is granted for reuse at this time. Public visibility on GitHub does not itself grant permission to copy, modify, or redistribute this project's code. Third-party marks and materials remain the property of their respective owners.
