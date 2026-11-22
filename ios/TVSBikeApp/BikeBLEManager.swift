import CoreBluetooth
import Combine
import Foundation
import UIKit

struct BikeDevice: Identifiable {
    let id: UUID
    let name: String
    let rssi: Int
    let peripheral: CBPeripheral
}

struct BikeTelemetry {
    var speedKph: Int?
    var engineRPM: Int?
    var odometerKm: Double?
    var fuelPercent: Int?
    var averageSpeedKph: Int?
    var recordedTopSpeedKph: Int?
    var throttlePercent: Int?
    var zeroTo60Seconds: Double?
    var gear: Int?
    var turnSignalCode: Int?
    var rideModeCode: Int?

    // Additional Apache 0x10 values. These are kept in wire units until
    // their display scaling has been verified against a controlled ride.
    var mileageRaw: Int?
    var switchStatusRaw: Int?
    var locationTagRaw: Int?
    var averageMileageRaw: Int?
    var tripFMeterRaw: Int?

    // Apache 0x11 vehicle state and diagnostics.
    var vehicleDirectionRaw: Int?
    var vehicleState1Raw: Int?
    var serviceReminderRaw: Int?
    var vehicleState2Raw: Int?
    var batteryVoltageRaw: Int?
    var softwareVersionRaw: Int?
    var milBlinkCodeRaw: Int?
    var vehicleModelRaw: Int?
    var vehicleDiagnosticsRaw: Int?
    var reserve3Raw: Int?
    var reserve14Raw: Int?
    var engineTemperatureRaw: Int?
    var tellTaleStatusRaw: Int?
    var screenMatrixRaw: Int?
    var vehicleState3Raw: Int?
    var absMilBlinkCodeRaw: Int?
    var vehicleModeRaw: Int?
    var sideStandStatus: Bool?
    var sideStandSensorStatus: Bool?

    // Apache 0x12 ride data. Raw values are shown because unit/scaling
    // confirmation is incomplete.
    var leanAngleRaw: Int?
    var cruisingRangeRaw: Int?
    var wheelAngleOffsetRaw: Int?
    var accelerationRaw: Int?
    var torqueRaw: Int?
    var tripDistanceRaw: Int?
    var tripTimeHoursRaw: Int?
    var tripTimeMinutesRaw: Int?
    var tripMileageRaw: Int?
    var tripFuelRaw: Int?
    var overspeedThresholdRaw: Int?
    var overspeedSettingRaw: Int?

    // No field map is known for 0x5F yet; retain its decoded payload for
    // investigation instead of assigning speculative names.
    var unknown5FPayloadHex: String?

    var rideModeName: String? {
        guard let rideModeCode else { return nil }
        switch rideModeCode {
        case 0, 9: return "Urban"
        case 1, 10: return "Rain"
        case 2, 11: return "Tour"
        case 3, 12: return "Rally"
        case 4: return "Supermoto"
        default: return "Mode \(rideModeCode)"
        }
    }

    var turnSignalName: String? {
        guard let turnSignalCode else { return nil }
        switch turnSignalCode {
        case 0: return "Off"
        case 1: return "Right"
        case 2: return "Left"
        case 3: return "Hazard"
        default: return "Unknown"
        }
    }
}

@MainActor
final class BikeBLEManager: NSObject, ObservableObject {
    nonisolated static var serviceUUID: CBUUID { CBUUID(string: "5456534d-5647-5341-5342-454e544f5251") }
    nonisolated static var notifyUUID: CBUUID { CBUUID(string: "00005354-0000-1000-8000-00805f9b34fb") }
    nonisolated static var writeUUID: CBUUID { CBUUID(string: "00005352-0000-1000-8000-00805f9b34fb") }

    @Published private(set) var bluetoothState: CBManagerState = .unknown
    @Published private(set) var devices: [BikeDevice] = []
    @Published private(set) var connectionMessage = "Bluetooth is starting…"
    @Published private(set) var telemetry = BikeTelemetry()
    @Published private(set) var frameCounts: [UInt8: Int] = [:]
    @Published private(set) var notificationCount = 0
    @Published private(set) var malformedFrameCount = 0
    @Published private(set) var discoveredServiceUUIDs: [String] = []
    @Published private(set) var lastFrameTime: Date?
    @Published private(set) var lastFrameSummary = "No telemetry received yet"
    @Published private(set) var lastWireFrameHex = "—"
    @Published var riderName: String {
        didSet { UserDefaults.standard.set(riderName, forKey: Self.riderNameDefaultsKey) }
    }
    @Published private(set) var preferredBikeID: UUID?
    @Published private(set) var preferredBikeName: String?
    @Published private(set) var documentTransferMessage = ""
    @Published private(set) var documentTransferProgress = 0.0
    @Published private(set) var documentTransferActive = false

    private static let riderNameDefaultsKey = "tvsBike.riderName"
    private static let bikeIDDefaultsKey = "tvsBike.preferredBikeID"
    private static let bikeNameDefaultsKey = "tvsBike.preferredBikeName"

    private var central: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?
    private var notifyCharacteristic: CBCharacteristic?
    private var writeCharacteristic: CBCharacteristic?
    private var wantsConnection = true
    private var sessionLogURL: URL?
    private var sessionLogHandle: FileHandle?
    private let logTimestampFormatter = ISO8601DateFormatter()
    private struct PendingWrite {
        let label: String
        let data: Data
        var continuation: CheckedContinuation<Void, Error>? = nil
    }
    private var writeQueue: [PendingWrite] = []
    private var writeInFlight: PendingWrite?
    private var didStartPhoneStatus = false
    private var lastMobileStatusQueuedAt: Date?
    private var latestDocumentAck = 0
    private var latestDocumentAckStatus: UInt8?
    private enum ScanPurpose: Equatable { case automatic, deviceSelection }
    private var scanPurpose: ScanPurpose = .automatic

    override init() {
        riderName = UserDefaults.standard.string(forKey: Self.riderNameDefaultsKey) ?? "Rider"
        if let storedID = UserDefaults.standard.string(forKey: Self.bikeIDDefaultsKey) {
            preferredBikeID = UUID(uuidString: storedID)
        } else {
            preferredBikeID = nil
        }
        preferredBikeName = UserDefaults.standard.string(forKey: Self.bikeNameDefaultsKey)
        super.init()
        central = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "com.example.TVSBike.central"]
        )
    }

    var isConnected: Bool {
        connectedPeripheral?.state == .connected
    }

    func scan() {
        guard bluetoothState == .poweredOn else {
            connectionMessage = "Turn on Bluetooth and allow access to nearby devices."
            logEvent("scan_not_started", details: ["reason": "Bluetooth is not powered on"])
            return
        }
        scanPurpose = .automatic
        startSessionLog()
        wantsConnection = true
        devices.removeAll()
        connectionMessage = "Searching for the TVS bike…"
        logEvent("scan_started", details: ["service_filter": Self.serviceUUID.uuidString, "auto_connect": true])
        if let preferredBikeID,
           let peripheral = central.retrievePeripherals(withIdentifiers: [preferredBikeID]).first {
            logEvent("configured_bike_retrieved", details: ["name": peripheral.name ?? preferredBikeName ?? "TVS bike"])
            connectionMessage = "Connecting to your configured bike…"
            connect(to: BikeDevice(
                id: peripheral.identifier,
                name: peripheral.name ?? preferredBikeName ?? "TVS bike",
                rssi: 0,
                peripheral: peripheral
            ))
            return
        }
        let connected = central.retrieveConnectedPeripherals(withServices: [Self.serviceUUID])
        if let peripheral = connected.first(where: { preferredBikeID == nil || $0.identifier == preferredBikeID }) {
            logEvent("connected_peripheral_retrieved", details: ["name": peripheral.name ?? "TVS bike", "state": peripheral.state.rawValue])
            connectionMessage = "Found an existing TVS BLE connection. Opening telemetry…"
            connect(to: BikeDevice(
                id: peripheral.identifier,
                name: peripheral.name ?? "TVS bike",
                rssi: 0,
                peripheral: peripheral
            ))
            return
        }
        central.scanForPeripherals(withServices: [Self.serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func scanForDeviceSelection() {
        guard bluetoothState == .poweredOn else {
            connectionMessage = "Turn on Bluetooth and allow access to nearby devices."
            return
        }
        scanPurpose = .deviceSelection
        devices.removeAll()
        connectionMessage = "Scanning for bikes to configure…"
        logEvent("bike_selection_scan_started")
        central.stopScan()
        central.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    func selectBike(_ device: BikeDevice) {
        preferredBikeID = device.id
        preferredBikeName = device.name
        UserDefaults.standard.set(device.id.uuidString, forKey: Self.bikeIDDefaultsKey)
        UserDefaults.standard.set(device.name, forKey: Self.bikeNameDefaultsKey)
        logEvent("preferred_bike_saved", details: ["name": device.name])
        scanPurpose = .automatic
        connect(to: device)
    }

    func forgetPreferredBike() {
        preferredBikeID = nil
        preferredBikeName = nil
        UserDefaults.standard.removeObject(forKey: Self.bikeIDDefaultsKey)
        UserDefaults.standard.removeObject(forKey: Self.bikeNameDefaultsKey)
        logEvent("preferred_bike_cleared")
        connectionMessage = "Bike preference cleared. Searching for a TVS bike…"
        if bluetoothState == .poweredOn, wantsConnection, connectedPeripheral == nil { scan() }
    }

    func setRiderName(_ value: String) {
        riderName = String(value.prefix(17))
    }

    func connect(to device: BikeDevice) {
        central.stopScan()
        scanPurpose = .automatic
        wantsConnection = true
        if sessionLogURL == nil { startSessionLog() }
        logEvent("connect_requested", details: ["name": device.name, "rssi_dbm": device.rssi, "peripheral_state": device.peripheral.state.rawValue])
        connectionMessage = "Connecting to \(device.name)…"
        telemetry = BikeTelemetry()
        frameCounts.removeAll()
        notificationCount = 0
        malformedFrameCount = 0
        discoveredServiceUUIDs.removeAll()
        lastFrameTime = nil
        lastFrameSummary = "Waiting for the first bike notification…"
        lastWireFrameHex = "—"
        didStartPhoneStatus = false
        lastMobileStatusQueuedAt = nil
        writeQueue.removeAll()
        writeInFlight = nil
        if let previous = connectedPeripheral,
           previous.identifier != device.peripheral.identifier,
           previous.state != .disconnected {
            central.cancelPeripheralConnection(previous)
        }
        connectedPeripheral = device.peripheral
        device.peripheral.delegate = self
        if device.peripheral.state == .connected {
            connectionMessage = "Already connected. Discovering TVS bike service…"
            logEvent("gatt_link_already_connected", details: ["name": device.name])
            device.peripheral.discoverServices(nil)
        } else {
            central.connect(device.peripheral)
        }
    }

    func disconnect() {
        guard let connectedPeripheral else { return }
        wantsConnection = false
        connectionMessage = "Disconnecting…"
        logEvent("disconnect_requested", details: ["reason": "user action"])
        central.cancelPeripheralConnection(connectedPeripheral)
    }

    private func beginTVSPhoneSession() {
        guard !didStartPhoneStatus else { return }
        didStartPhoneStatus = true
        logEvent("phone_status_cycle_started", details: ["rider_name_frame": "5B 52", "status_frame": "5B 4A", "interval_seconds": 2])
        enqueueWrite(label: "rider_name_0x52", data: makeTextFrame(id: 0x52, text: riderName))
        let welcomeName = riderName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !welcomeName.isEmpty {
            enqueueWrite(label: "personalized_welcome_0x4C", data: makeCustomTextFrame(text: welcomeName, marker: 0x01))
            logEvent("welcome_message_queued", details: ["frame_id": "4C", "trigger": "bike_connected", "rider_name_length": welcomeName.count])
        } else {
            logEvent("welcome_message_skipped", details: ["reason": "rider name is empty"])
        }
        enqueueWrite(label: "mobile_status_0x4A_initial", data: makeMobileStatusFrame())
        lastMobileStatusQueuedAt = Date()
    }

    private func makeCustomTextFrame(text: String, marker: UInt8) -> Data {
        let bytes = text.unicodeScalars.prefix(15).map { scalar -> UInt8 in
            scalar.isASCII ? UInt8(scalar.value) : UInt8(ascii: "X")
        }
        return makeAppFrame(id: 0x4C, body: [0x00, marker] + bytes)
    }

    private func makeTextFrame(id: UInt8, text: String) -> Data {
        let bytes = text.unicodeScalars.prefix(17).map { scalar -> UInt8 in
            scalar.isASCII ? UInt8(scalar.value) : UInt8(ascii: "X")
        }
        return makeAppFrame(id: id, body: bytes)
    }

    private func makeMobileStatusFrame(now: Date = Date()) -> Data {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let batteryPercent: Int
        if UIDevice.current.batteryLevel >= 0 {
            batteryPercent = Int((UIDevice.current.batteryLevel * 100).rounded())
        } else {
            batteryPercent = 80
        }
        let batteryBucket: UInt8
        switch batteryPercent {
        case ..<10: batteryBucket = 0
        case ..<20: batteryBucket = 1
        case ..<40: batteryBucket = 2
        case ..<60: batteryBucket = 3
        case ..<80: batteryBucket = 4
        default: batteryBucket = 5
        }

        let parts = Calendar.current.dateComponents([.hour, .minute, .second, .day, .month, .year], from: now)
        let hour24 = parts.hour ?? 0
        let mockSignalLevel: UInt8 = 3
        var body = [UInt8](repeating: 0, count: 17)
        body[0] = (mockSignalLevel << 4) | batteryBucket
        body[1] = 0x78
        body[4] = UInt8(hour24 % 12 == 0 ? 12 : hour24 % 12)
        body[5] = UInt8(parts.minute ?? 0)
        body[6] = UInt8(parts.second ?? 0)
        body[7] = hour24 >= 12 ? 1 : 0
        body[9] = 0 // Android network type has no direct iOS equivalent here.
        body[10] = UInt8(parts.day ?? 1)
        body[11] = UInt8(parts.month ?? 1)
        body[12] = UInt8((parts.year ?? 2000) % 100)

        logEvent("mobile_status_frame_built", details: [
            "frame_id": "4A",
            "battery_percent": batteryPercent,
            "battery_bucket": batteryBucket,
            "signal_level": mockSignalLevel,
            "signal_is_mock": true,
            "network_type": 0,
            "network_type_is_unknown": true
        ])
        return makeAppFrame(id: 0x4A, body: body)
    }

    private func makeAppFrame(id: UInt8, body: [UInt8]) -> Data {
        var padded = Array(body.prefix(17))
        if padded.count < 17 { padded.append(contentsOf: repeatElement(0, count: 17 - padded.count)) }
        var frame: [UInt8] = [0x5B, id]
        frame.append(contentsOf: padded.map { $0 ^ 0xEA })
        frame.append(0xFF)
        return Data(frame)
    }

    private func enqueueWrite(label: String, data: Data) {
        writeQueue.append(PendingWrite(label: label, data: data))
        logEvent("write_queued", details: ["label": label, "length": data.count, "wire_hex": data.map { String(format: "%02X", $0) }.joined(separator: " ")])
        writeNextIfPossible()
    }

    private func writeNextIfPossible() {
        guard writeInFlight == nil, let pending = writeQueue.first else { return }
        guard let connectedPeripheral, connectedPeripheral.state == .connected,
              let writeCharacteristic else {
            logEvent("write_not_sent", details: ["label": pending.label, "reason": "GATT write characteristic or connection unavailable"])
            return
        }
        writeQueue.removeFirst()
        writeInFlight = pending
        logEvent("write_tx", details: ["label": pending.label, "characteristic": writeCharacteristic.uuid.uuidString])
        connectedPeripheral.writeValue(pending.data, for: writeCharacteristic, type: .withResponse)
    }

    private func startSessionLog() {
        try? sessionLogHandle?.close()
        sessionLogHandle = nil
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyyMMdd_HHmmss_SSS"
        let filename = "ble_session_\(formatter.string(from: Date())).jsonl"
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TVSBikeLogs", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let url = root.appendingPathComponent(filename)
            FileManager.default.createFile(atPath: url.path, contents: nil)
            sessionLogURL = url
            sessionLogHandle = try FileHandle(forWritingTo: url)
            logEvent("session_started", details: ["app": "TVSBike iOS", "read_only": false, "phone_to_bike_writes_enabled": true])
        } catch {
            sessionLogURL = nil
            print("[TVSBike BLE] Could not create session log: \(error.localizedDescription)")
        }
    }

    private func logEvent(_ event: String, details: [String: Any] = [:]) {
        var record: [String: Any] = ["timestamp": logTimestampFormatter.string(from: Date()), "event": event]
        details.forEach { record[$0.key] = $0.value }
        guard let data = try? JSONSerialization.data(withJSONObject: record, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8) else { return }
        // Also mirror every event to Xcode's debug console for live debugging.
        print("[TVSBike BLE] \(json)")
        guard let line = (json + "\n").data(using: .utf8) else { return }
        try? sessionLogHandle?.write(contentsOf: line)
    }

    private func logNotification(_ raw: [UInt8]) {
        let sensitiveIdentifierFrame = (raw.count >= 2 && raw[0] == 0x5A && raw[1] == 0x7D)
            || (raw.count >= 2 && raw[0] == 0x5B && raw[1] == 0x42)
        var fields: [String: Any] = ["length": raw.count]
        if raw.count >= 2 {
            fields["header"] = String(format: "%02X", raw[0])
            fields["frame_id"] = String(format: "%02X", raw[1])
        }
        if sensitiveIdentifierFrame {
            fields["wire_hex"] = "REDACTED_IDENTIFIER_FRAME"
        } else {
            fields["wire_hex"] = raw.map { String(format: "%02X", $0) }.joined(separator: " ")
            let isDocumentAck = raw.count >= 9 && raw[0] == 0x5A && raw[1] == 0x54
            if (raw.count == 20 || isDocumentAck), !(raw[0] == 0x5A && raw[1] == 0x6B) {
                var decoded = raw
                for index in 2..<(raw.count - 1) { decoded[index] ^= 0xEA }
                fields["decoded_hex"] = decoded.map { String(format: "%02X", $0) }.joined(separator: " ")
            }
        }
        logEvent("notification_rx", details: fields)
    }

    private func handleNotification(_ data: Data) {
        let raw = [UInt8](data)
        logNotification(raw)
        notificationCount += 1
        lastFrameTime = Date()
        lastWireFrameHex = raw.map { String(format: "%02X", $0) }.joined(separator: " ")
        let now = Date()
        if didStartPhoneStatus, !documentTransferActive,
           let lastQueuedAt = lastMobileStatusQueuedAt,
           now.timeIntervalSince(lastQueuedAt) >= 2,
           isConnected {
            lastMobileStatusQueuedAt = now
            enqueueWrite(label: "mobile_status_0x4A_periodic", data: makeMobileStatusFrame(now: now))
        }
        // Apache document progress notifications are 23 bytes: the standard
        // 0x54 fields are followed by the bike's "Document N" label.
        if raw.count >= 9, raw[0] == 0x5A, raw[1] == 0x54, raw.last == 0xFF {
            var frame = raw
            for index in 2..<(frame.count - 1) { frame[index] ^= 0xEA }
            latestDocumentAck = (Int(frame[5]) << 8) | Int(frame[6])
            latestDocumentAckStatus = frame[7]
            frameCounts[0x54, default: 0] += 1
            lastFrameSummary = "Document transfer: frame \(latestDocumentAck), status \(frame[7])"
            logEvent("document_ack_received", details: [
                "length": raw.count,
                "frame": latestDocumentAck,
                "status": Int(frame[7]),
                "label": frame.count > 9
                    ? String(bytes: frame[9..<min(19, frame.count - 1)], encoding: .ascii) ?? ""
                    : ""
            ])
            return
        }

        guard raw.count == 20, raw[19] == 0xFF else {
            malformedFrameCount += 1
            lastFrameSummary = "Unexpected frame format: \(raw.count) bytes, trailer \(raw.last.map { String(format: "%02X", $0) } ?? "none")"
            return
        }

        // The captured Apache 0x6B music-command notification is not XOR-obfuscated.
        if raw[0] == 0x5A, raw[1] == 0x6B {
            frameCounts[0x6B, default: 0] += 1
            lastFrameSummary = "Bike music command: \(raw[2])"
            return
        }

        var frame = raw
        for index in 2..<19 {
            frame[index] ^= 0xEA
        }
        guard frame[0] == 0x5A else {
            malformedFrameCount += 1
            lastFrameSummary = String(format: "Unknown frame header 0x%02X (ID 0x%02X)", frame[0], frame[1])
            return
        }
        let frameID = frame[1]
        frameCounts[frameID, default: 0] += 1
        lastFrameSummary = String(format: "Received frame 0x%02X", frameID)

        switch frameID {
        case 0x10:
            telemetry.speedKph = Int(frame[2])
            telemetry.odometerKm = Double((Int(frame[3]) << 16) | (Int(frame[4]) << 8) | Int(frame[5])) / 10
            telemetry.fuelPercent = Int(frame[6])
            telemetry.averageSpeedKph = Int(frame[7])
            telemetry.recordedTopSpeedKph = Int(frame[9])
            telemetry.throttlePercent = Int(frame[10])
            telemetry.zeroTo60Seconds = Double(frame[12]) / 10
            telemetry.engineRPM = (Int(frame[16]) << 8) | Int(frame[17])
            telemetry.mileageRaw = Int(frame[8])
            telemetry.switchStatusRaw = Int(frame[11])
            telemetry.locationTagRaw = Int(frame[11])
            telemetry.averageMileageRaw = Int(frame[13])
            telemetry.tripFMeterRaw = (Int(frame[14]) << 8) | Int(frame[15])
        case 0x11:
            telemetry.gear = Int(frame[5])
            telemetry.turnSignalCode = Int(frame[12])
            // ApacheClusterDataReceiver reads DATA_ID_5A_11[17], then masks
            // its low nibble to obtain the ride mode code.
            telemetry.rideModeCode = Int(frame[17] & 0x0F)
            telemetry.vehicleDirectionRaw = Int(frame[2])
            telemetry.vehicleState1Raw = Int(frame[3])
            telemetry.sideStandStatus = (frame[3] & 0x04) != 0
            telemetry.sideStandSensorStatus = (frame[2] & 0x20) != 0
            telemetry.serviceReminderRaw = Int(frame[4])
            telemetry.vehicleState2Raw = Int(frame[6])
            telemetry.batteryVoltageRaw = Int(frame[6])
            telemetry.softwareVersionRaw = Int(frame[7])
            telemetry.milBlinkCodeRaw = Int(frame[8])
            telemetry.vehicleModelRaw = Int(frame[9])
            telemetry.vehicleDiagnosticsRaw = Int(frame[10])
            telemetry.reserve3Raw = Int(frame[11])
            telemetry.reserve14Raw = Int(frame[13])
            telemetry.engineTemperatureRaw = Int(frame[13])
            telemetry.tellTaleStatusRaw = Int(frame[13])
            telemetry.screenMatrixRaw = Int(frame[14])
            telemetry.vehicleState3Raw = Int(frame[15])
            telemetry.absMilBlinkCodeRaw = Int(frame[16])
            telemetry.vehicleModeRaw = Int(frame[17])
        case 0x12:
            telemetry.leanAngleRaw = Int(frame[2])
            telemetry.cruisingRangeRaw = (Int(frame[3]) << 8) | Int(frame[4])
            telemetry.wheelAngleOffsetRaw = Int(frame[5])
            telemetry.accelerationRaw = Int(frame[6])
            telemetry.torqueRaw = Int(frame[7])
            telemetry.tripDistanceRaw = (Int(frame[8]) << 8) | Int(frame[9])
            telemetry.tripTimeHoursRaw = Int(frame[10])
            telemetry.tripTimeMinutesRaw = Int(frame[11])
            telemetry.tripMileageRaw = Int(frame[12])
            telemetry.tripFuelRaw = (Int(frame[13]) << 8) | Int(frame[14])
            telemetry.overspeedThresholdRaw = Int(frame[15])
            telemetry.overspeedSettingRaw = Int(frame[16])
        case 0x5F:
            telemetry.unknown5FPayloadHex = frame[2..<19]
                .map { String(format: "%02X", $0) }
                .joined(separator: " ")
        default:
            break
        }
    }

    func sendDocument(image: UIImage, name: String, pictureNumber: Int) async throws {
        guard isConnected, let peripheral = connectedPeripheral, let characteristic = writeCharacteristic else {
            throw DocumentTransferError.notConnected
        }
        guard (1...5).contains(pictureNumber) else { throw DocumentTransferError.invalidPictureNumber }
        guard !documentTransferActive else { throw DocumentTransferError.alreadySending }
        let payload = try Self.makeDocumentPayload(from: image)
        let chunkSize = 128
        let totalFrames = (payload.count + chunkSize - 1) / chunkSize
        guard totalFrames > 0, totalFrames <= 6000 else { throw DocumentTransferError.imageTooLarge }
        let safeName = String(name.unicodeScalars.prefix(10).map { $0.isASCII ? Character($0) : " " })
        documentTransferActive = true
        documentTransferProgress = 0
        documentTransferMessage = "Starting transfer…"
        latestDocumentAck = 0
        latestDocumentAckStatus = nil
        logEvent("document_transfer_started", details: ["picture_number": pictureNumber, "frames": totalFrames, "payload_bytes": payload.count])
        defer { documentTransferActive = false }
        do {
            try await sendDocumentPacket(Self.documentInit(picture: pictureNumber, total: totalFrames, current: 1, status: 1, name: safeName), label: "document_init", peripheral: peripheral, characteristic: characteristic)
            try await Task.sleep(for: .milliseconds(150))
            for sequence in 1...totalFrames {
                let start = (sequence - 1) * chunkSize
                let end = min(start + chunkSize, payload.count)
                let chunk = payload.subdata(in: start..<end)
                try await sendDocumentPacket(Self.documentChunk(sequence: sequence, chunk: chunk), label: "document_chunk_\(sequence)", peripheral: peripheral, characteristic: characteristic)
                documentTransferProgress = Double(sequence) / Double(totalFrames)
                if sequence % 20 == 0 {
                    documentTransferMessage = "Sending… \(sequence) / \(totalFrames)"
                    logEvent("document_transfer_progress", details: ["sent_frames": sequence, "total_frames": totalFrames, "acknowledged_frames": latestDocumentAck])
                    if sequence < totalFrames { try await waitForDocumentAck(atLeast: sequence) }
                }
            }
            try await sendDocumentPacket(Self.documentInit(picture: pictureNumber, total: totalFrames, current: totalFrames, status: 3, name: safeName), label: "document_complete", peripheral: peripheral, characteristic: characteristic)
            if (try? await waitForDocumentCompletion(atLeast: totalFrames)) != nil {
                documentTransferMessage = "Bike confirmed the document."
                logEvent("document_transfer_completed", details: ["acknowledged_frames": latestDocumentAck])
            } else {
                documentTransferMessage = "Sent all image data; bike confirmation was not received."
                logEvent("document_transfer_unconfirmed", details: ["sent_frames": totalFrames])
            }
        } catch {
            documentTransferMessage = "Transfer failed: \(error.localizedDescription)"
            logEvent("document_transfer_failed", details: ["sent_frames": Int(documentTransferProgress * Double(totalFrames)), "error": error.localizedDescription])
            throw error
        }
    }

    private func sendDocumentPacket(_ data: Data, label: String, peripheral: CBPeripheral, characteristic: CBCharacteristic) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            writeQueue.append(PendingWrite(label: label, data: data, continuation: continuation))
            writeNextIfPossible()
        }
        guard isConnected else { throw DocumentTransferError.disconnected }
    }

    private func waitForDocumentAck(atLeast frame: Int, timeout: Int = 8) async throws {
        let deadline = Date().addingTimeInterval(TimeInterval(timeout))
        while Date() < deadline {
            if latestDocumentAck >= frame {
                guard latestDocumentAckStatus == 1 else { throw DocumentTransferError.bikeRejected(latestDocumentAckStatus ?? 0) }
                return
            }
            guard isConnected else { throw DocumentTransferError.disconnected }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw DocumentTransferError.acknowledgementTimeout(frame)
    }

    private func waitForDocumentCompletion(atLeast frame: Int, timeout: Int = 8) async throws {
        let deadline = Date().addingTimeInterval(TimeInterval(timeout))
        while Date() < deadline {
            if latestDocumentAck >= frame, latestDocumentAckStatus == 3 { return }
            guard isConnected else { throw DocumentTransferError.disconnected }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw DocumentTransferError.acknowledgementTimeout(frame)
    }

    private static func documentInit(picture: Int, total: Int, current: Int, status: UInt8, name: String) -> Data {
        var body: [UInt8] = [UInt8(picture), UInt8((total >> 8) & 0xff), UInt8(total & 0xff), UInt8((current >> 8) & 0xff), UInt8(current & 0xff), status, picture == 4 ? 5 : (picture == 5 ? 10 : 0)]
        body.append(contentsOf: name.utf8.prefix(10))
        body += repeatElement(0, count: 20 - body.count)
        return Data([0x5a, 0x53] + body.map { $0 ^ 0xea } + [0xff])
    }

    private static func documentChunk(sequence: Int, chunk: Data) -> Data {
        var data = Array(chunk.prefix(128))
        data += repeatElement(0, count: 128 - data.count)
        let sequenceBytes = [UInt8((sequence >> 8) & 0xff), UInt8(sequence & 0xff)]
        let checksumInput = [0x5a, 0x55] + sequenceBytes + data
        let checksum = UInt8((0xff - (checksumInput.reduce(0, { ($0 + Int($1)) & 0xff })) ) & 0xff)
        return Data([0x5a, 0x55] + (sequenceBytes + data + [checksum]).map { $0 ^ 0xea } + [0xff])
    }

    private static func makeDocumentPayload(from image: UIImage) throws -> Data {
        guard let source = image.cgImage else { throw DocumentTransferError.invalidImage }
        // APK constants are 0x320 × 0x1E0 = 800 × 480 px. Its transfer
        // bitmap is RGB_565 (2 bytes/pixel), which matches the 768,000-byte
        // payload and 6,000 chunks in the HCI capture.
        let width = 800, height = 480
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue), let pixels = context.data else { throw DocumentTransferError.invalidImage }
        context.interpolationQuality = .high
        // Android's RGB_565 target starts black and its ImageView FIT_CENTER
        // preserves the cropped image's aspect ratio inside the 800×480 canvas.
        context.setFillColor(gray: 0, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1) // Android bitmap rows start at the top.
        let fit = min(CGFloat(width) / CGFloat(source.width), CGFloat(height) / CGFloat(source.height))
        let drawWidth = CGFloat(source.width) * fit
        let drawHeight = CGFloat(source.height) * fit
        let drawRect = CGRect(x: (CGFloat(width) - drawWidth) / 2, y: (CGFloat(height) - drawHeight) / 2, width: drawWidth, height: drawHeight)
        context.draw(source, in: drawRect)
        let bytes = pixels.bindMemory(to: UInt8.self, capacity: width * height * 4)
        var packed = Data(capacity: width * height * 2)
        // The repeated 2×2 bike output showed that this receiver consumes a
        // conventional row-major raster. Emit complete scanlines; the bike
        // needs the vertical row reversal, but not a horizontal mirror.
        for y in 0..<height {
            let sourceY = height - 1 - y
            for x in 0..<width {
                let sourceX = x
                let pixel = (sourceY * width + sourceX) * 4
                let blue = UInt16(bytes[pixel])
                let green = UInt16(bytes[pixel + 1])
                let red = UInt16(bytes[pixel + 2])
                let rgb565 = ((red >> 3) << 11) | ((green >> 2) << 5) | (blue >> 3)
                packed.append(UInt8(rgb565 & 0xff))
                packed.append(UInt8(rgb565 >> 8))
            }
        }
        return packed
    }

    enum DocumentTransferError: LocalizedError {
        case notConnected, disconnected, invalidImage, invalidPictureNumber, alreadySending, imageTooLarge
        case acknowledgementTimeout(Int), bikeRejected(UInt8)
        var errorDescription: String? {
            switch self {
            case .notConnected: return "Connect to the bike before sending a document."
            case .disconnected: return "The bike disconnected during the transfer."
            case .invalidImage: return "This image could not be prepared for the bike."
            case .invalidPictureNumber: return "Choose a valid document slot."
            case .alreadySending: return "A document is already being sent."
            case .imageTooLarge: return "This image is larger than the bike transfer limit. Crop it more tightly and try again."
            case .acknowledgementTimeout(let frame): return "The bike did not acknowledge frame \(frame)."
            case .bikeRejected(let status): return "The bike reported transfer status \(status)."
            }
        }
    }
}

extension BikeBLEManager: CBCentralManagerDelegate {
    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        Task { @MainActor in
            self.logEvent("bluetooth_state_restored", details: [
                "peripheral_count": peripherals.count,
                "scanning_restored": dict[CBCentralManagerRestoredStateScanServicesKey] != nil
            ])
            guard let peripheral = peripherals.first else { return }
            peripheral.delegate = self
            self.connectedPeripheral = peripheral
            self.connectionMessage = "Restoring the TVS bike connection…"
            self.logEvent("restored_peripheral", details: ["name": peripheral.name ?? "TVS bike", "state": peripheral.state.rawValue])
            switch peripheral.state {
            case .connected:
                peripheral.discoverServices(nil)
            case .disconnected:
                self.central.connect(peripheral)
            case .connecting, .disconnecting:
                break // Core Bluetooth is already completing the restored operation.
            @unknown default:
                self.central.connect(peripheral)
            }
        }
    }

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            self.bluetoothState = central.state
            switch central.state {
            case .poweredOn:
                self.logEvent("bluetooth_state", details: ["state": "powered_on"])
                self.connectionMessage = "Bluetooth ready. Searching for your TVS bike…"
                if self.wantsConnection { self.scan() }
            case .poweredOff:
                self.logEvent("bluetooth_state", details: ["state": "powered_off"])
                self.connectionMessage = "Bluetooth is turned off."
            case .unauthorized:
                self.logEvent("bluetooth_state", details: ["state": "unauthorized"])
                self.connectionMessage = "Bluetooth permission is not allowed for this app."
            case .unsupported:
                self.logEvent("bluetooth_state", details: ["state": "unsupported"])
                self.connectionMessage = "This device does not support Bluetooth LE."
            case .resetting:
                self.logEvent("bluetooth_state", details: ["state": "resetting"])
                self.connectionMessage = "Bluetooth is resetting…"
            case .unknown:
                self.logEvent("bluetooth_state", details: ["state": "unknown"])
                self.connectionMessage = "Bluetooth state is unknown."
            @unknown default:
                self.logEvent("bluetooth_state", details: ["state": "other"])
                self.connectionMessage = "Bluetooth is unavailable."
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let name = peripheral.name
            ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String
            ?? "Unnamed BLE device"
        let advertisedServices = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let isTVSBike = Self.looksLikeTVSBike(name) || advertisedServices.contains(Self.serviceUUID)
        Task { @MainActor in
            let existingIndex = self.devices.firstIndex(where: { $0.id == peripheral.identifier })
            let device = BikeDevice(id: peripheral.identifier, name: name, rssi: RSSI.intValue, peripheral: peripheral)
            if let index = existingIndex {
                self.devices[index] = device
            } else {
                self.devices.append(device)
                self.logEvent("device_discovered", details: ["name": name, "rssi_dbm": RSSI.intValue, "tvsmatch": isTVSBike, "advertised_services": advertisedServices.map(\.uuidString)])
            }
            self.devices.sort { $0.rssi > $1.rssi }
            guard self.scanPurpose == .automatic,
                  isTVSBike,
                  self.wantsConnection,
                  self.connectedPeripheral == nil,
                  self.preferredBikeID == nil || peripheral.identifier == self.preferredBikeID else { return }
            self.connectionMessage = "Found \(name). Connecting automatically…"
            self.connect(to: device)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            guard self.connectedPeripheral?.identifier == peripheral.identifier else {
                self.central.cancelPeripheralConnection(peripheral)
                return
            }
            peripheral.delegate = self
            self.connectionMessage = "Connected. Discovering TVS bike service…"
            self.logEvent("gatt_connected", details: ["name": peripheral.name ?? "Unknown peripheral"])
            peripheral.discoverServices(nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            guard self.connectedPeripheral?.identifier == peripheral.identifier else { return }
            self.connectionMessage = "Could not connect: \(error?.localizedDescription ?? "unknown error")"
            self.logEvent("gatt_connect_failed", details: ["error": error?.localizedDescription ?? "unknown error"])
            self.connectedPeripheral = nil
            if self.wantsConnection, self.bluetoothState == .poweredOn {
                self.connectionMessage = "Connection attempt failed. Searching for the bike again…"
                self.central.scanForPeripherals(withServices: [Self.serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            guard self.connectedPeripheral?.identifier == peripheral.identifier else { return }
            self.connectionMessage = error.map { "Disconnected: \($0.localizedDescription)" } ?? "Disconnected from bike."
            self.logEvent("gatt_disconnected", details: ["error": error?.localizedDescription ?? "none", "auto_reconnect": self.wantsConnection])
            self.connectedPeripheral = nil
            self.notifyCharacteristic = nil
            self.writeCharacteristic = nil
            self.writeInFlight?.continuation?.resume(throwing: DocumentTransferError.disconnected)
            self.writeQueue.forEach { $0.continuation?.resume(throwing: DocumentTransferError.disconnected) }
            self.writeQueue.removeAll()
            self.writeInFlight = nil
            if self.wantsConnection, self.bluetoothState == .poweredOn {
                self.connectionMessage = "Bike disconnected. Searching to reconnect…"
                self.central.scanForPeripherals(withServices: [Self.serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
            }
        }
    }
}

private extension BikeBLEManager {
    nonisolated static func looksLikeTVSBike(_ name: String) -> Bool {
        let normalized = name.lowercased().filter { $0.isLetter || $0.isNumber }
        return normalized.contains("tvsrtr310") || normalized.contains("rtr310") || normalized.contains("apache")
    }
}

extension BikeBLEManager: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            Task { @MainActor in
                self.connectionMessage = "Service discovery failed: \(error.localizedDescription)"
                self.logEvent("service_discovery_failed", details: ["error": error.localizedDescription])
            }
            return
        }
        let services = peripheral.services ?? []
        let serviceUUIDs = services.map { $0.uuid.uuidString }
        Task { @MainActor in
            self.discoveredServiceUUIDs = serviceUUIDs
            self.logEvent("services_discovered", details: ["services": serviceUUIDs])
        }
        guard let service = services.first(where: { $0.uuid == Self.serviceUUID }) else {
            let found = serviceUUIDs.isEmpty ? "none" : serviceUUIDs.joined(separator: ", ")
            Task { @MainActor in self.connectionMessage = "TVS service not found. Discovered services: \(found)" }
            return
        }
        peripheral.discoverCharacteristics([Self.notifyUUID, Self.writeUUID], for: service)
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error {
            Task { @MainActor in
                self.connectionMessage = "Characteristic discovery failed: \(error.localizedDescription)"
                self.logEvent("characteristic_discovery_failed", details: ["error": error.localizedDescription])
            }
            return
        }
        let characteristics = (service.characteristics ?? []).map { characteristic in
            ["uuid": characteristic.uuid.uuidString, "properties_raw": characteristic.properties.rawValue] as [String: Any]
        }
        Task { @MainActor in self.logEvent("characteristics_discovered", details: ["service": service.uuid.uuidString, "characteristics": characteristics]) }
        if let write = service.characteristics?.first(where: { $0.uuid == Self.writeUUID }) {
            Task { @MainActor in self.writeCharacteristic = write }
        } else {
            Task { @MainActor in self.logEvent("write_characteristic_missing", details: ["expected_uuid": Self.writeUUID.uuidString]) }
        }
        guard let notify = service.characteristics?.first(where: { $0.uuid == Self.notifyUUID }) else {
            Task { @MainActor in self.connectionMessage = "The bike telemetry characteristic was not found." }
            return
        }
        Task { @MainActor in
            self.notifyCharacteristic = notify
            self.connectionMessage = "Enabling telemetry notifications…"
            self.logEvent("notification_subscription_requested", details: ["characteristic": notify.uuid.uuidString])
        }
        peripheral.setNotifyValue(true, for: notify)
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            if let error {
                self.connectionMessage = "Could not subscribe to telemetry: \(error.localizedDescription)"
                self.logEvent("notification_subscription_failed", details: ["error": error.localizedDescription])
            } else if characteristic.isNotifying {
                self.connectionMessage = "Telemetry notifications active. Starting TVS phone-status exchange…"
                self.logEvent("notification_subscription_active", details: ["characteristic": characteristic.uuid.uuidString])
                self.beginTVSPhoneSession()
            } else {
                self.connectionMessage = "Telemetry notifications stopped."
                self.logEvent("notification_subscription_stopped", details: ["characteristic": characteristic.uuid.uuidString])
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            Task { @MainActor in
                self.connectionMessage = "Telemetry read/notify error: \(error.localizedDescription)"
                self.logEvent("notification_value_error", details: ["error": error.localizedDescription, "characteristic": characteristic.uuid.uuidString])
            }
            return
        }
        guard characteristic.uuid == Self.notifyUUID, let value = characteristic.value else { return }
        let data = Data(value)
        Task { @MainActor in self.handleNotification(data) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            let pending = self.writeInFlight
            self.writeInFlight = nil
            if let error {
                pending?.continuation?.resume(throwing: error)
            } else {
                pending?.continuation?.resume(returning: ())
            }
            self.logEvent(error == nil ? "write_response_success" : "write_response_error", details: [
                "label": pending?.label ?? "unknown",
                "characteristic": characteristic.uuid.uuidString,
                "error": error?.localizedDescription ?? "none",
                "att_success_means_bike_received_write": error == nil
            ])
            self.writeNextIfPossible()
        }
    }
}
