# TVS Bike BLE Protocol and Communication

How the phone app and the bike talk to each other over Bluetooth Low Energy (BLE). Everything here was reverse engineered from an HCI snoop capture of the official phone app (`btsnoop_hci.log`, 148 s, one connection) and confirmed by a Python client (`bike_web.py` + `bike_protocol.py`) that now works against the real bike.

**Confidence levels used below**

- **Confirmed**: seen directly in the capture, or reproduced byte for byte by `test_bike_protocol.py`.
- **Inferred**: a reasonable reading of the data, not proven.
- **Unknown**: present in the data, meaning not determined.

---

## 1. The big picture

```
  Phone / Mac (central, GATT client)                    Bike (peripheral, GATT server)
  ----------------------------------                    ------------------------------
  scans, connects  ------------------------------------>  advertises as "TVSRTR310DLA3140"
                   <------------------------------------   Security Request ("please pair")
  passkey pairing  <------------------------------------>  bike shows passkey, phone types it
  enable notifications (CCCD = 0x0001)  --------------->
  write rider-name frame (0x52)  ---------------------->
                   <-----------------------------------    telemetry notifications, ~3 per second
  write clock/status frame (0x4A) every 2 s  ---------->
                   <-----------------------------------    more telemetry notifications
  disconnect  ---------------------------------------->
```

- The **bike is the data source**. It streams telemetry (speed, RPM, fuel, gear, and so on) as notifications as soon as the app subscribes.
- The **app** sends only a few small frames: the rider name once, then a periodic frame carrying the phone's clock, date and a signal level.
- One service, two characteristics. All traffic is 20-byte frames.

---

## 2. GATT layout (Confirmed)

| Item | Value |
|---|---|
| Device name (GATT `0x2A00`) | `TVSRTR310DLA3140` |
| Custom service UUID | `5456534d-5647-5341-5342-454e544f5251` (the bytes spell ASCII `TVSMVGSASBENTORQ`) |
| Notify characteristic (bike to app) | `00005354-0000-1000-8000-00805f9b34fb` (16-bit `0x5354`), value handle `0x22` |
| CCCD for the notify characteristic | handle `0x23` (app writes `0x0001` to subscribe) |
| Write characteristic (app to bike) | `00005352-0000-1000-8000-00805f9b34fb` (16-bit `0x5352`), value handle `0x26`, write with response |

The bike also exposes the standard Generic Access, Generic Attribute and Device Information services. The app does not need them for telemetry.

Handles can differ between firmware versions, so a client should use the **UUIDs**, not the handles. That is also the only option in Python on macOS.

---

## 3. Connection sequence in detail

Times are seconds from the first packet in the capture.

| Time | Who | What |
|---|---|---|
| 0 to 57 | Phone | Bluetooth stack start-up, then scanning. |
| 58.4 | Phone | Sends the LE Extended Create Connection command (connect to the bike using the filter accept list). |
| 58.7 | Bike | Sends an **SMP Security Request**: it wants the link paired and encrypted. |
| 58.7 to 59.6 | Phone | **GATT discovery** (services, characteristics, descriptors). Works before pairing. |
| 62.4 | Phone | Pairing Request: keyboard and display capable, MITM, Secure Connections, bonding. |
| 62.4 | Bike | Pairing Response: **display only**, MITM, Secure Connections, bonding. |
| 62.5 to 62.8 | Both | Exchange public keys. |
| 62.8 to 72.5 | User | About **10 s gap**: the user reads the passkey from the bike display and types it into the phone. |
| 72.5 to 73.3 | Both | 20 rounds of Pairing Confirm / Pairing Random (the passkey is verified bit by bit), then DHKey check. |
| 73.3 | Both | Key distribution; link **encryption enabled**. |
| 73.4 to 74.0 | Phone | GATT discovery repeated over the encrypted link. |
| 77.0 | Phone | **Enable notifications**: write `0x0001` to the CCCD (handle `0x23`). |
| 77.0 | Both | MTU exchange: phone asks 512, bike grants 251. (Not needed: frames are 20 bytes and fit the default MTU of 23.) |
| 77.1 | Phone | Writes the **rider-name frame** (`0x52`) as a Write Request; the bike replies with a Write Response. |
| 77.2 onward | Bike | **Starts streaming** notifications. It does not wait for the clock frame. |
| 79.1 | Phone | First **clock/status frame** (`0x4A`), then one every 2 s. |
| ~89 | Phone | Clock/status frame rate rises to about 5 per second (reason unknown). |
| 126.7 to 130.2 | Phone | Eight writes of a second text frame (`0x43`) every ~0.5 s (the capture shows `Thiravi`). |
| 144.4 | Phone | Disconnects. The bike does not drop the link. |

**What this means for a client**

1. Pairing must finish before notifications can be enabled. Allow time for the passkey entry; the phone needed ~15 s from connecting.
2. After subscribing, the bike streams on its own.
3. Do the first write right after subscribing, then send the clock frame about every 2 s.

---

## 4. Frame format (Confirmed)

Every frame, in both directions, is **20 bytes**:

```
 byte:   0        1         2 ........ 18      19
       +--------+---------+-------------------+---------+
       | header | frame id|   body (17 bytes) | trailer |
       +--------+---------+-------------------+---------+
        raw      raw       XOR 0xEA            raw 0xFF
```

| Byte | Meaning | Value |
|---|---|---|
| 0 | Header | `0x5A` for most bike frames, `0x5B` for all app frames and a few bike frames |
| 1 | Frame ID | Raw, not obfuscated |
| 2 to 18 | Body | Every byte is XORed with `0xEA` |
| 19 | Trailer | `0xFF` |

Because zero padding becomes `0xEA` after the XOR, unused bytes show up as `EA EA EA ...` on the wire. That is how the key was spotted.

There is no checksum that I could find. The trailer is constant.

### Decoding (Python)

```python
def decode(frame: bytes) -> bytes:
    """Return the frame with the body de-obfuscated; header, id and trailer untouched."""
    out = bytearray(frame)
    for i in range(2, len(out) - 1):
        out[i] ^= 0xEA
    return bytes(out)

def build(frame_id: int, body: bytes = b"", header: int = 0x5B) -> bytes:
    body = body.ljust(17, b"\x00")
    return bytes([header, frame_id]) + bytes(b ^ 0xEA for b in body) + b"\xFF"
```

In the sections below, **"frame byte N"** means index N of the *decoded* frame (index 0 is the header, 1 is the ID, so the body starts at 2).

### Worked example: rider name

Wire bytes written by the phone:

```
5b 52 99 8f 84 ea ea ea ea ea ea ea ea ea ea ea ea ea ea ff
```

- `5b`: app header
- `52`: frame ID, rider name
- `99 8f 84` XOR `ea` gives `73 65 6e`, which is ASCII `sen`
- `ea ...` XOR `ea` gives `00` padding
- `ff`: trailer

---

## 5. App to bike frames (writes to `0x5352`)

All writes are ATT Write Requests (with response). In the capture there were 303 writes and no failures.

### 5.1 Rider name, ID `0x52` (Confirmed)

Sent **once**, 0.1 s after notifications are enabled.

| Body bytes | Content |
|---|---|
| 2 to 18 | Name in ASCII, zero padded, maximum 17 characters |

### 5.2 Second name field, ID `0x43` (Confirmed frame, purpose Unknown)

Seen 8 times at about 0.5 s intervals (126.7 to 130.2 s). Same layout as `0x52`: ASCII text, zero padded. The capture shows `Thiravi`. Best reading: the **caller name shown on the cluster during the call alert** (see section 10.3). The Python app does not send it yet.

### 5.3 Phone status, ID `0x4A` (Confirmed: layout read from the APK and matched to the capture)

The periodic frame, built by `BlePacketsProvider.sendApacheMobileData` in the APK and sent by `ApacheMobileToCluster.sendMobileData`. 294 of the 303 writes are this one.

| Frame byte | Field | APK source | Example |
|---|---|---|---|
| 2 | High nibble: **cellular signal strength**. Low nibble: **phone battery level 0 to 5** (see section 11.2) | `NETWORK_STRENGTH` pref and `getIceBatteryLevel()`, decimal digits joined and parsed as hex | `0x33` = signal 3, battery 40 to 59 % |
| 3 | **Overspeed limit** in km/h, `120` (`0x78`) when the overspeed alert is off | `IceSettingsModel.getOverSpeedAlert()/getOverSpeedLimit()` | `0x78` |
| 4, 5 | Always 0 | constants | `0x00` |
| 6 | Hour, 12-hour clock | `getCurrentHour(Calendar)` | `0x05` |
| 7 | Minute | `Calendar.MINUTE` | `0x3A` |
| 8 | Second | `Calendar.SECOND` | `0x3A` |
| 9 | 1 = PM, 0 = AM | `Calendar.AM_PM` | `0x01` |
| 10 | **Missed-call count** | `MISSED_CALL` pref | `0x00` |
| 11 | **Network type** code | `NETWORK_TYPE` pref | `0x00`, later `0x03`, `0x04` |
| 12 | Day of month | `Calendar.DAY_OF_MONTH` | `0x0E` |
| 13 | Month (1 to 12) | `Calendar.MONTH + 1` | `0x0B` |
| 14 | Year mod 100 | `Calendar.YEAR % 100` | `0x1A` |
| 15 | **Current call state** | `bleEngine.getCurrentCallState()` | `0x00`; `0x02` once, right after the reject |
| 16 | **Voice-assist status**, high nibble | `VOICE_ASSIST_STATUS` pref, digit + `"0"` parsed as hex | `0x00` |
| 17, 18 | Always 0 | padding | `0x00` |

The APK has two other layouts for the bike types `APACHE_U449` and `APACHE_U469` (17-byte body, call state in byte 17) and an 18-byte variant. The capture matches the standard layout above.

Wire bytes for the first capture example (17:58:58, 14 Nov 2026): `5b 4a d9 92 ea ea ef d0 d0 eb ea ea e4 e1 f0 ea ea ea ea ff`

**Timing**

| Period | Rate |
|---|---|
| First frame | 2.0 s after the rider-name frame |
| 79 to ~89 s | One every 2 s |
| ~89 s to the end | About 5 per second (every ~0.2 s), same content; reason unknown |

The bike keeps streaming while this frame is sent. Whether it would stop streaming if the frame were missing was not tested.

---

## 6. Bike to app frames (notifications from `0x5354`)

500 notifications in 67 s, about 7 per second. The raw frame IDs seen:

| Raw ID | Header | Count | Rate | Meaning |
|---|---|---|---|---|
| `0x10` | `5A` | 224 | ~every 0.3 s | **Main telemetry**: speed, odometer, fuel, RPM and more |
| `0x12` | `5A` | 223 | ~every 0.3 s | Second periodic frame, mostly zeros. Meaning Unknown |
| `0x11` | `5A` | 29 | occasional | **Gear** (and other fields, Unknown) |
| `0x5F` | `5A` | 12 | occasional | Almost all zeros. Unknown |
| `0x7D` | `5A` | 5 | at connect (77.6 to 78.9 s) | ASCII identifier of 17 characters. It looks like a VIN or vehicle ID |
| `0x42` | `5B` | 5 | at connect | Contains the bike's own Bluetooth address (value redacted) |
| `0x6B` | `5A` | 2 | rare | Short, mostly zeros. Unknown |

The identity frames (`0x7D`, `0x42`) are sent a few times right after the subscription. Do not log them if you care about privacy.

### 6.1 Telemetry frame `0x10`

Field layout, as implemented by the Python decoder. Offsets are decoded frame bytes.

| Frame byte | Field | Conversion | Value in the capture (bike stationary) |
|---|---|---|---|
| 2 | Speed | km/h | 0 |
| 3 to 5 | Odometer | big-endian integer, divided by 10 | 275928 gives 27,592.8 km |
| 6 | Fuel level | percent | 21 |
| 7 | Average speed | km/h | 33 |
| 9 | Recorded top speed | km/h | 134 |
| 10 | Throttle | percent | 14 |
| 12 | 0 to 60 time | divided by 10, seconds | 5.6 |
| 16, 17 | Engine RPM | big-endian integer | 0 |

Two values need checking against the instrument cluster: the **odometer scale** (is it really tenths of a km?) and **throttle 14 %** while the bike is at rest (it may be a different field).

Example, wire bytes of one telemetry frame:

```
5a 10 ea ee df 32 ff cb 4f 6c e4 ea d2 ea ea ea ea ea 37 ff
```

Decoded body (XOR `ea`), listed by frame byte:

| Byte | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13-17 | 18 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Value | `00` | `04` | `35` | `d8` | `15` | `21` | `a5` | `86` | `0e` | `00` | `38` | `00` x5 | `dd` |
| Field | speed 0 | odometer `0x0435d8` = 275928 | | | fuel 21 | avg speed 33 | unknown | top speed 134 | throttle 14 | unknown | 0-60 = 5.6 s | RPM (16-17) = 0 | unknown |

### 6.2 Gear frame `0x11`

| Frame byte | Field | Value in the capture |
|---|---|---|
| 5 | Gear | 0 (the bike was stationary; whether 0 means neutral is not confirmed) |

---

## 7. Other behavior in the capture

- **Bike as GATT client.** The bike sent a few ATT requests to the phone (reads and writes to handle `0x0000`), and the phone rejected them with "invalid handle" or "attribute not found". This looks harmless: the app worked normally. It may also be an artifact of how the capture was reassembled.
- **Connection parameters.** The link started at a 7.5 ms interval, moved to 45 ms, and settled at 7.5 ms with a 6 s supervision timeout.
- **Encryption.** Link-layer encryption is on after pairing, but the HCI snoop log shows the **decrypted** ATT data. The XOR with `0xEA` is the application's own light obfuscation, not security.

---

## 8. Minimal client recipe

```python
# 1. connect
# 2. pair (passkey shown on the bike's display)   macOS: the system dialog appears by itself
# 3. enable notifications on 00005354-0000-1000-8000-00805f9b34fb
# 4. write rider name (ID 0x52) with response
# 5. every 2 s write the clock frame (ID 0x4A) with response, starting 2 s later
# 6. decode every notification: decode(frame), then read fields by frame ID
```

See `bike_protocol.py` for the frame builders and `test_bike_protocol.py` for the checks against captured bytes.

---

## 9. Open questions

1. What do the unknown clock-frame bytes (frame bytes 3 and 11) mean, and does byte 2 really track signal level?
2. Why does the phone speed the clock frame up to ~5 per second after 89 s?
3. Which exact bike-side value is the call reject, and which d-pad button is code 1 or 2 (see section 10)?
4. What do the `0x12`, `0x5F` and `0x6B` notifications carry?
5. Does the bike stop streaming, or disconnect, if the clock frame stops?
6. Are the odometer and throttle conversions correct?

Items 1 to 4 can be answered by capturing the phone app again while changing one thing at a time (for example, toggling mobile data, receiving a call, changing the rider name) and diffing the frames.

---

## 10. Call alert and d-pad: what the capture shows

> **Second correction (iOS code and APK field names, section 13).** Byte 12 of the `0x11` frame is the **turn indicator** (0 off, 1 right, 2 left, 3 hazard), not a d-pad code, so the 2, 2, 2, 0, 1, 0 pattern at 134 to 140 s was left and right indicators. The bike's `0x10` byte 11 is the handlebar **switch status**. The `0x6B` frame is the bike's **music command** (not obfuscated). Wherever section 10 says "d-pad" for byte 12, read "turn indicator".
>
> **Correction after reading the APK (section 11).** Byte 11 of the `0x4A` frame is the **network type**, not a call status word, and byte 15 is the **call state**. The call-state byte went to 2 for one frame at 130.55 s, right after the bike's reject report. The rest of this section is kept as originally written; where it conflicts with section 11, section 11 wins.

The second half of the capture (about 98 to 144 s) contains a call alert and handlebar d-pad presses. These are **not yet decoded with certainty**: the capture has one call and a handful of presses, so there is too little data to separate every meaning. This section lists what was observed and ranks the readings.

### 10.1 Bike frame checksum (Confirmed)

Bike-to-app frames carry a checksum in the **last body byte (decoded frame byte 18)**:

```
byte18 = (0xA5 - frame_id - sum(decoded bytes 2..17)) mod 256
```

It holds for every `0x10`, `0x11`, `0x12`, `0x5F` and `0x42` frame in the capture (all 493 of them). It does not hold for the identity frames `0x7D` and `0x6B`. The app's own frames do **not** carry this checksum, and the bike accepts them anyway (your Python client works without it).

Use it to validate incoming frames, and to tell real state changes from corrupted frames.

### 10.2 Observed events, in time order

| Time (s) | Direction | Observation |
|---|---|---|
| 98 to 125 | Bike to app | Frame `0x11` byte 17 toggles `0x22` / `0x32` (with byte 18 following the checksum) 8 times. Meaning Unknown. |
| 125.77 | App to bike | Clock frame `0x4A` byte 11 (the "status word") changes **0 to 3**. |
| 126.67 to 130.16 | App to bike | Eight `0x43` frames, 0.5 s apart, text `Thiravi`. |
| 130.27 and 130.55 | Bike to app | Two telemetry frames (`0x10`) with **byte 11 = 2** instead of 0. Seen nowhere else. |
| 134.24 to 140.25 | Bike to app | Frame `0x11` **byte 12** goes 2, 2, 2, 0, 1, 0, and byte 13 drops `0x50` to `0x4F` (80 to 79) at 137.47. |
| 142.54 | App to bike | Status word changes **3 to 4**. |
| Every 10 s | Bike to app | Frame `0x5F` alternates between all zeros and a variant with byte 12 = `0x20` (a 10 s bike heartbeat). |

### 10.3 Call alert, best reading

| Piece | Reading | Confidence |
|---|---|---|
| `0x43` frame | **Caller name shown on the cluster during a call**: a text frame, sent in a burst of 8 over 4 s, right after the call was triggered | High |
| Clock frame byte 11 going to 3 | A call or notification state flag. It rose ~0.9 s before the first name frame. | Medium. It stays at 3 after the call ended and became 4 at 142.5 s, so it may be an event counter rather than a plain "ringing" flag. |
| Bike `0x10` byte 11 = 2 (130.27 s) | **The rider's cancel / reject**, reported by the bike. It appears 0.1 s after the last name frame and nowhere else. | Medium. The app stopped sending names at 130.16 s, just before it. It could instead be a fixed 4 s ring timeout. |

### 10.4 D-pad music controls, owner-confirmed

The rider confirmed the physical double-press behavior while using the TVS app:

| Physical control | Bike music command (`0x6B`, byte 2) | Default phone action in RTR Link |
|---|---:|---|
| Enter / OK, double press | `3` | Next track |
| Back, double press | `4` | Previous track |
| Down, double press | `2` | Pause |

The apps let the rider remap each reported bike command to no action, next track, previous track, play, pause, or play/pause. Android also supports volume up/down. iOS omits volume actions because it cannot set system volume programmatically. This changes the phone action only; it does not reprogram the bike's physical controls. Captured repeated copies of the same `0x6B` command within 100 ms are treated as duplicate BLE notifications and collapsed to one action. The older interpretation of `0x11` byte 12 as a d-pad code was incorrect; that field is part of the vehicle-state frame, not the music-command event.

Mapping selections are now persisted as soon as they change in Settings (and again on Save). If an action still appears not to work, check the session log for `bike_data_changed` on `0x6B` and then `music_command_action`: absence of the first means the bike did not send the button event; presence of the first but absence of the second points to command handling or a duplicate suppression. On iOS, transport actions are sent through `MPMusicPlayerController.systemMusicPlayer`; they control Apple Music playback and cannot reliably control third-party players. iOS volume changes are unavailable by platform design.

### 10.5 Signal, battery and the rest of the app-to-bike data

| Item | Where it is in the capture | Confidence |
|---|---|---|
| Signal strength | `0x4A` byte 2, high nibble (drifts 1, 2, 3) | Confirmed in the APK |
| Battery percentage | `0x4A` byte 2, low nibble, as a 0 to 5 bucket (section 11.2). It stayed at 3 (40 to 59 %) during the capture, which is why it never changed. | Confirmed in the APK |
| Call alert | `0x43` caller name and status word, as above | Medium |
| SMS, missed call, navigation, and similar | Not exercised in this capture | None |

### 10.6 Remaining capture questions

The owner-confirmed double-press mapping above settles the music controls. It does not establish mappings for the other d-pad directions or single presses, nor does it explain the separate `0x11` state transitions observed in older captures. Those should remain unassigned until a capture isolates each action; do not label `0x11` byte 12 as a music or d-pad command.

---

## 11. Definitions read from the TVS Connect APK

Source: `com.tvsm.connect.apk`, version 8.12.3 (build 326), decompiled statically with JADX (SHA-256 `b16bd054...20832d`). The APK was never run. Class and method names below let you re-check every item.

### 11.1 Where the Apache (RTR) protocol lives

| Role | Class |
|---|---|
| BLE service for the Apache bikes | `com.tvs.ble.feature.bikewiseblehelpers.ApacheBleService` (`sendRiderName`, `sendInitialDataPacket`, `writeCyclicDataPacket`, `sendMusicStatusDataPacket`) |
| Everything the app writes to the cluster | `com.tvs.ble.feature.datasender.ApacheMobileToCluster` |
| Frame builders | `com.tvs.bluetooth.core.utils.BlePacketsProvider` and `BluetoothUtil` |
| Parsed cluster data (bike to app) | `com.tvs.bike.core.protocol.BtData` (not decoded yet) |
| Connection and call handling | `com.tvs.ble.feature.handler.BleEngine` (`processBikeWiseIncomingCall`, `getCurrentCallState`, `observeDocTransferFromCluster`) |
| Bluetooth stack | Nordic `no.nordicsemi.android.ble.BleManager` plus a Blessed-style central (`com.tvsm.connect.blemanager`) |

The same package has helpers for other models (`NtorqBleHelper`, `JupiterBleService`, `U347/U388/U467BleHelper`), so frame layouts differ per bike type.

### 11.2 Phone battery level (byte 2, low nibble)

`BluetoothUtil.getIceBatteryLevel(percent)`:

| Phone battery | Level sent |
|---|---|
| 0 to 9 % | 0 |
| 10 to 19 % | 1 |
| 20 to 39 % | 2 |
| 40 to 59 % | 3 |
| 60 to 79 % | 4 |
| 80 to 100 % | 5 |

### 11.3 Everything `ApacheMobileToCluster` can send

These are the app-to-bike features visible in the class. Only the first three appear in the capture.

| Method | What it is | In the capture? |
|---|---|---|
| `sendRiderName` | Frame `0x52`, rider name | Yes |
| `sendMobileData` | Frame `0x4A`, status (section 5.3) | Yes |
| contact info packet (`returnContactInfoPacket(name, byte, byte)`) | Call or SMS alert caller name, likely frame `0x43` | Yes (`Thiravi`) |
| `sendMobileData5(int, String)` | Another mobile-data frame with text | No |
| `sendBirthDate` | Rider date of birth | No |
| `sendCustomTextLineOne` / `Two` | Custom text lines on the display | No |
| `sendNavigationAssistOn`, `sendNavigationInstructions`, `sendManuverString`, `sendDestReachedCustomText` | Turn-by-turn navigation | No |
| `sendLocation` | Current location | No |
| `sendVehicleMode` | Ride mode | No |
| `sendTSLSignal`, `sendTslTimer` | Traffic-signal / timer feature | No |
| `sendFollowMeHeadLampTimer`, `sendIdleStartStopTimer` | Cluster settings | No |
| `calibrationWriteRequest` | Calibration | No |

Frame types seen in `BlePacketsProvider`: initial mobile packet, mobile packet with ACK, VIN, music and play/pause state, vehicle control, calibration, timer control, contact info.

### 11.4 Still to decode (next pass)

1. `com.tvs.bike.core.protocol.BtData`: the field list for the bike-to-app frames (`0x10`, `0x11`, `0x12`, `0x5F`, and others).
2. `BluetoothUtil.processDialer` and `BleEngine.processBikeWiseIncomingCall`: how the bike's call accept and reject are reported (the `0x10` byte 11 value of 2) and the call-state values.
3. `BluetoothUtil.processMusicControls` and `processMusicControlsUseBtData`: d-pad and music button codes.
4. `BluetoothUtil.getNetworkType`, `getCurrentNetworkType` and `getSignalStrength`: the network-type code table and signal range.
5. `BluetoothUtil.decryptDataForApache`, `encryptDataApache` and `isValidChecksum`: confirm that the obfuscation is a constant XOR of `0xEA` and the exact bike-frame checksum.
6. `BleEngine.observeDocTransferFromCluster`: the document transfer (Digi Docs) feature.

---

## 12. Digi Docs (document transfer to the cluster)

Source: the iOS app's `BikeBLEManager.sendDocument` and its helpers, written against the APK and checked against a Nov 21 HCI capture (its code comments cite 768,000 bytes in 6,000 chunks). The APK side is `ApacheClusterDataReceiver.handleDocTransferFrame` and `BleEngine.observeDocTransferFromCluster`; it has **not** been decompiled yet, so treat the byte layouts as "implemented and working in the iOS app", not as APK-verified. The 148 s capture analysed in sections 1 to 10 contains no document transfer.

### 12.1 Image format

| Item | Value |
|---|---|
| Size | 800 x 480 pixels (APK constants `0x320` x `0x1E0`) |
| Pixel format | RGB565, 2 bytes per pixel, low byte first |
| Payload | 800 x 480 x 2 = **768,000 bytes** |
| Fit | Image scaled to fit, centered, black background |
| Row order | Rows are sent from the bottom row to the top row (vertical reversal), no horizontal mirror |
| Slots | Picture number 1 to 5 |
| Name | Up to 10 ASCII characters (non-ASCII replaced with a space) |

### 12.2 Frames

Unlike the normal app frames, document frames use header `0x5A`, not `0x5B`.

**Init / complete frame, ID `0x53`, 23 bytes**

```
[0x5A][0x53][ 20 bytes, each XOR 0xEA ][0xFF]
 body[0]    picture number (1 to 5)
 body[1..2] total chunk count, big-endian   (6000 for a full image)
 body[3..4] current chunk, big-endian       (1 for the start frame, total for the end frame)
 body[5]    status: 1 = start, 3 = complete
 body[6]    0, except 5 for picture 4 and 10 for picture 5 (meaning unknown)
 body[7..16] name, ASCII, up to 10 bytes
 rest       0
```

**Data chunk, ID `0x55`, 134 bytes**

```
[0x5A][0x55][ sequence_hi, sequence_lo, 128 data bytes, checksum ] XOR 0xEA [0xFF]
 sequence   1 to total, big-endian
 data       128 bytes, zero padded for the last chunk
 checksum   (0xFF - ((0x5A + 0x55 + sequence_hi + sequence_lo + sum(data)) & 0xFF)) & 0xFF
```

**Acknowledgement from the bike, ID `0x54`, 23 bytes** (header `0x5A`, body XOR `0xEA` from byte 2 to byte 21)

| Frame byte | Meaning |
|---|---|
| 5, 6 | Last chunk number received, big-endian |
| 7 | Status: 1 = receiving, 3 = complete; anything else is treated as a rejection |
| 9 to 18 | ASCII label, `Document N` |

### 12.3 Transfer procedure (as implemented)

1. Send the init frame (status 1, current chunk 1). Wait 150 ms.
2. Send chunks 1 to total in order, as normal writes.
3. After every 20th chunk (except the last), wait up to 8 s for an acknowledgement with chunk number at least that chunk and status 1. A different status stops the transfer as a rejection.
4. Send the init frame again with status 3 and current chunk = total.
5. Wait up to 8 s for an acknowledgement with chunk number at least total and status 3. If it does not arrive, report "sent but unconfirmed".
6. Limit: 6,000 chunks, which is exactly one full 800 x 480 image.
7. The periodic `0x4A` status frame is paused during the transfer.
8. After a successful final bike acknowledgement, RTR Link writes a `0x4C` custom-text line using the observed free-text marker `0x41`, displays `<name> Done`, waits 2 seconds, and sends an all-zero text body to clear it. The bike text line holds 15 ASCII characters, so the name is capped at 10 to keep ` Done` visible.

### 12.4 Also sent by the iOS app at connection

| Frame | Purpose |
|---|---|
| `0x52` | Rider name (17 ASCII bytes) |
| `0x4C` | Welcome message: body `00 01` followed by up to 15 ASCII characters of the rider name |
| `0x4A` | First phone-status frame, then one every 2 s |

The `0x4C` frame is not in the 148 s capture; it comes from the iOS app and the APK's custom-text path (`sendCustomTextLineOne` and `apacheCustomTextLineOneArray`).

---

## 13. Bike-to-app data points: field map, scaling, status

Fields are listed by frame byte (decoded, so byte 2 is the first body byte). "Capture" values come from the 148 s capture, where the bike was stationary. "iOS" is the iOS app's current decoding. "APK" is what the decompiled `ApacheClusterDataReceiver` shows by field name and type only; **the APK scaling code has not been decompiled yet**, so every row marked "raw" still needs its scaling confirmed against `setSpeedometerDataByte5A10`, `5A11`, `5A12`, `5A16`, `5A18` and `5F`.

### 13.1 Frame `0x10` (speedometer 1, ~every 0.3 s)

| Byte | Field | iOS | APK field | Capture | Status |
|---|---|---|---|---|---|
| 2 | Speed (km/h) | km/h | `speed` | 0 | Decoded |
| 3 to 5 | Odometer | 24-bit, /10 km | `odometer` (double) | 275928 | Scale to verify against the cluster |
| 6 | Fuel level % | % | `fuelLevelPercentage` | 21 | Decoded |
| 7 | Average speed | km/h | `averageSpeed` | 33 | Decoded |
| 8 | Mileage | raw | `mileageCaptured` / instant mileage | 165 | **Raw** |
| 9 | Top speed | km/h | `topSpeed` / `vehicleTopSpeed` | 134 | Decoded |
| 10 | Throttle | % | `throttlePercentage` | 14 | Check at rest |
| 11 | Switch status | raw | `switchStatus`, `locationTag` | 0; **2 at 130.3 s** (the reject) | **Raw**; value 2 observed with the call cancel |
| 12 | 0 to 60 time | /10 s | `currentZeroTo60Time` | 56 = 5.6 s | Decoded |
| 13 | Average mileage | raw | `averageMileageDirect` | 0 | **Raw** |
| 14, 15 | Trip F-meter | 16-bit raw | trip meter fields | 0 | **Raw** |
| 16, 17 | Engine RPM | 16-bit | `engineRpm` (double) | 0 | Decoded |
| 18 | Checksum | not checked | `checkSum` | | Formula in section 10.1 |

### 13.2 Frame `0x11` (speedometer 2, on change)

| Byte | Field | iOS | APK field | Capture | Status |
|---|---|---|---|---|---|
| 2 | Vehicle direction / flags (bit `0x10` = side-stand sensor status in APK) | raw | `vehicleDirection2`; APK reads 1-based bit 5 | 0 | **Mask confirmed in APK; polarity/physical interpretation unverified** |
| 3 | Vehicle state 1 (bit `0x40` = side-stand status in APK; bit `0x01` = kill-switch status) | raw | `vehicleState1`; APK reads 1-based bits 7 and 1 | `0xC0` | **Side-stand state mapping confirmed by rider: set = stand down, clear = stand up** |
| 4 | Service reminder | raw | `serviceReminder` | 0 | **Raw** |
| 5 | Gear | int | `gearPosition` | 0 | Decoded; 0 may mean neutral |
| 6 | Battery voltage | raw | `batteryVoltage` (**double**, so scaled) | `0x0C` = 12 | **Raw**; scale unknown (looks like volts) |
| 7 | Speedo software version | raw | `speedoSwVersion` | 4 | **Raw** |
| 8 | MIL blink code | raw | `isAlertEngineMIL` | 0 | **Raw** |
| 9 | Vehicle model | raw | | 0 | **Raw** |
| 10 | Vehicle diagnostics | raw | `vehicleDiagnostics` | `0x20` | **Raw** |
| 11 | Reserved | raw | | 0 | **Raw** |
| 12 | **Turn indicator**: 0 off, 1 right, 2 left, 3 hazard | name table | `turnIndicatorStatus` | 2, 2, 2, 0, 1, 0 at 134 to 140 s | Decoded (corrects section 10) |
| 13 | Engine temperature / tell-tales | raw | `engineTemperature` (has a conversion function) | `0x50` = 80, then 79 at 137.5 s | **Raw**; probably degrees Celsius, offset unknown |
| 14 | Screen matrix | raw | | `0x23` | **Raw** |
| 15 | Vehicle state 3 | raw | | 0 | **Raw** |
| 16 | ABS MIL blink code | raw | | 0 | **Raw** |
| 17 | Low nibble: **ride mode** (0/9 Urban, 1/10 Rain, 2/11 Tour, 3/12 Rally, 4 Supermoto). High nibble: unknown flag, toggled 2 and 3 | name table | `rideMode`, `rideModeValue` | `0x22` / `0x32` = mode 2 (Tour) | Decoded; high nibble unknown |
| 18 | Checksum | not checked | | | Section 10.1 |

**Bit positions confirmed from APK static code.** In `ApacheClusterDataReceiver.setSpeedometerDataByte5A11`, the helper converts a 1-based bit position to a zero-based shift. The APK assigns `vehicleState1` bit position 7 (mask `0x40`) to `sideStandStatus`, `vehicleDirection2` bit position 5 (mask `0x10`) to `sideStandSensorStatus`, and `vehicleState1` bit position 1 (mask `0x01`) to `killSwitchStatus`. The rider has verified against the bike that the decoded side-stand status reports the actual state: set means down, clear means up. Both apps present this as “Down” / “Up.”

### 13.3 Frame `0x12` (ride data)

The `0x12` frame supplies one ride-statistics set. The rider confirmed that this set is Trip A, the distance is in tenths of a kilometre (`3194` = `319.4 km`), and the overspeed threshold matches the bike setting in km/h. Both apps show only these confirmed values, plus the decoded ride time. Other `0x12` fields remain hidden in the UI because their units or meaning are unconfirmed.

| Byte | Field in iOS | Likely APK field |
|---|---|---|
| 2 | Lean angle | derived or `gForce` |
| 3, 4 | Cruising range (16-bit) | `cruisingRange` |
| 5 | Wheel angle offset | |
| 6 | Acceleration | `acceleration` |
| 7 | Torque | |
| 8, 9 | Trip A distance (16-bit), tenths of km | `tripDistance` |
| 10, 11 | Trip time (hours, minutes) | `tripTime` |
| 12 | Trip mileage | `tripMileage` |
| 13, 14 | Trip fuel (16-bit) | `tripFuel` |
| 15 | Overspeed threshold in km/h | matches the bike setting |
| 16 | Overspeed setting | |

Capture value of the whole body: `00 00 45 00 0C 00 0C 94 07 29 1F B2 24 5A 01 00 22` (raw hex after XOR); the iOS field map above is what the app applies to it. In this example, distance `0x0794` (1940) would display as `194.0 km`; the rider separately confirmed the same tenths-of-kilometre scaling with value 3194 = 319.4 km.

### 13.4 Other frames from the bike

| Frame | Seen | iOS handles it? | What it is |
|---|---|---|---|
| `0x5F` | Every 10 s, alternating `00` and a `0x20` variant | Stores payload hex | `setSpeedometerDataByte5F`; field map unknown |
| `0x6B` | 2 in the capture | Yes, configurable phone media action | **Music command**; byte 2 values 2/3/4 are Down/Enter/Back double-press events per owner confirmation (section 10.4). Not XOR-obfuscated, so the checksum test fails for it by design |
| `0x7D` | 5 at connect | **No** | ASCII 17-character identifier (looks like a VIN). APK: `handleSpeedOMeterFrame5A7D` |
| `0x42` (header `0x5B`) | 5 at connect | **No** | Contains the bike's Bluetooth address (value redacted) |
| `0x16` | Not in the capture | **No** | APK `ApacheSpeedOMeter4`: lap number/time and best-lap fields; this is lap timing, not the A/B/C/day trip-meter set |
| `0x18` | Not in the capture | **No** | APK `ApacheSpeedOMeter5`: engine load, fuel injection, pressures, temperatures, voltage, runtime and distance-traveled fields; no A/B/C/day trip-meter set |
| `0x54` | Document ack | Yes | Section 12 |
| TPMS | Not in the capture | **No** | APK has tyre pressure, temperature and battery fields (front and rear) |

### 13.5 iOS code review against the above

1. **Matches the protocol:** frame builder (`5B`, XOR `0xEA`, `FF`), the `0x4A` layout including the battery bucket and 12-hour clock, the 2 s status rhythm, the 20-byte and 23-byte frame handling, the `0x6B` exception.
2. **`0x4A` gaps:** byte 10 (missed calls), 11 (network type), 15 (call state) and 16 (voice assist) are always 0, and byte 3 is fixed at `0x78`. The APK takes byte 3 from the overspeed setting.
3. **Mislabelled in the UI model:** `turnSignalCode` is right. `switchStatusRaw` and `locationTagRaw` both read byte 11 of `0x10`; the capture shows byte 11 = 2 during the call reject, so treat it as the switch status.
4. **Not decoded:** `0x7D` vehicle identifier (raw value withheld), `0x16`, `0x18`, TPMS, and the unknown bits above.
5. **Fields stored raw until scaling is confirmed:** battery voltage, engine temperature, cruising range, trip fuel, odometer scale. Trip A distance uses tenths of a kilometre, as confirmed by the rider.


### 13.6 Action-marker session, 2026-11-23

The iOS Xcode log for this session covers `05:38:14Z` to `05:39:45Z`: 577 notifications, 40 writes, all 40 with ATT success responses, six user action markers, and one disconnect with CoreBluetooth reporting an unexpected connection timeout. The bike continued notifying through approximately `05:39:33Z`; the disconnect callback arrived about 12 seconds later. The recurring `0x4A` status writes are logged about every 2 seconds through `05:39:32Z`. ATT success confirms the GATT write response, not that the bike application acted on the frame.

The `0x11` frame byte 3 (APK `vehicleState1`) changes among `0xC0`, `0x80`, `0x00`, and `0x40`. Applying the APK's bit masks, side-stand status (mask `0x40`) is 1 for `0xC0` and `0x40`, and 0 for `0x80` and `0x00`. Kill-switch status (mask `0x01`) is 0 in every observed value. Byte 2 (`vehicleDirection2`) stays zero, so the APK's side-stand-sensor bit (`0x10`) also stays clear.

Marker comparisons are suggestive but not conclusive: the “Side stand is on” marker at `05:38:29Z` is followed by a `0xC0` to `0x80` change at `05:38:33Z`; “Side stand off” at `05:38:38Z` is followed by no change in the APK side-stand bit; “Kill switch off” at `05:38:48Z` is followed by `0x80` to `0x00` at `05:38:49Z`, which changes neither the APK side-stand bit nor kill-switch bit; and “Kill switch on” at `05:38:54Z` is followed by `0x00` to `0x40` at `05:38:59Z`, changing the APK side-stand bit while the kill-switch bit remains clear. Because each marker timestamp records when the note was submitted (not necessarily when the physical action occurred), and some markers combine multiple actions, this capture cannot assign those raw transitions to a control with confidence.

The `0x10` fuel byte is 17 at connection and changes to 14 by `05:38:17Z`, then remains 14 through the last `0x10` notification at `05:39:33Z`. This supports the low-fuel observation for this session. The final `0x10` and `0x11` frames also change at `05:39:33Z`, but there is no action marker at that time, so their cause is unknown.

### 13.7 Trip A, B, C, and Day Trip meters

The analyzed Apache BLE receiver (`ApacheClusterDataReceiver`) assigns a single set of `tripDistance`, `tripTime`, `tripMileage`, and `tripFuel` from frame `0x12`. The rider has identified the currently observed set as Trip A. The frame does not establish separate live values for Trip B, Trip C, or Day Trip. The APK has `tripADistance` and `tripBDistance` properties in save-ride/API data models, but those model fields alone do not show that the values are read from live bike BLE notifications. Its `0x16` frame model is lap timing; `0x18` contains engine/diagnostic values. The app now presents the confirmed `0x12` distance as Trip A and omits unsupported extra trip-meter placeholders.

**Status:** fetching all four cluster meters remains unresolved. It needs a capture that includes their values changing (ideally while selecting each meter on the cluster) and identification of the corresponding notification or other authorized data source.

### 13.8 BLE session logs

The iOS app writes every received notification as `bike_notification_rx` and every actual phone-to-bike GATT write as `write_tx` to Xcode's debug console and the session JSONL file. Records include wire bytes, decoded bytes when the frame format is known, and byte-level mapping notes. Digi Docs chunks are logged too, so a full transfer creates many large console records. The iOS logger redacts identity-bearing `0x7D` and `0x42` frame contents. Android continues to use change-only `bike_data_changed` records with mapped values.

The Android parser was missing `0x11` byte 4 (`serviceReminderRaw`); it is now included. Remaining mappings are incomplete: `0x7D` vehicle identifier and `0x42` Bluetooth address are intentionally not exposed; `0x16` lap timing, `0x18` engine/diagnostic values, and TPMS are not parsed. The `0x5F` body is logged as raw unmapped payload. These are protocol gaps inferred from the APK reverse-engineering notes, not fields confirmed in the latest bike capture.
