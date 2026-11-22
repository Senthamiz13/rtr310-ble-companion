# TVS Bike BLE Protocol and Communication

How the phone app and the bike talk to each other over Bluetooth Low Energy (BLE). This is reverse engineered from official app HCI snoop captures (kept locally under `local_data/captures/`) and captured packet comparisons from the Python client. Runtime behavior may still differ from the Android app.

**Confidence levels used below**

- **Confirmed**: directly visible in the capture or established by the APK packet builder. Matching a byte does not, by itself, confirm that byte's meaning.
- **Inferred**: a reasonable reading of the data, not proven.
- **Unknown**: present in the data, meaning not determined.

## 1A. Confirmed indicator and call behavior

The following signal-status values have now been correlated with the rider's indicator actions:

| Decoded `0x11` byte 12 | Meaning | Evidence |
|---:|---|---|
| `0` | No indicator active | Seen between active indicator values in the earlier full capture; interpreted as off. |
| `1` | Right indicator | Rider-confirmed action/status correlation. |
| `2` | Left indicator | Rider-confirmed action/status correlation. |
| `3` | Hazard lights | Rider-confirmed action/status correlation. |

This is **bike-to-phone telemetry**: the APK's Apache parser calls this field `turnSignalLampStatus` at offset 12, and the phone reports it. It is not a D-pad command and it is not the phone's hazard-on command.

The APK also handles a bike-originated call-control action in Apache frame `0x11`, decoded byte 11: value `1` invokes Android's `TelecomManager.acceptRingingCall()`, `2` rejects, and `3` invokes the app's alternate reject path. The adjacent byte 12 is independently parsed as turn-signal status. The decompiled APK's accept-call path does **not** send an indicator/hazard BLE write. Therefore, if hazards come on when you answer from the bike, the evidence points to the bike/cluster firmware activating them as a local behavior associated with call acceptance (or another bike-side rule); the APK alone does not establish the exact firmware trigger. The current truncated HCI capture cannot verify the byte-11/byte-12 transition during that call.

To prove the trigger, make one controlled capture with full 20-byte packets from before the phone rings until after answering on the bike. Record the instant of the button press. Check whether the bike sends frame `0x11` byte 11 = `1`, whether byte 12 changes to `3`, and whether the phone sends any write at that instant. If byte 12 changes to `3` without an app-to-bike command, it confirms the bike reports/activates hazard locally; if an app write precedes it, decode that write before concluding.

---

## 1. The big picture

```
  Phone / Mac (central, GATT client)                    Bike (peripheral, GATT server)
  ----------------------------------                    ------------------------------
  scans, connects  ------------------------------------>  advertises as "<bike BLE name>"
                   <------------------------------------   Security Request ("please pair")
  passkey pairing  <------------------------------------>  bike shows passkey, phone types it
  enable notifications (CCCD = 0x0001)  --------------->
  write rider-name frame (0x52)  ---------------------->
                   <-----------------------------------    main telemetry frame ~3 per second
  write clock/status frame (0x4A) every 2 s  ---------->
                   <-----------------------------------    more telemetry notifications
  disconnect  ---------------------------------------->
```

- The **bike is the data source**. It streams telemetry (speed, RPM, fuel, gear, and so on) as notifications after the app subscribes. Main frames `0x10` and `0x12` each arrive about 3 times per second in this capture; total notifications average about 7.5 per second.
- The **app** sends a rider-name frame once, then a periodic frame carrying the phone's clock/date, signal level, battery bucket, and Android network-type value.
- One service, two characteristics. All traffic is 20-byte frames.

---

## 2. GATT layout (Confirmed)

| Item | Value |
|---|---|
| Device name (GATT `0x2A00`) | `<bike BLE name>` |
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
| 126.7 to 130.2 | Phone | Eight writes of a second text frame (`0x43`) every ~0.5 s (caller text redacted). |
| 144.4 | Phone | Disconnects. The bike does not drop the link. |

**What this means for a client**

1. Pairing must finish before notifications can be enabled. In this capture encryption began about 15 s after connecting, and the phone subscribed around 18 s after connecting; allow time for passkey entry and service setup.
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
5b 52 b8 83 8e 8f 98 ea ea ea ea ea ea ea ea ea ea ea ea ff
```

- `5b`: app header
- `52`: frame ID, rider name
- `b8 83 8e 8f 98` XOR `ea` gives `52 69 64 65 72`, which is ASCII `Rider`
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

### 5.2 Contact-info text, ID `0x43` (Frame confirmed; captured use likely caller name)

Seen 8 times at about 0.5 s intervals (126.7 to 130.2 s). The captured caller text is redacted. The APK routes `0x43` through `returnContactInfoPacket()` / caller-information code, so this is contact/caller information; the capture associates the text burst with a call. The exact behavior triggered on the bike is not established. The Python dashboard has a caller-name test that exercises the captured text-frame form without embedding personal call content.

### 5.3 Clock and status, ID `0x4A` (Confirmed layout, some fields Inferred)

The periodic frame. 294 of the 303 writes are this one. It carries the phone's clock and date.

| Frame byte | Meaning | Status | Example (17:58:58 on 14 Nov 2026) |
|---|---|---|---|
| 2 | `(signal_strength << 4) \| battery_bucket` | APK reads signal strength and maps phone battery percentage to a 0–5 bucket; capture shows `0x13`, `0x23`, `0x33` | `0x33` |
| 3 | Over-speed limit | APK default is 120; capture confirms `0x78` | `0x78` |
| 4, 5 | Unknown fields | Zero in this capture; APK may fill them for call/SMS data | `0x00` |
| 6 | Hour, 12-hour clock | Confirmed | `0x05` |
| 7 | Minute | Confirmed | `0x3A` |
| 8 | Second | Confirmed | `0x3A` |
| 9 | 1 if PM, else 0 | Inferred (always PM in the capture) | `0x01` |
| 10 | Missed-call related field | Zero in this capture; nonzero behavior was not observed | `0x00` |
| 11 | Android network-type code | APK reads its `NETWORK_TYPE` preference here; observed 0 for most of the session, then 3 and 4 | `0x00` |
| 12 | Day of month | Confirmed | `0x0E` (14) |
| 13 | Month | Confirmed | `0x0B` (11) |
| 14 | Year mod 100 | Confirmed | `0x1A` (26) |
| 15 to 18 | Other call/voice-assist fields | Zero in this capture; semantic mapping is incomplete | `0x00` |

Wire bytes for that example: `5b 4a d9 92 ea ea ef d0 d0 eb ea ea e4 e1 f0 ea ea ea ea ff`

The low nibble of byte 2 is the phone battery bucket, not a fixed `0x03`. The APK's `getIceBatteryLevel()` maps 0–9% to 0, 10–19% to 1, 20–39% to 2, 40–59% to 3, 60–79% to 4, and 80–100% to 5. This capture's low nibble is 3, which narrows battery to 40–59%; the exact phone percentage is not recoverable.

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
| `0x11` | `5A` | 29 | occasional | Gear and bike state; APK parser names byte 12 as turn-signal-lamp status |
| `0x5F` | `5A` | 12 | occasional | Almost all zeros. Unknown |
| `0x7D` | `5A` | 5 | at connect (77.6 to 78.9 s) | Vehicle identifier frame; payload redacted |
| `0x42` | `5B` | 5 | at connect | Bluetooth identity frame; payload redacted |
| `0x6B` | `5A` | 2 | rare | Bike-to-phone **music control** command; command value is byte 2 |

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

See [`../scripts/bike_protocol.py`](../scripts/bike_protocol.py) for the frame builders and [`../tests/test_bike_protocol.py`](../tests/test_bike_protocol.py) for the checks against captured bytes.

---

## 9. Open questions

1. What do the remaining unknown clock-frame fields (especially bytes 4, 5, 10 and 15–18) carry during calls or messages?
2. Why does the phone speed the clock frame up to ~5 per second after 89 s?
3. What is the `0x43` frame for, and what triggered it?
4. What do the `0x12` and `0x5F` notifications carry?
5. Does the bike stop streaming, or disconnect, if the clock frame stops?
6. Are the odometer and throttle conversions correct?

Items 1 to 4 can be answered by capturing the phone app again while changing one thing at a time (for example, toggling mobile data, receiving a call, changing the rider name) and diffing the frames.

---

## 10. Calls, bike controls, navigation and media

The earlier full capture (about 98 to 144 s) contains caller-info traffic and state changes. APK analysis and the later `btsnooz_hci.log` now resolve some of those frames; other values remain uncertain.

### 10.1 Bike frame checksum (Confirmed)

Bike-to-app frames carry a checksum in the **last body byte (decoded frame byte 18)**:

```
byte18 = (0xA5 - frame_id - sum(decoded bytes 2..17)) mod 256
```

It holds for every `0x10`, `0x11`, `0x12`, `0x5F` and `0x42` frame in the capture (all 493 of them). It does not hold for the identity frame `0x7D` or the special music-control frame `0x6B`. The app's own frames do **not** carry this checksum, and the bike accepts them anyway (your Python client works without it).

Use it to validate incoming frames, and to tell real state changes from corrupted frames.

### 10.2 Observed events, in time order

| Time (s) | Direction | Observation |
|---|---|---|
| 98 to 125 | Bike to app | Frame `0x11` byte 17 toggles `0x22` / `0x32` (with byte 18 following the checksum) 8 times. Meaning Unknown. |
| 125.77 | App to bike | Clock frame `0x4A` byte 11 (Android network-type value per APK code) changes **0 to 3**. |
| 126.67 to 130.16 | App to bike | Eight `0x43` caller/contact frames, 0.5 s apart; personal text redacted. |
| 130.27 and 130.55 | Bike to app | Two telemetry frames (`0x10`) with **byte 11 = 2** instead of 0. Seen nowhere else. |
| 134.24 to 140.25 | Bike to app | Frame `0x11` **byte 12** goes 2, 2, 2, 0, 1, 0. With the later rider-confirmed mapping, this is left, left, left, off, right, off. Byte 13 drops `0x50` to `0x4F` (80 to 79) at 137.47; its meaning is unknown. |
| 142.54 | App to bike | Network-type byte changes **3 to 4**. |
| Every 10 s | Bike to app | Frame `0x5F` alternates between all zeros and a variant with byte 12 = `0x20` (a 10 s bike heartbeat). |

### 10.3 Call alert, best reading

| Piece | Reading | Confidence |
|---|---|---|
| `0x43` frame | Caller/contact-name text: the APK routes this frame family through caller-information code. | High for purpose; the frame alone does not create or prove call/ringing state. |
| Network-type byte going to 3 | APK reads byte 11 from its `NETWORK_TYPE` preference. Its change shortly before the caller-name frame is temporal coincidence in this capture; it is not evidence of a call-state flag. | High for the field's APK source; the trigger for values 3 and 4 in this capture is unknown. |
| Bike `0x10` byte 11 = 2 (130.27 s) | **The rider's cancel / reject**, reported by the bike. It appears 0.1 s after the last name frame and nowhere else. | Medium. The app stopped sending names at 130.16 s, just before it. It could instead be a fixed 4 s ring timeout. |

### 10.4 D-pad music commands (Confirmed by APK and the new capture)

The RTR 310 reports handlebar music-control input to the phone as frame `5A 6B`; the APK classifies it as `MUSIC_CONTROL_PACKET` and reads byte 2 as the command. `BluetoothUtil.getMusicCommandType()` maps `0` to Play, `1` to Pause, `2` to Toggle, `3` to Next, and `4` to Previous (it also defines `5`/`6` as volume up/down). The handler sends Android media key events to the active audio session; Toggle checks whether music is active and chooses pause or play. In the new capture, the visible bike notifications contain byte-2 values `3`, `4`, and `2`, each repeated twice. They line up with Next, Previous, and Toggle commands. Each pair is followed by the phone writing refreshed media metadata/state (`0x62`, `0x63`, `0x61`, `0x60`). The capture proves these command values, but not the physical direction or exact button press pattern that generated them.

The earlier interpretation that `0x11` byte 12 was a D-pad code was incorrect. The APK's Apache parser names it `turnSignalLampStatus`; it is bike status telemetry, not the music-control command. The rider-confirmed mapping is `1=right`, `2=left`, `3=hazard`; `0` is interpreted as off.

### 10.5 Indicators and hazard lights

The APK's Apache data parser reads frame `0x11`, byte 12 as turn-signal-lamp status and publishes it in the bike telemetry model. Correlated values: `0=off`, `1=right`, `2=left`, `3=hazard` (rider-confirmed). The separate global `TSL_LEFT`, `TSL_RIGHT`, and `TSL_HAZARD` constants are not the basis for this mapping.

When accepting a call from the bike, Apache `0x11` byte 11 is an action field: APK code maps `1` to answer, `2` to reject, and `3` to a second reject mode. For `1`, the app calls Android `acceptRingingCall()`. The inspected accept path contains no BLE write that sets hazards. The most likely explanation for hazards activating at answer is a bike/cluster firmware behavior linked to the bike's call-accept action; this remains an inference until a full HCI trace captures the byte-11 action, byte-12 status, and any simultaneous phone writes. Do not treat the call action value `1` as the right-indicator status: they are different byte offsets in the same frame.

The **Nov 14 `btsnooz_hci.log`** capture has 17 `0x11` notifications, but its packet truncation removes byte 12. Thus the mapping above is documented from the rider's observed actions, but those particular transitions cannot be validated against that file. A later full-packet capture is summarized in section 10.9; it still does not contain a controlled call-answer or stand-toggle event.

### 10.6 Signal, battery and the rest of the app-to-bike data

| Item | Where it is in the capture | Confidence |
|---|---|---|
| Signal strength | Clock frame byte 2, high nibble; APK reads its signal-strength preference | High for source field; exact scale is not independently verified |
| Phone battery | Clock frame byte 2, low nibble, mapped by APK to a 0–5 battery bucket | High for source field; capture shows bucket 3 (40–59%), not exact percent |
| Android network type | Clock frame byte 11; APK reads its `NETWORK_TYPE` preference | High for source field; values observed include 0, 3, 4 |
| Call alert | `0x43` caller name; byte 11 is network type, not a call-state field | Medium |
| SMS, missed call, navigation, and similar | Not exercised in this capture | None |

### 10.7 How to settle it

1. To independently verify indicator/hazard mapping, capture full 20-byte `0x11` notifications and toggle left, right, and hazard one at a time with timestamps. The current mapping is rider-confirmed.
2. For D-pad mapping beyond media commands, capture one physical key action at a time and correlate bike `0x6B` command frames with the action. The current capture already maps `3=Next`, `4=Previous`, and `2=Toggle`.
3. Probe from Python by changing signal level, battery bucket, and network type one at a time. Treat `0x43` as caller/contact information, not proof of ringing state by itself.

### 10.8 New HCI capture: `btsnooz_hci.log` (2026-11-14, 19:38–19:41)

This capture begins mid-session; it does not contain the connection, pairing, service discovery, or notification-subscription sequence. TShark reports **15 captured bytes out of 32 on every ATT packet**, leaving only the first three ATT value bytes. That is enough to see the `0x6B` music command byte, but not later fields such as incoming `0x11` byte 12 or most caller-name text. Treat the counts below as evidence of traffic, not complete packet decodes.

- **Telemetry stayed active:** 823 notifications on handle `0x0022`, from frame 2 through frame 1586. The captured prefixes identify 394 each of `5A 10` and `5A 12`, 17 of `5A 11`, 12 of `5A 5F`, and 6 of `5A 6B`. The six `0x6B` notifications contain byte-2 values `3,3,4,4,2,2`: APK maps these to Next, Previous, Toggle. The longest gap between all notifications was about **330 ms**. This capture does not show a telemetry stall before disconnect.
- **Phone writes:** 140 ATT Write Requests on handle `0x0026`. The visible prefixes classify 91 as `5B 4A` status frames, 10 as `5B 43` contact/caller-text frames, 19 as `5A 4E` navigation frames, and five each as `5B 60`, `5B 61`, `5B 62`, and `5B 63` media frames. All 140 custom-characteristic writes received ATT Write Responses. The 10 `0x43` writes appear in three bursts (4, 4, then 2) about half a second apart, rather than the eight-frame burst in the older capture. The truncated payload prevents recovering the caller name or telling whether each burst corresponds to a separate call event.
- **Navigation:** `ApacheClusterNavigationData` in the APK defaults to header `5A 4E`. The 19 matching writes appear in several short bursts from about 11.4 to 18.5 seconds into the captured session. That strongly matches your report that you used map navigation. The packet contents are truncated, so turn instructions, distance, and route text cannot be recovered here.
- **Spotify/media:** the APK's music sender emits song title `0x62`, artist `0x63`, album `0x61`, and Apache play/pause state `0x60`. The bike sent `0x6B` command values `3` (Next), `4` (Previous), and `2` (Toggle), each twice. Those commands line up with your D-pad track and play/pause use. The app then sent five four-frame media metadata/state sequences; three followed the commands, while two likely represent other media-session updates. Payload truncation prevents reading track names or the play/pause state byte.
- **Indicators / hazard:** the APK's Apache parser identifies `0x11` byte 12 as `turnSignalLampStatus`; rider-confirmed values are `1=right`, `2=left`, `3=hazard` (`0=off`). The new capture contains 17 such frames but is truncated before byte 12, so those logged indicator actions cannot be correlated to this mapping from this file. Similar-looking global TSL constants are unrelated evidence and were not used for the mapping.

### 10.9 Full HCI capture: `btsnoop_hci.log` (2026-11-21)

Source: `local_data/captures/btsnoop_hci.log` (SHA-256 `a33dfc299cd678293327b6806db3e5316836f0d918a7700cad11e0ccf0a7d541`). The same file is in the Android bugreport's Bluetooth log folder. Unlike the Nov 14 `btsnooz` capture, this log contains complete 20-byte application frames.

- **Explicit alert text:** the phone writes custom-text frame `0x4C` decoded as **“Alert Enabled”** at 11:41:09.933 and **“Alert Disabled”** at 11:41:15.692; another “Alert Enabled” is sent at 12:06:39.748. Each is followed by a zero-filled `0x4C` clear frame within about half a second. These packets travel **phone → bike**, so they are cluster-display text sent by the app, not a warning received by the phone. The BLE trace alone does not establish what user action or alert condition triggered them.
- **Stand telemetry:** the APK's `ApacheSpeedOMeter2` / `ApacheClusterDataReceiver` model includes `sideStandStatus` and `sideStandSensorStatus`, derived from bits in frame `0x11`. There are 132 complete `0x11` notifications in this capture. The corresponding stand bits do not change in this session, so this file provides no side-stand transition to correlate with a mobile alert. Their active/inactive polarity still needs a controlled test with the physical stand.
- **Other warnings:** no bike-to-phone BLE text frame containing a stand warning or other warning message appears in the capture. This does not rule out an iOS/Android notification created locally from telemetry, or delivered through the app's network connection; an HCI BLE snoop only shows Bluetooth traffic. The `0x11` telemetry does carry diagnostic, tell-tale, temperature, and MIL-related fields in the APK model, but the warning thresholds and all codes are not decoded well enough here to label a fault.
- **Useful next capture:** with the official app connected, toggle the side stand once while stationary, note the exact time, then capture the resulting phone notification. Compare the full `0x11` frames immediately before and after and check whether any `0x4C` write or phone notification occurs. Avoid riding or relying on an experimental app for safety warnings.
- **Disconnect:** notifications continued through frame 1586. The phone then sent a local HCI Disconnect command (frame 1587); the controller reported `Connection Terminated by Local Host (0x16)` at frame 1589. As in the prior Android trace, this identifies the local host as the disconnect initiator, but cannot distinguish an app-requested disconnect from an Android stack/host close. It does not support the theory that notifications stopping caused this disconnect.

**APK correlation:** `TelephonyUtils.returnContactInfoPacket()` constructs `5B 43`; `BlePacketsProvider.sendApacheMobileData()` constructs `5B 4A`; `ApacheClusterNavigationData` uses `5A 4E`; and `BleMusicDataSourceImpl`/`BlePacketsProvider` build title, artist, album, and play/pause packets (`5B 62`, `5B 63`, `5B 61`, `5B 60`). The bike's `5A 6B` command byte also maps to Next, Previous, and Toggle in the APK. Together with your description, this capture records navigation and D-pad-driven media actions. It cannot confirm indicator/hazard states or mock battery/signal values because those bytes were truncated. The `0x43` caller-information traffic is also present, although you did not mention placing or receiving a call during this session.
