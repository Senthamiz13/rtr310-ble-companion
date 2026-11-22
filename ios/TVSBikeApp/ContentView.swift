import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var bike: BikeBLEManager
    @EnvironmentObject private var digiDocs: DigiDocsStore
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    brandCard
                    connectionCard
                    telemetryGrid
                    additionalTelemetry
                    digiDocsCard
                }
                .padding()
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Settings", systemImage: "gearshape") { showingSettings = true }
                }
            }
            .sheet(isPresented: $showingSettings) {
                NavigationStack {
                    BikeSettingsView()
                        .environmentObject(bike)
                }
            }
        }
    }

    private var brandCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("RTR 310 BLE Companion")
                .font(.title2.weight(.bold))
                .foregroundStyle(.white)
            Text("Independent Bluetooth companion")
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.72))
        }
        .frame(maxWidth: .infinity)
        .frame(height: 112, alignment: .leading)
        .padding(.horizontal, 22)
        .background(
            LinearGradient(
                colors: [Color(red: 0.12, green: 0.16, blue: 0.21), Color(red: 0.035, green: 0.045, blue: 0.06)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 22)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 22)
                .stroke(.white.opacity(0.08), lineWidth: 1)
        }
    }

    private var connectionCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: bike.isConnected ? "dot.radiowaves.left.and.right" : "dot.radiowaves.left.and.right")
                    .foregroundStyle(bike.isConnected ? .green : .secondary)
                Text(bike.isConnected ? "Connected" : "Not connected")
                    .font(.headline)
                Spacer()
                if bike.isConnected {
                    Button("Disconnect", role: .destructive) { bike.disconnect() }
                        .font(.subheadline)
                }
            }
            Text(bike.connectionMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if let lastFrameTime = bike.lastFrameTime {
                Text("Last frame: \(lastFrameTime.formatted(date: .omitted, time: .standard))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.background, in: RoundedRectangle(cornerRadius: 16))
    }

    private var telemetryGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            TelemetryTile(title: "Speed", value: formatted(bike.telemetry.speedKph), unit: "km/h", icon: "speedometer")
            TelemetryTile(title: "Engine", value: formatted(bike.telemetry.engineRPM), unit: "rpm", icon: "engine.combustion")
            TelemetryTile(title: "Odometer", value: formatted(bike.telemetry.odometerKm, decimals: 1), unit: "km", icon: "road.lanes")
            TelemetryTile(title: "Fuel", value: formatted(bike.telemetry.fuelPercent), unit: "%", icon: "fuelpump")
            TelemetryTile(title: "Gear", value: formatted(bike.telemetry.gear), unit: "", icon: "gearshape")
            TelemetryTile(title: "Riding mode", value: bike.telemetry.rideModeName ?? "—", unit: "", icon: "steeringwheel")
            TelemetryTile(title: "Indicators", value: bike.telemetry.turnSignalName ?? "—", unit: indicatorCode, icon: "arrow.left.arrow.right")
            TelemetryTile(title: "Throttle", value: formatted(bike.telemetry.throttlePercent), unit: "%", icon: "bolt.fill")
            TelemetryTile(title: "Average speed", value: formatted(bike.telemetry.averageSpeedKph), unit: "km/h", icon: "chart.line.uptrend.xyaxis")
            TelemetryTile(title: "0–60", value: formatted(bike.telemetry.zeroTo60Seconds, decimals: 1), unit: "s", icon: "timer")
            TelemetryTile(title: "Top speed", value: formatted(bike.telemetry.recordedTopSpeedKph), unit: "km/h", icon: "gauge.with.dots.needle.67percent")
        }
    }

    private var digiDocsCard: some View {
        NavigationLink {
            DigiDocsView(store: digiDocs, bike: bike)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "doc.text.image")
                    .font(.title2)
                    .foregroundStyle(.tint)
                    .frame(width: 42, height: 42)
                    .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 4) {
                    Text("Digi Docs").font(.headline).foregroundStyle(.primary)
                    Text("\(digiDocs.documents.count) saved on this iPhone")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.background, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
    }

    private var additionalTelemetry: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Additional bike data")
                .font(.title3.weight(.semibold))

            detailCard("Ride data · 0x10", items: [
                ("Mileage (raw)", raw(bike.telemetry.mileageRaw)),
                ("Switch status (raw)", hexByte(bike.telemetry.switchStatusRaw)),
                ("Location-tag field (same byte)", hexByte(bike.telemetry.locationTagRaw)),
                ("Average mileage (raw)", raw(bike.telemetry.averageMileageRaw)),
                ("Trip-F meter (raw)", raw(bike.telemetry.tripFMeterRaw))
            ])

            detailCard("Vehicle status · 0x11", items: [
                ("Side-stand status bit", bike.telemetry.sideStandStatus.map { $0 ? "Set" : "Clear" }),
                ("Side-stand sensor bit", bike.telemetry.sideStandSensorStatus.map { $0 ? "Set" : "Clear" }),
                ("Service reminder (raw)", hexByte(bike.telemetry.serviceReminderRaw)),
                ("Battery voltage field (raw)", hexByte(bike.telemetry.batteryVoltageRaw)),
                ("Software version field (raw)", hexByte(bike.telemetry.softwareVersionRaw)),
                ("MIL blink code (raw)", hexByte(bike.telemetry.milBlinkCodeRaw)),
                ("Vehicle model (raw)", hexByte(bike.telemetry.vehicleModelRaw)),
                ("Vehicle diagnostics (raw)", hexByte(bike.telemetry.vehicleDiagnosticsRaw)),
                ("Engine temperature field (raw)", hexByte(bike.telemetry.engineTemperatureRaw)),
                ("Tell-tale status (raw)", hexByte(bike.telemetry.tellTaleStatusRaw)),
                ("Screen matrix (raw)", hexByte(bike.telemetry.screenMatrixRaw)),
                ("ABS MIL blink code (raw)", hexByte(bike.telemetry.absMilBlinkCodeRaw)),
                ("Vehicle mode byte (raw)", hexByte(bike.telemetry.vehicleModeRaw)),
                ("Vehicle direction/state bytes", [bike.telemetry.vehicleDirectionRaw, bike.telemetry.vehicleState1Raw, bike.telemetry.vehicleState2Raw, bike.telemetry.vehicleState3Raw].compactMap { $0 }.map { String(format: "%02X", $0) }.joined(separator: " ").nilIfEmpty),
                ("Reserved bytes", [bike.telemetry.reserve3Raw, bike.telemetry.reserve14Raw].compactMap { $0 }.map { String(format: "%02X", $0) }.joined(separator: " ").nilIfEmpty)
            ], footnote: "Raw values are shown where byte format, units, or bit meaning still needs confirmation. Side-stand bit polarity is not verified; switch states are retained in the state bytes.")

            detailCard("Ride dynamics · 0x12", items: [
                ("Lean angle (raw)", raw(bike.telemetry.leanAngleRaw)),
                ("Cruising range (raw)", raw(bike.telemetry.cruisingRangeRaw)),
                ("Wheel-angle offset (raw)", raw(bike.telemetry.wheelAngleOffsetRaw)),
                ("Acceleration (raw)", raw(bike.telemetry.accelerationRaw)),
                ("Torque (raw)", raw(bike.telemetry.torqueRaw)),
                ("Trip distance (raw)", raw(bike.telemetry.tripDistanceRaw)),
                ("Trip time (raw)", tripTime),
                ("Trip mileage (raw)", raw(bike.telemetry.tripMileageRaw)),
                ("Trip fuel (raw)", raw(bike.telemetry.tripFuelRaw)),
                ("Overspeed threshold (raw)", raw(bike.telemetry.overspeedThresholdRaw)),
                ("Overspeed setting (raw)", raw(bike.telemetry.overspeedSettingRaw))
            ], footnote: "Values are from the APK's field map; units/scaling are not yet verified.")

            detailCard("Unmapped frame · 0x5F", items: [
                ("Decoded payload", bike.telemetry.unknown5FPayloadHex)
            ], footnote: "The frame is captured for analysis; its fields have not been identified.")
        }
    }

    private var tripTime: String? {
        guard let hours = bike.telemetry.tripTimeHoursRaw,
              let minutes = bike.telemetry.tripTimeMinutesRaw else { return nil }
        return "\(hours) h \(minutes) min"
    }

    private func detailCard(_ title: String, items: [(String, String?)], footnote: String? = nil) -> some View {
        let visibleItems = items.filter { $0.1 != nil }
        return Group {
            if !visibleItems.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(.headline)
                        .padding(.bottom, 8)
                    ForEach(Array(visibleItems.enumerated()), id: \.offset) { _, item in
                        LabeledContent(item.0, value: item.1 ?? "—")
                            .font(.subheadline)
                            .padding(.vertical, 5)
                    }
                    if let footnote {
                        Text(footnote)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.top, 7)
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.background, in: RoundedRectangle(cornerRadius: 16))
            }
        }
    }

    private func raw(_ value: Int?) -> String? { value.map(String.init) }
    private func hexByte(_ value: Int?) -> String? { value.map { String(format: "0x%02X", $0) } }

    private var indicatorCode: String {
        bike.telemetry.turnSignalCode.map { "code \($0)" } ?? ""
    }

    private func formatted(_ value: Int?) -> String {
        value.map { $0.formatted() } ?? "—"
    }

    private func formatted(_ value: Double?, decimals: Int) -> String {
        value.map { $0.formatted(.number.precision(.fractionLength(decimals))) } ?? "—"
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

private struct BikeSettingsView: View {
    @EnvironmentObject private var bike: BikeBLEManager
    @Environment(\.dismiss) private var dismiss
    @State private var riderNameDraft = ""

    var body: some View {
        Form {
            Section("Bike") {
                if let preferredBikeName = bike.preferredBikeName {
                    LabeledContent("Configured bike", value: preferredBikeName)
                } else {
                    Text("No bike selected. The app will connect to a TVS bike it discovers.")
                        .foregroundStyle(.secondary)
                }

                Button("Scan for bikes", systemImage: "antenna.radiowaves.left.and.right") {
                    bike.scanForDeviceSelection()
                }
                .disabled(bike.bluetoothState != .poweredOn)

                if bike.devices.isEmpty {
                    Text("Keep the bike nearby with ignition on while scanning.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(bike.devices) { device in
                        Button {
                            bike.selectBike(device)
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(device.name).foregroundStyle(.primary)
                                    Text("\(device.rssi) dBm · \(device.id.uuidString)")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if device.id == bike.preferredBikeID {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.green)
                                }
                            }
                        }
                    }
                }

                Text(bike.connectionMessage)
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if bike.preferredBikeID != nil {
                    Button("Forget configured bike", role: .destructive) {
                        bike.forgetPreferredBike()
                    }
                }
            }

            Section("Rider") {
                TextField("Rider name", text: $riderNameDraft)
                    .accessibilityIdentifier("riderNameField")
                    .submitLabel(.done)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()

                Text("Used for the bike’s rider-name frame when a connection starts and for the welcome button. Changes to the bike’s rider-name frame take effect on the next connection. Up to 17 characters; display text supports 15.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { riderNameDraft = bike.riderName }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    bike.setRiderName(riderNameDraft)
                    dismiss()
                }
            }
        }
    }
}

private struct TelemetryTile: View {
    let title: String
    let value: String
    let unit: String
    let icon: String

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Label(title, systemImage: icon)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value).font(.title2.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
                if !unit.isEmpty { Text(unit).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
        .padding(12)
        .background(.background, in: RoundedRectangle(cornerRadius: 14))
    }
}
