# TVS Bike iOS app

Native SwiftUI/Core Bluetooth starter app for the Apache RTR 310 BLE protocol.

## Current scope

- Automatically scans for the TVS service when Bluetooth is ready, reconnects to the configured bike, and lets the rider choose a bike in Settings.
- Connects to the known TVS custom GATT service and subscribes to its telemetry characteristic.
- Decodes primary `0x10` / `0x11` telemetry plus additional `0x10`, `0x11`, and `0x12` fields from the APK's field map. Fields with unconfirmed scaling or bit polarity are labeled as raw values.
- Retains the decoded `0x5F` payload for analysis; its field layout is not known yet.
- Displays the Apache riding mode parsed from byte 17's low nibble in frame `0x11`: Urban, Rain, Tour (Sport), Rally (Track), and Supermoto. Codes 9–12 are also mapped to their 0–3 aliases.
- Includes a local Digi Docs photo library with naming, deletion, a 5:3 landscape crop editor, bike document slot selection, and BLE upload with progress and bike acknowledgements.
- Sends the observed rider-name and phone-status frames, with the phone-status update paced by incoming bike notifications for background resilience.
- Stores the selected bike and rider name on the iPhone. Settings can scan and select a bike; once telemetry notifications are active, the app sends the configured rider name in the observed personalized welcome `0x4C` frame.
- Uses Core Bluetooth state restoration and the `bluetooth-central` background mode to restore scanning/connections and reconnect to the bike.
- Keeps diagnostic logs in Xcode’s console and a local session file; the app UI shows telemetry without the debug log panel.

### Background behavior on iPhone

Launch the app once and allow Bluetooth access. After that, the app can reconnect when the bike advertises its TVS service, including while the app is in the background or when iOS relaunches it for a Core Bluetooth event. iOS controls background scan timing and wakeups, so this is not an instant-launch guarantee. Swiping the app away (force-quit) disables automatic background relaunch until the app is opened again. iOS also does not bring the app’s screen to the foreground just because the bike is nearby.

## Open and run

1. Open `TVSBike.xcodeproj` in Xcode.
2. Select the `TVSBike` scheme and an iPhone destination.
3. Set a unique bundle identifier and select your Apple development team under **Signing & Capabilities**.
4. Install on a physical iPhone, allow Bluetooth access, and keep the bike nearby with ignition on. The app searches and connects automatically; use **Scan** to retry or select a device manually.
5. Open Settings to choose the bike and enter the rider name. If the bike requests pairing, complete the system pairing prompt. The app will then discover the service and subscribe to telemetry.

BLE hardware behavior cannot be tested in the iOS simulator; use a physical iPhone to verify pairing, background reconnection, and telemetry.

RTR Companion is a fan-made, open-source project for Apache RTR owners. It is an independent companion and does not replace the official TVS app. It is not affiliated with, endorsed by, or supported by TVS Motor Company. The software is experimental, provided as-is, and used at the rider's own risk. Compatibility and behavior may vary by bike and firmware. Follow the official owner's manual and do not interact with the app while riding. Product names and trademarks belong to their respective owners.

### Digi Docs transfer

Choose a photo, crop and position it, save it, connect to the bike, choose a slot, then tap **Send to bike**. The app fits the crop inside an 800 × 480 canvas, converts it to RGB565, reverses the vertical row order, and sends complete row-major scanlines without a horizontal mirror. It sends the captured `0x53` start frame and 128-byte `0x55` chunks, checks `0x54` progress acknowledgements every 20 chunks, and sends the completion frame. This produces 768,000 bytes (6,000 chunks), matching the supplied HCI capture. Validate with a non-sensitive test image and keep the phone connected until the bike confirms completion.

## Known service layout

| Role | UUID |
|---|---|
| TVS custom service | `5456534d-5647-5341-5342-454e544f5251` |
| Bike notifications | `00005354-0000-1000-8000-00805f9b34fb` |
| Phone writes | `00005352-0000-1000-8000-00805f9b34fb` |

Frames are 20 bytes. For Apache telemetry, bytes 2–18 are XORed with `0xEA`; header, frame ID, and `0xFF` trailer are left unchanged. The bike's `0x6B` music-command notification is an observed exception and is treated as raw.

See [`../../docs/bike_ble_protocol.md`](../../docs/bike_ble_protocol.md) for the full reverse-engineered protocol and confidence notes.
