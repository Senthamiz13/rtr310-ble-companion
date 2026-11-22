# Bluetooth capture findings

This document summarizes findings from the owner's Android Bluetooth HCI captures while using TVS Connect with an Apache RTR 310. Raw captures are deliberately not included: they can contain device identifiers, pairing exchanges, caller/display text, and personal document image data.

For frame layouts and confidence levels, see [`bike_ble_protocol.md`](bike_ble_protocol.md). APK-derived feature findings are summarized in [`tvs_app_features.md`](tvs_app_features.md).

## Connection and telemetry

- The bike advertises a custom BLE service. The phone connects, completes passkey pairing when requested, discovers the service, and subscribes to the bike's notification characteristic.
- The bike sends recurring `0x10`, `0x11`, and `0x12` telemetry frames. The app also sees `0x5F`; that frame remains unmapped.
- The Android captures show sustained notification traffic while connected. Some sessions end with an Android local-host disconnect; HCI evidence alone cannot tell whether the app or Android's Bluetooth stack requested it.
- `0x11` carries gear, indicator status, and riding-mode fields. The rider confirmed indicator codes `0=off`, `1=right`, `2=left`, and `3=hazard`, and identified modes including Tour, Rally, and Supermoto.

## Phone-to-bike traffic observed

| Frame | Observed use |
|---|---|
| `0x52` | Rider name during connection setup |
| `0x4A` | Periodic phone status, including battery/network-related values |
| `0x43` | Caller/contact display information |
| `0x4C` | Custom text/status messages, including voice-assist feedback |
| `0x4E` | Navigation updates |
| `0x60`–`0x63` | Music playback state and track metadata |
| `0x53`, `0x55` | Digi Docs transfer start/completion and image data chunks |

The APK uses phone-status writes on a short repeating cadence. Captures show both roughly two-second intervals and faster bursts; the trigger for every burst is not established.

## Digi Docs transfer

A captured transfer used a `0x53` setup/completion frame, followed by `0x55` image chunks and `0x54` bike acknowledgements. The observed image payload used 128 data bytes per chunk and 6,000 chunks. The bike returned progress and completion statuses. The image content itself was not extracted or copied into the project.

The iOS client implements this transfer flow with acknowledgements. Verify it using a non-sensitive image and a stationary bike; firmware limits and retry behavior may differ.

## Riding mode, indicators, and controls

- Mode comes from the low nibble of byte 17 in `0x11`. The rider-confirmed labels are Urban, Rain, Tour (Sport), Rally (Track), and Supermoto.
- Indicator status is reported by the bike in byte 12 of `0x11`; it is not an app command.
- Bike-originated `0x6B` notifications carry music commands. Observed values include next, previous, and play/pause toggle; duplicate notifications were seen for some actions.
- The rider reports that connecting in TVS Connect starts voice-assist behavior and a single D-pad click can trigger it. Captures show related `0x4C` display text, but do not identify the exact D-pad trigger packet or contain microphone audio.
- When answering a call from the bike, the rider observed hazards. The inspected app path accepts the call but does not send an indicator command; the exact bike-side trigger remains unconfirmed.

## Known unknowns

- The byte meanings, scaling, and some state-bit polarities in `0x10`–`0x12` need controlled tests.
- The `0x5F` frame has no confirmed field map.
- The precise app/OS reason for local-host disconnects is not visible from HCI events alone.
- Voice-assist D-pad trigger details and the bike firmware's hazard behavior during call acceptance remain unknown.

## Handling future captures

Keep original HCI logs under the ignored `local_data/captures/` directory. Before sharing a derived excerpt, remove Bluetooth addresses, device names, VIN/serial-like strings, caller/rider names, pairing material, location data, and document image payloads. Prefer publishing decoded summaries over full packet traces.
