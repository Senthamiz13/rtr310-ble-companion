# RTR Link for Android

Native Android companion app for Apache RTR riders. This is an independent fan project and is not affiliated with or supported by TVS Motor Company.

## Current implementation

- BLE scan for the TVS custom service, connect, discover GATT characteristics, and subscribe to notifications.
- Auto-connect to the previously selected motorcycle, with a foreground service for a persistent connected session.
- Live speed, RPM, odometer, fuel, gear, riding mode, indicators, throttle, and side-stand state.
- Confirmed Trip A distance (scaled in tenths of a kilometre) and overspeed threshold, plus an expandable view of additional raw/provisional fields.
- Rider name setting; send the rider-name frame and personalized welcome when telemetry notifications are enabled.
- Phone-status frame every two seconds while incoming bike telemetry is active.
- Timestamped action markers and BLE send/receive event logging to Logcat and an app-private JSONL session file.
- Saved Digi Docs images, slots 1–5, 800 × 480 RGB565 conversion, and the observed `0x53` / `0x55` / `0x54` transfer flow.

## Open in Android Studio

Open this `android/` directory as a project. Android Studio will download the Gradle Android plugin and SDK components if they are not already installed. Use a physical Android phone; BLE is not available in the emulator.

Set the app's signing configuration in Android Studio to produce a distributable APK or App Bundle. Do not commit signing keys, private bike captures, the proprietary TVS Connect APK, or rider-specific logs.

## BLE permissions and first connection

Allow **Nearby devices** when prompted (and Location on older Android versions). Keep the bike nearby with ignition on. The app scans for the known TVS custom service and will connect to the previously selected device, or the first matching bike if none has been selected. Configure the preferred bike and rider name in Settings.

The foreground notification is required by Android to keep the BLE connection alive while the app is backgrounded. Android controls background execution and scan behavior, so reconnect is not guaranteed to be instantaneous. Stop the connection from the app before riding if you do not want it active.

## Protocol and limitations

The app uses the frame layout documented in [`../docs/bike_ble_protocol.md`](../docs/bike_ble_protocol.md). Confirmed mappings include the side-stand state, Trip A distance, and overspeed threshold. `0x12` contains one ride-statistics set; Trip B, Trip C, and Day Trip are not separately mapped in the currently analyzed live BLE feed. Other values remain raw or provisional until their meaning and scaling are verified. The phone signal value in the status frame remains an observed placeholder rather than a measured signal-strength mapping.

Document transfer is based on the captured Apache protocol. Test only while the motorcycle is stationary and keep the app connected until it reports completion. The bike can reject or time out on unsupported image content or transfer state.

## Build environment

- Android Studio with Android SDK Platform 36
- Java 17
- Android Gradle Plugin 9.1.0 and Gradle 9.3.1 (downloaded by Android Studio)

See the repository root README for project safety notes and protocol confidence levels.
