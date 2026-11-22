# TVS Connect APK — Reverse Engineering Findings (RTR 310)

**Target:** `local_data/apk/com.tvsm.connect.apk` (TVS Connect, v8.12.3, ~52,669 classes; kept local and excluded from Git)
**Goal:** Understand the BLE communication protocol between the app and the TVS Apache RTR 310
**Method:** Static analysis (REA / decompilation), cross-checked against an nRF Connect log from a real RTR 310 (device address redacted)

---

## 1. Transport layer

- The app uses **Bluetooth Low Energy (GATT)**, not Bluetooth Classic, for current-generation bikes.
- The BLE central/peripheral wrapper (`com.tvsm.connect.blemanager` — `BluetoothCentral`, `BluetoothPeripheral`, `BluetoothBytesParser`) is a repackaged copy of the open-source **Blessed** BLE library. This layer is generic and not TVS-specific.
- A legacy `BluetoothSPP` (Classic serial) path also exists in the app, used by older instrument clusters — **not** relevant to the RTR 310.
- The app negotiates MTU via `requestMtu`, and frames are written to a dedicated write characteristic while subscribing to notifications on a separate read characteristic.

## 2. App architecture — two generations of protocol code

| Layer | Package | Notes |
|---|---|---|
| Legacy / shared base | `com.tvsm.connect.bluetooth.bluetoothlegatt` | `BaseConnectHelperService`, `SampleGattAttributes`, per-model `HelperService` classes (`N251`, `N360`, `U324`, `U368`, `U399`, `U400`, `U408`, `U449`) |
| Newer, Kotlin-based protocol module | `com.tvs.bike.core.protocol.apache` | Dedicated package for the **Apache RTR series** (includes RTR 310) |

The RTR 310 uses the **newer `apache` protocol module**, confirmed by matching its real BLE service UUID (captured via nRF Connect) against the app's code.

## 3. Confirmed BLE identifiers (RTR 310)

Captured live from the bike in nRF Connect and matched against the app:

| Item | UUID | Decoded meaning |
|---|---|---|
| Custom service | `5456534d-5647-5341-5342-454e544f5251` | ASCII = **"TVSMVGSASBENTORQ"** (TVS internal module name) |
| Notify characteristic | `00005354-0000-1000-8000-00805f9b34fb` | ASCII bytes `0x53 0x54` = **"ST"** (Status/Stream — bike → phone) |
| Write characteristic | `00005352-0000-1000-8000-00805f9b34fb` | ASCII bytes `0x53 0x52` = **"SR"** (Send/Receive — phone → bike) |

Standard BLE services also present (Generic Access, Generic Attribute, Device Information) — nothing TVS-specific in those.

## 4. Frame structure (incoming, bike → phone)

Decompiled from `com.tvs.bike.core.protocol.apache.ApacheIncomingFrameIdentifier.getFrameConfig()`:

- **Every incoming frame starts with byte `0x5A`.**
- **The second byte selects the frame type**, which determines how the rest of the payload is interpreted:

| 2nd byte | Frame type | Data class | Fields (by name, from class inspection) |
|---|---|---|---|
| `0x10` | Speedometer data 1 | `ApacheSpeedOMeter1` | speed, engine RPM, odometer, fuel level, trip meter, top speed, 0–60 time, switch status, throttle |
| `0x11` | Speedometer data 2 | `ApacheSpeedOMeter2` | (additional cluster fields — not yet decompiled in detail) |
| `0x12` | Speedometer data 3 | `ApacheSpeedOMeter3` | (additional cluster fields — not yet decompiled in detail) |
| `0x16` | Speedometer data 4 | `ApacheSpeedOMeter4` | (additional cluster fields — not yet decompiled in detail) |
| `0x18` | Speedometer data 5 | `ApacheSpeedOMeter5` | (additional cluster fields — not yet decompiled in detail) |
| `0x29` | Wi-Fi / basic data | `ApacheBasicData` | byte1..byte16 generic fields, Wi-Fi password related |

## 5. Known limitation: exact byte offsets are not statically traceable

- The data classes (`ApacheSpeedOMeter1`, `ApacheBasicData`, etc.) are plain Kotlin property holders — decompiling their constructors shows **no field-assignment logic** (just a `super()` call and a null-check idiom).
- Tracing cross-references to their setters (e.g. `setSpeed`) returned **zero call sites** anywhere in the app.
- Conclusion: these objects are almost certainly populated by a **reflection-based mapper** (field-order or annotation driven) at runtime, which static decompilation cannot follow.
- **Field names and frame grouping are confirmed; exact byte offsets within each frame are not.**

## 6. Legacy protocol module (for older/other bikes — not RTR 310 but same app)

Project-code-based classes found in `com.tvsm.connect.bluetooth.bluetoothlegatt`:

| Role | Classes |
|---|---|
| BLE service/session logic | `N251`, `N360`, `U324`, `U368`, `U399`, `U400`, `U408`, `U449` `HelperService` |
| App → bike frames | `…DataSenderToCluster` / `…SendDataToCluster` (e.g. `U368`, `U399`, `U400`, `U449`, `U507`) |
| Bike → app frames | `…ClusterDataReceiver`, `…DataParser`, `…ClusterDataManager` |
| Encryption | `N360`, `U368`, `U399`, `U449` `EncryptDecryptDataUtils`; `U399ChecksumUtils` |

- `BaseConnectHelperService.getDecryptedData()` is a passthrough in the base class; encrypted models override it. Some legacy bikes (U399, U449, U368) encrypt frames — the RTR 310 (Apache module) encryption status has not yet been confirmed.
- No class name contains "310" or "RTR" directly — model-to-protocol mapping is handled by UUID matching (done here), not by literal class names.

## 7. Message categories observed across the app (from `SampleGattAttributes` / data IDs, legacy module)

**Bike → phone:**
Speed, RPM, gear, fuel level/segments, distance to empty, odometer, trip meters, engine temperature, battery voltage, side-stand and brake switch status, MIL/fault codes, turn signals, lean angle, top/average speed, best 0–60 time.

**Phone → bike:**
Caller ID/name, call disconnect, SMS/app notifications, turn-by-turn navigation data and control, music metadata and playback state, weather, news, sports scores, current time/date, rider name, mobile signal and battery level.

**Control / personalization:**
`VEHICLE_CONTROL`, `ACCESSORY_CONTROL`, `USER_PERSONALIZATION`, service reminder reset, screenshot capture/image transfer, OTA update (`OTA_SEND_DATA_*`, `OTA_ACK_DATA_*`).

*(Note: this category list comes from the legacy module's data-ID constants; exact overlap with the Apache/RTR 310 frame set above has not been fully cross-verified.)*

## 8. Live connection log findings (nRF Connect, 2026-11-03)

Captured against the real bike (`<bike BLE name>`):

- Two connection attempts, neither captured actual data traffic — both disconnected shortly after `setCharacteristicNotification` was called, before any `Write` or `Notification received` line appeared.
- **Attempt 1:** Bonding failed (`BOND_STATE_CHANGED → NONE, reason: REMOVED`), bike then force-disconnected (`GATT_CONN_TERMINATE_LOCAL_HOST`).
- **Attempt 2:** Bonding succeeded (`BOND_STATE_CHANGED → BONDED`), services discovered, notifications enabled — but connection then timed out (`GATT_CONN_TIMEOUT`) ~23 seconds later with no data exchanged.
- **Working hypothesis:** the bike expects an authenticated/handshake write to the `SR` (write) characteristic shortly after connecting, and drops the link if it doesn't see one. nRF Connect only subscribes to notifications — it never writes anything — which would explain both failures.

## 9. Open items / next steps

1. **Confirm exact byte offsets** for each Apache frame type — requires a real data capture (see below), since static analysis can't follow the reflection-based field population.
2. **Compare the cyclic mobile-status write with a phone capture.** This has now been done: the official app's `sendApacheMobileData()` packet is `0x5B 0x4A`, while the web bridge had been sending `0x5B 0x73`. The full field layout still needs to be reconciled before treating the web packet as a faithful replica.
3. **Confirm encryption status** of the Apache module frames (legacy U399/U449/U368 bikes encrypt; RTR 310's Apache module status unconfirmed).
4. **Capture a `btsnoop_hci.log`** (Android's built-in Bluetooth HCI snoop log) while the real TVS Connect app is connected to the bike, ideally with the ignition on. This captures both directions (writes and notifications) and would let the byte offsets be anchored against the already-known field names and frame-type map above.

## 10. APK follow-up (2026-11-14)

The APK is stored locally as `local_data/apk/com.tvsm.connect.apk` and was decoded with Apktool 3.0.3. Static inspection found an initial app-to-bike data sequence that was not covered in the earlier notes:

- `BaseBleConnectHelperService.initializeDeviceServices()` calls `sendInitialDataPacket()` after the BLE connection setup.
- `ApacheBleService.sendInitialDataPacket()` sends a rider-name frame. For most Apache bike types it sends it once. Its `APACHE_U449` / `APACHE_U469` branch sends the rider name five times, waits 400 ms between sends, then sends mobile data and location. This is app data transfer; the code does **not** label it as a telemetry request or authentication handshake.
- `ApacheMobileToCluster.sendRiderName()` builds a 20-byte packet with `BluetoothUtil.createMobileToClusterArray(name, 0x52)`: `0x5B 0x52`, up to 17 sanitized ASCII name bytes, zero padding, and a final `0xFF`. For the general Apache path, `BleEngine.writeCharacteristicToCentral()` applies `encryptDataApache()`, which preserves the first two and final bytes and XORs each intervening byte with `0xEA`; it then writes with response. The separate `APACHE_U449` / `APACHE_U469` branch uses a different encryption function.
- The script now has an opt-in `--send-rider-name TEXT` switch for the APK-derived Apache-U400-style packet. Its default remains read-only. A successful write may update the rider name shown by the cluster; it is not proven to start the telemetry stream.
- The live Mac probe connected and subscribed to `ST` for 30 seconds but received no notifications. Its Device Information reads returned literal labels (“Model Number”, “Firmware Revision”, etc.), not vehicle values.

The APK also contains a separate `com.tvsm.unified.apache.common.sendreceive.ApacheDataParser` implementation with explicit byte indexing (for example, its speed getter reads byte index 2 after the `0x5A` start byte). I have not yet established that this parser is the parser wired to the RTR 310 `ApacheBleService` path, so its offsets should not yet be treated as confirmed for the tested bike.

### Live data capture after rider-name frame

The user ran the probe with `--send-rider-name "BLE TEST"`. The logged TX bytes were `5B 52 A8 A6 AF CA BE AF B9 BE EA EA EA EA EA EA EA EA EA FF`, matching the APK's `0x5B 0x52` rider-name packet plus Apache XOR transform. About 30 ms later, `ST` began delivering repeated 20-byte frames, including IDs `0x10`, `0x11`, `0x12`, `0x5F`, `0x7D`, and `0x5B 0x42`. This is the first confirmed bike-to-Mac telemetry capture; the rider-name write preceded the stream, though that timing alone does not establish it as an authentication handshake.

### Cyclic BLE traffic and the ride button

- After GATT setup, `BaseBleConnectHelperService.initializeDeviceServices()` starts the foreground BLE service, sends initial data, starts a location service, and schedules cyclic writes immediately and every 2 seconds (`startSendingCyclicData`, `Timer.schedule(..., 0, 2000)`).
- For the Apache service, `ApacheBleService.writeCyclicDataPacket()` calls `ApacheMobileToCluster.sendMobileData()`, which builds and writes an app-to-bike mobile-status/settings frame. The web bridge had been sending a 20-byte `0x5B 0x73` frame based on an earlier mistaken frame-ID interpretation. The Android HCI capture below shows the official packet is `0x5B 0x4A`; sessions described in the historical web logs used the old frame. The web session log records write results and sequence numbers without storing packet bytes.
- `sendInitialDataPacket()` and the cyclic timer start as part of BLE service initialization; the APK does not gate them on a “Start Ride” button.
- The exact “Start your Ride” label in this APK is in `ikarus_bt_layout_dashboard.xml`, an Ikarus EV dashboard layout. The Apache U400 navigation screens instead contain “Start Day” / “Start Tour” controls. Static inspection places those controls in navigation/tour UI, not in the BLE connection service. They are not evidence of a BLE keepalive action.
- The earlier standalone `ble_capture.txt` reports a 30.5-second GATT session with notifications spanning about 6.3 seconds. The later web logs add link-state polling and show that telemetry can pause while GATT remains connected; see the latest-session analysis below.

### Latest web-session check (2026-11-14)

- `ble_session_20261114_141703_6e546c18d1cd.jsonl` ended with the dashboard's explicit `disconnect_button` request; do not classify it as a bike disconnect.
- `ble_session_20261114_141743_378d187dd494.jsonl` connected at 14:17:45.916 and lost the BLE link at 14:18:45.437 (59.521 seconds). No app disconnect was requested. Twenty-seven `0x73` writes succeeded at roughly two-second intervals; write 28 failed because CoreBluetooth already reported `disconnected`. This rules out our code intentionally disconnecting first but does not identify whether the bike or macOS initiated link loss.
- Notifications arrived through 14:17:54, then paused for about 37 seconds while `BleakClient.is_connected` remained true; they resumed at 14:18:34 and stopped again before the link dropped. The telemetry silence is therefore distinct from the later GATT disconnect.
- In `BaseBleConnectHelperService`, initialization reads Android battery percentage and cellular signal/network type once, then starts the foreground service, initial packet, cyclic timer, and location work. APK inspection and the Android capture now confirm `sendApacheMobileData` uses frame `0x4A`; byte 2 combines signal strength and a battery bucket, while byte 11 carries the `NETWORK_TYPE` preference. Earlier Mac logs used the wrong `0x73` frame ID, even though the GATT writes were acknowledged.
- The dashboard now emits frame `0x4A` and includes the configured signal level, APK battery bucket, and network type. Its default values are synthetic (4/4, 80%, code 13). Packet cadence remains the Mac's two-second approximation; the phone capture also has bursts near 200 ms that are not yet explained.
- The Android HCI capture shows bike notifications continuing until 37 ms before Android sent a local HCI disconnect command. That trace does not show telemetry stopping before the disconnect. The earlier Mac logs do show notification stalls during the old `0x73` implementation; compare with a fresh Mac run using `0x4A` before attributing that behavior to the bike.

### Latest session and Android capture route (2026-11-14)

- `ble_session_20261114_172150_ac2916aa6c46.jsonl` connected at 17:21:53.062 and lost the GATT link at 17:24:11.185 (138.124 seconds). No app disconnect request was logged. Sixty-six cyclic `0x73` writes succeeded at ~2-second intervals; the next failed only after `BleakClient` reported disconnected.
- The session's configured phone values were all zero (`cellular_signal_level=0`, `phone_battery_percent=0`, `network_type=0`), so this run did not exercise non-zero phone-status bytes.
- Notifications first arrived at connection, then stopped around 17:21:59 while GATT remained connected. They resumed at 17:23:46 for about 3 seconds (last at 17:23:49.630), then ceased before the 17:24:11 link loss. This separates sensor notification stalls from the later link drop.
- The APK's `BluetoothUtil.saveBLEConnectionsLogs()` method body is an immediate return, so its many apparent BLE log calls do not persist data in this build. The manifest does declare an Apache U400 foreground connected-device service and `WAKE_LOCK`, which the Mac bridge does not reproduce as Android lifecycle features.
- Android's built-in Bluetooth HCI snoop option is the best next capture: it records the phone's actual GATT writes, bike notifications, and HCI disconnect event while TVS Connect is running. A bug report can package the snoop capture. ADB access from this Mac was not available in this run because its local server could not start under the sandbox (`Operation not permitted`).

### Android HCI capture (2026-11-14, 17:57–18:00)

- `btsnoop_hci.log` shows the phone connecting as BLE central at 17:58:38.488 on handle `0x0010`, with a 45 ms connection interval and 5 s supervision timeout. The connection lasted 85.684 seconds, until 18:00:04.172.
- The bike sent 500 notifications on the subscribed characteristic from 17:58:57.016 through 18:00:04.134. The longest gap was 0.390 seconds. There is no telemetry stall before disconnect in this capture; the final notification arrived 37 ms before the phone's disconnect command. This differs from the Mac prototype sessions where notifications stopped well before some link drops.
- The phone wrote three frame types on the custom write characteristic: one rider-name packet (`5B 52`) at 17:58:56.901, 294 `5B 4A` packets from 17:58:58.899 through 18:00:02.345, and eight `5B 43` packets between 17:59:46.474 and 17:59:49.964. Each write request received an ATT response. `BlePacketsProvider.sendApacheMobileData()` constructs the mobile/settings packet with header `[0x5B, 0x4A]`; this is confirmed by both the decompiled APK's byte array and the captured writes. Our prototype had been sending `0x5B 0x73`, so it was not sending the official cyclic mobile-status frame. The captured `0x4A` writes also occur in clusters, often about 200 ms apart, rather than at a steady two-second cadence throughout; reproduce that behavior only after understanding its trigger.
- The phone sent HCI `Disconnect` at 18:00:04.163; the controller reported `Connection Terminated by Local Host (0x16)` at 18:00:04.172. Android's local Bluetooth host initiated this disconnect. The trace cannot distinguish an app-requested disconnect from an Android-side close. The bike was still notifying immediately before the command, so this capture does not support telemetry stopping as the cause of this particular disconnect.
- The mismatch in frame ID and synthetic status fields is a strong explanation for why the Mac prototype may behave differently from TVS Connect. An ATT write response only proves the GATT server accepted the write at the transport layer; it does not prove the bike interpreted the packet as its official status message.
- The bridge now uses the confirmed `0x4A` frame ID and encodes the signal, battery bucket, and network type fields. Other call/voice-assist fields and the phone's burst cadence remain to be compared. Do not use the separate `0x73` calibration-write frame as a status/keepalive substitute.

### New Android HCI capture (`btsnooz_hci.log`, 2026-11-14, 19:38–19:41)

- The snoop starts during an established connection, so it cannot show pairing or the initial GATT setup. Each ATT packet is only **15 bytes captured from a 32-byte packet**. The ATT value is consequently truncated to three bytes: enough to classify frame prefixes and the `0x6B` command byte, but not enough to decode status values, caller names, or incoming `0x11` byte 12.
- Within the visible data there are 823 bike notifications on handle `0x0022` and 140 phone Write Requests on `0x0026`; every captured write received an ATT response. Incoming notifications contain prefixes for `0x10` (394), `0x12` (394), `0x11` (17), `0x5F` (12), and `0x6B` (6). The maximum notification gap is about 330 ms, and notifications continue until just before the local disconnect command.
- The visible phone write prefixes include `5B 4A` 91 times and `5B 43` 10 times. APK correlation: `BlePacketsProvider.sendApacheMobileData()` emits `5B 4A`; `TelephonyUtils.returnContactInfoPacket()` emits `5B 43`. The ten `0x43` writes fall into three bursts of 4, 4, and 2 frames, with about 500 ms between frames within a burst. The payload is truncated, so this file cannot reveal the caller name or prove what was displayed on the bike.
- APK correlation resolves those additional prefixes: `ApacheClusterNavigationData` uses `5A 4E` for navigation; `BleMusicDataSourceImpl` and `BlePacketsProvider` use `5B 62` for song title, `5B 63` for artist, `5B 61` for album, and `5B 60` for Apache play/pause state. The capture shows 19 navigation writes in short bursts from about 19:38:43.5 to 19:38:50.6. It also shows five four-frame metadata/state sequences in the order title, artist, album, play/pause at about 19:39:10.2, 19:39:18.5, 19:39:19.5, 19:39:22.8, and 19:39:27.6. This matches the reported map-navigation use and Spotify track/pause/play actions.
- The bike sent six `5A 6B` music-control notifications, with byte 2 values `3, 3, 4, 4, 2, 2`. The Apache receiver classifies `0x6B` as a music command, and `BluetoothUtil.getMusicCommandType()` maps `3=Next`, `4=Previous`, and `2=Toggle`. Its handler dispatches Android `AudioManager` media key events; Toggle checks current music activity and selects play or pause. Each value appears in two back-to-back notifications; whether the bike intentionally duplicates a single press is not established. Associated metadata/state writes follow roughly 0.4 seconds later. This directly correlates bike music-control commands with the observed Spotify actions; the physical D-pad directions remain unknown.
- For indicators, the APK's `ApacheDataParser` routes frame `0x11` through the byte-11 speedometer parser and defines byte 12 as `turnSignalLampStatus` (offset `0x0C`). Rider-confirmed codes are `0=off`, `1=right`, `2=left`, `3=hazard`. The 17 incoming `0x11` notifications in this file are truncated before byte 12, so this capture itself cannot correlate each physical action to a status transition.
- The Apache call-control path reads decoded frame `0x11` byte 11 and maps `1=accept`, `2=reject`, `3=alternate reject`. Accept invokes Android `TelecomManager.acceptRingingCall()`. The inspected APK path does not send a hazard/indicator BLE write. Hazards observed when answering from the bike are therefore most likely a bike/cluster-side behavior associated with the call-accept event, but the exact firmware trigger is unproven; capture full packets across an incoming call and answer to correlate byte 11, byte 12, and simultaneous phone writes.
- Notification frames continue through frame 1586. The phone sends HCI Disconnect at frame 1587; frame 1589 reports `Connection Terminated by Local Host (0x16)`. This is another trace where the local Android host initiates closure while bike notifications are still arriving; it does not tell us whether the app or Android Bluetooth stack requested the close.
- Compared with the older full-length snoop, this capture confirms `0x4A` status and `0x43` caller/contact traffic and, when correlated with APK sender code, navigation and music controls/metadata. Future captures should be exported without packet truncation (full 32-byte HCI packet records) and should isolate each indicator or hazard action. Record physical D-pad directions and signal toggles with timestamps to map directions and turn-status values.

### Notification stalls versus link drops (2026-11-14, 17:37–17:39)

- The four sessions (`17:37:54`, `17:38:14`, `17:38:31`, and `17:38:58`) used mock signal/battery/network values but sent the then-incorrect `0x73` frame. In each, incoming notifications stopped while the GATT connection was still reported as connected. Those logs predate the `0x4A` correction and should not be treated as tests of the official status packet.
- The newest session, `ble_session_20261114_173858_51c303c97b79.jsonl`, received notifications through 17:39:18.198. The monitor recorded the stream stalled at 17:39:20.525; status writes then succeeded at 17:39:23.913 and 17:39:25.938. At 17:39:26.037 the dashboard called its disconnect endpoint, and the callback classified the disconnect as `app_requested` / `disconnect_button`. So this run confirms telemetry stopped first, but it does **not** show the bike dropping the BLE link.
- The same sequence appears in the other three short tests: a notification stall, successful cyclic writes afterwards, then a dashboard `disconnect_button` event. Those endings must not be counted as spontaneous bike disconnects.
- The earlier unrequested drop, `ble_session_20261114_172150_ac2916aa6c46.jsonl`, is the useful comparison. Its last notification was at 17:23:49.630; all 66 cyclic writes through 17:24:04.960 succeeded; the BLE callback reported a link loss at 17:24:11.185 (138.124 seconds after connection), with no app disconnect reason. Thus telemetry went quiet about 21.6 seconds before the actual link loss. This strongly supports two stages: the bike stops sending notifications, then the BLE link later disconnects. It does not establish why the notifications stop or which side initiates the eventual disconnect.
- The 17:21 run used all-zero mock phone values, while the later runs used non-zero values. Since the later runs also had notification stalls, the stall is not explained by the all-zero status fields. The status writes succeeding only confirms ATT-level writes were accepted; it does not prove the bike application considered their contents valid or that those writes should request telemetry.
- Next useful evidence is a fresh Mac session using `0x4A`, with raw frame logging enabled, compared against a new Android capture. The existing HCI trace already identifies a local-host disconnect in the Android session; the earlier Mac JSONL logs cannot identify why the bike stopped notifying while connected.

For `decryptDataForApache`, bytes 2 through length-2 are XORed with `0xEA`; the two-byte header and final `0xFF` are preserved. A captured `0x5A 0x10` frame decrypts to `5A 10 00 04 34 D1 0E 21 A5 86 09 00 38 20 00 00 00 00 D1 FF`. In the APK's `ApacheDataParser` map, this corresponds to speed 0, odometer 27,566.5 (three-byte big-endian value divided by 10), fuel level 14, average speed 33, top speed 134, throttle 9, 0–60 time 5.6 (byte value divided by 10), and RPM 0. These field interpretations come from the separate parser class; they are useful initial decoding, but should be cross-checked against a second capture with known instrument readings.

`scripts/ble_probe.py` prints both wire and Apache-decoded bytes for each notification. Its status line also distinguishes read-only runs from runs using the opt-in rider-name write.

---
*Compiled from static APK analysis and live BLE observations against a TVS Apache RTR 310. The APK exposes an initial rider-name data packet, but no explicit telemetry request/authentication handshake has been confirmed. See Sections 9 and 10.*
