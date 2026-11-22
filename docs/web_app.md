# TVS bike telemetry web app

This is a local web dashboard that scans for the RTR 310, connects over BLE, subscribes to the bike's `ST` characteristic, and streams telemetry updates to the browser.

## Start it

From the project folder, install the web dependencies into the existing environment and launch the server:

```sh
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python scripts/bike_web.py
```

Open [http://127.0.0.1:8765](http://127.0.0.1:8765), scan for the bike, select it, and connect. Keep the bike nearby with ignition on. The server binds to `127.0.0.1`, so the dashboard is available only on this Mac.

The startup rider-name frame is enabled by default because it preceded the telemetry stream in the observed run; that timing does not prove it is required. The text is sent to the bike; it is not a screen-control command. The HCI capture and APK show the Android app's repeating clock/status frame is `0x5B 0x4A`. The dashboard builds this frame with signal strength, the APK's phone-battery bucket, network-type code, and this Mac's clock/date. Use **Send status now** to write the selected mock values immediately while connected; subsequent two-second writes use those values. Dashboard defaults (signal 4/4, battery 80%, network code 13) are synthetic, not readings from the phone. The official capture also shows bursts of `0x4A` writes around every 200 ms; that trigger is not yet understood, so the Mac's repeating cadence is still an approximation.

**Send caller-name test** writes the observed `0x43` text frame eight times, 500 ms apart. It tests the caller-name/contact-info payload seen in the capture; it does not create an incoming call on the phone or guarantee a ringing/call screen on the bike. The handlebar music-controls panel listens for bike frame `0x5A 0x6B`: APK code maps command values 0/1/2/3/4/5/6 to play/pause/toggle/next/previous/volume up/volume down. Adjacent duplicate command notifications within 100 ms are combined into one UI event, while every notification remains represented in the session log. Physical D-pad directions are not mapped. Frame `0x11`, byte 12 is turn-signal status: `0=off`, `1=right`, `2=left`, `3=hazard` (rider-confirmed); the dashboard shows both label and code. The APK's incoming-call handler reads byte 11 of Apache `0x11`: `1=answer`, `2=reject`, `3=alternate reject`. It answers through Android Telecom and does not issue a hazard BLE write in the inspected call path. Hazard activation upon call answer therefore appears bike/cluster-side; confirming the exact trigger needs an untruncated HCI capture spanning the call and answer. Session logs record test events without storing the entered caller name; raw frames are only included when `BIKE_LOG_RAW=1` is set, which may include names and other identifying values.

**Custom display text** sends one 20-byte `0x5B 0x4C` frame. The text is limited to 15 printable ASCII characters and uses a first-pass control prefix based on the captured personalized-text frame. Check the cluster to verify how the bike renders it. **Digi Docs image upload** currently accepts a PNG up to 768,000 bytes, asks before sending, then transmits 128-byte chunks and waits for bike progress acknowledgements. This is not yet format-compatible with the TVS app: APK inspection shows TVS crops/resizes and rotates the selected bitmap, then sends its pixel data, not the original compressed PNG/JPEG bytes. The 6,000 chunks were the largest captured transfer, not a verified APK file-size limit. The current Python sender also adds a 30 ms delay after each BLE write-with-response, while the APK sends in 20-frame bursts and resumes from bike progress acknowledgements. These differences can explain an image that transfers partially or stalls; the cause of a specific earlier attempt needs its session log to pinpoint. Image transfer in the web app remains experimental.

Each BLE connection attempt writes a timestamped JSON Lines log to `local_data/session_logs/`. The dashboard shows a **Session log** link to download the active or most recent log. Logs include connection milestones, startup and cyclic write results and sequence numbers, notification frame types and decoded telemetry, and whether the app requested disconnect or received a link-loss callback. Raw BLE payloads, rider names, device addresses, vehicle identifiers, and MAC values are not written.

## Telemetry fields

The dashboard decodes speed, RPM, odometer, fuel percentage, average speed, recorded top speed, throttle, 0–60 time, gear, and turn-signal status from APK-derived offsets. Turn-signal codes display as `Off`, `Right`, `Left`, or `Hazard` for `0`, `1`, `2`, or `3`, respectively. It also displays music-control commands sent from the bike to the phone. Unmapped accessory and identity frames are omitted from the page. The indicator code meanings are rider-confirmed; other decoded values should still be compared with the instrument cluster.
