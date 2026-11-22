# TVS Connect feature analysis

Static analysis of the supplied Android APK with Apktool. The APK was not run, so UI behavior and backend responses were not runtime-verified. Names below refer to decompiled package/class paths under `smali_classes*` in the APK.

## Digital documents (Digi Docs)

- `ApacheSelectDocumentFragment` provides camera and gallery selection, image cropping, and a document-name field.
- The fragment saves a local JPEG copy at quality 100, and separately prepares the cluster payload: it renders the cropped bitmap into an 800×480 transfer view (`0x320 × 0x1E0`), backed by an `RGB_565` bitmap, rotates that bitmap 180°, splits it into left/right 400×480 halves, copies their two-byte pixel data, and concatenates the halves. The resulting payload is 768,000 bytes (6,000 128-byte chunks), matching the supplied HCI capture. The BLE payload is raster pixel data, not the JPEG or PNG file bytes.
- The resulting `DocumentDataModel` records document metadata and the prepared image data in the local database through `DocumentsViewModel.saveDocument`; the upload flow is local-first, not evidence of a cloud document upload.
- `DocumentsViewModel` delegates storage to `DocumentStorageUseCase` and `DocumentRepository`; the document DAO uses a local document table. This is a local app-side copy, not evidence of a cloud upload.
- The RTR U400 path is implemented in `com.tvsm.unified.apache.utils.ApacheDigiDocUtils`; the APK also contains a separate generic `BLEImageTransferUtils` for other bike types. For RTR U400, transfer setup sends `5A 53` with picture/slot, total and current frame numbers, a status, and a short label. Data is sent in numbered `5A 55` frames with 128 payload bytes. The BLE helper appends the checksum and applies the cluster's XOR transform. The app sends data in 20-frame bursts; bike `5A 54` responses report progress, request a retransmission, report completion, or indicate a slot/memory issue. The APK resumes at the requested frame and has explicit delete and firmware-support paths.
- The 2026-11-21 HCI capture confirms one 6,000-frame transfer (768,000 bytes of chunk payload) completed with status 3 (`docImageTransferCompleted`). This is the largest transfer in that capture, not a demonstrated fixed limit in the APK: the APK derives frame count from the prepared bitmap byte array. The bike's actual maximum image size remains unverified.
- **Why the web transfer may have failed:** the current web endpoint accepts a PNG and chunks those compressed file bytes directly. That does not match the APK's cropped/resized/rotated raster-pixel payload. Also, the web loop awaits a BLE write-with-response and then sleeps another 30 ms after every frame; the APK sends 20-frame bursts and waits for bike progress between bursts. The PNG size cap can reject files above 768,000 bytes, but an under-limit PNG can still have a different payload format, and the extra per-frame delay can substantially lengthen a full transfer. These code differences make format and pacing the stronger explanations for a partial sync; the exact cause of the user's earlier attempt is not proven without its session log.

## Map navigation

- The APK has both Mappls and HERE navigation implementations (`MapplsNavigationFragment`, `HereMapsNavigationFragment`).
- Apache navigation data is represented by `ApacheClusterNavigationData`: default start byte `0x5A`, data ID `0x4E`, distance to maneuver, remaining time/distance, pictogram code, number of text strings, navigation status, units, alert, prefix/reserved bytes, and end byte. Its `toByteArray()` method serializes the record.
- Map navigation sends turn-by-turn state to the bike cluster over BLE; it is not just showing a map on the phone. The project's existing BLE capture notes also correlate Apache `5A 4E` writes with active map navigation.
- Reproducing this requires a supported route source/provider (Mappls/HERE or another source) plus the navigation frame and write cadence. It is feasible to implement as an incremental iOS feature, but the exact pictogram table and full payload behavior should be validated against captures.

## Break / engine temperature alert

- The resource text `Take a break to cool down the engine` is associated with `u368_eng_temp_alert` and the U368 engine-temperature alert model (`U368AlertsUtils.getAlertModelEngineTemp`). The model includes the engine-temperature frame value.
- In this APK, that wording is an engine temperature warning, not proof of an elapsed-hours rider-rest reminder. I found no evidence in the Apache receiver that emits this alert on a ride-duration timer. The `TAKE_A_BREAK` token appears in voice-assistant / other vehicle flows and is not itself proof of a timed reminder.
- So for the Apache RTR 310, the observed “break” alert should be treated as unconfirmed until a matching RTR310 code path or live observation is found. If the user's observation is that a reminder appears after hours, it may be a different model/feature or a dashboard alert forwarded by another component.

## Current riding mode

- `ApacheClusterDataReceiver` parses the incoming `0x5A 0x11` cluster frame. It reads the ride-mode byte, masks the low nibble (`getRideMode(B)`), passes it through `ApacheUtils.getApacheModesForRidingPattern`, then stores the result in `ApacheBleDataModel.rideMode`.
- `getApacheModesForRidingPattern` maps mode IDs 0–3 to themselves and IDs 9–12 to 0–3. The UI maps the resulting 0–3 values to URBAN, RAIN, TOUR, and RALLY, respectively, for one Apache vehicle family; another branch is vehicle-type dependent.
- In the 2026-11-21 capture, the bike sent low-nibble codes 0, 1, 2, 3, and 4 in `0x11` byte 17. The rider confirms Track is Rally, Sport is Tour, and code 4 is Supermoto. Transitions to codes 1–4 appear in the log, corroborating the app's mode-data path and the current iOS decoder. Code 0 uses the APK's Urban/default mapping.

## Voice assistant

- The rider reports that TVS Connect starts voice-assist behavior when the bike connects, and a single D-pad click can trigger it. The rider's first spoken phrase produces a personalized welcome on the bike display. This behavior is rider-confirmed; personal display text is omitted from this public note.
- In the 2026-11-21 captures, the phone sends custom-text (`0x4C`) frames including “Alert Enabled,” “Alert Disabled,” and volume feedback. These confirm changing text/status is sent to the cluster, but do not contain the spoken phrase or the reported welcome message itself. Other identifying display text is omitted from the notes.
- The full 2026-11-21 HCI trace shows “Alert Enabled” and “Alert Disabled” as phone-to-bike (`0x4C`) writes, each followed by a clear frame. Treat them as cluster display feedback; they are not received mobile alerts. The capture contains no explicit side-stand warning text from the bike. Although the APK parses side-stand status from `0x11` telemetry, that field did not transition in this session. See the detailed evidence and limitations in `bike_ble_protocol.md` section 10.9.
- The capture's early custom-text packet includes only a partial rider-name fragment, not the full greeting. A later capture has repeated changes in otherwise-unmapped `0x11` bytes near the reported interaction; neither APK parsing nor the capture maps those changes to a D-pad event yet.
- The current iOS app does not yet implement this voice-assistant flow. Porting it requires identifying the exact incoming bike packet for the D-pad click, then adding speech recognition and command handling on iOS. The trigger packet must be verified before wiring it to microphone activation.

## Relevance to our iOS app

1. Current telemetry decoding exposes ride mode from actual `0x5A 0x11` packets; the new capture corroborates codes 1–4 and their labels provided by the rider.
2. Map navigation is a larger but clear BLE feature: route engine -> turn instruction -> `0x5A 0x4E` serialization -> write to the bike.
3. Document sync is technically feasible and the main frames are now known. It needs a separate transfer state machine (init frame, chunking, sequence/progress, acknowledgements, resume/delete, firmware support checks). It should not be mixed into the periodic telemetry write loop.
4. A rest alert should be implemented only after confirming whether the RTR310 app uses a timer, an engine-temperature threshold, or a bike-originated notification. The inspected string directly supports the temperature-warning explanation for U368.
