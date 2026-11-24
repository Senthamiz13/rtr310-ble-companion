import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var bike: BikeBLEManager
    @EnvironmentObject private var digiDocs: DigiDocsStore
    @State private var showingSettings = false
    @State private var actionNote = ""

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
            .background(RTRTheme.background.ignoresSafeArea())
            .tint(RTRTheme.accent)
            .toolbarBackground(RTRTheme.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
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
        .preferredColorScheme(.dark)
    }

    private var brandCard: some View {
        HStack(spacing: 10) {
            Image("ApacheRTR310")
                .resizable()
                .scaledToFit()
                .frame(width: 132, height: 90)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 6) {
                Text("RTR Link")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Independent Bluetooth companion")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.72))
                    .fixedSize(horizontal: false, vertical: true)
                Text("Fan-made · Open source")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(RTRTheme.status)
                Text("Does not replace TVS app · Use at your own risk")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.62))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 160, alignment: .leading)
        .padding(.horizontal, 14)
        .background(
            LinearGradient(
                colors: [RTRTheme.panel, RTRTheme.background],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 22)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 22)
                .stroke(RTRTheme.border, lineWidth: 1)
        }
    }

    private var connectionCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: bike.isConnected ? "dot.radiowaves.left.and.right" : "dot.radiowaves.left.and.right")
                    .foregroundStyle(bike.isConnected ? RTRTheme.status : Color.secondary)
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
        .background(RTRTheme.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay { RoundedRectangle(cornerRadius: 16).stroke(RTRTheme.border, lineWidth: 1) }
    }

    private var telemetryGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            TelemetryTile(title: "Speed", value: formatted(bike.telemetry.speedKph), unit: "km/h", icon: "speedometer")
            TelemetryTile(title: "Engine", value: formatted(bike.telemetry.engineRPM), unit: "rpm", icon: "engine.combustion")
            TelemetryTile(title: "Odometer", value: formatted(bike.telemetry.odometerKm, decimals: 1), unit: "km", icon: "road.lanes")
            TelemetryTile(title: "Fuel", value: formatted(bike.telemetry.fuelPercent), unit: "%", icon: "fuelpump")
            TelemetryTile(title: "Gear", value: formatted(bike.telemetry.gear), unit: "", icon: "gearshape")
            TelemetryTile(title: "Riding mode", value: bike.telemetry.rideModeName ?? "—", unit: "", icon: "steeringwheel")
            TelemetryTile(title: "Side stand", value: bike.telemetry.sideStandStatus.map { $0 ? "Down" : "Up" } ?? "—", unit: "", icon: "figure.stand")
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
                    .foregroundStyle(RTRTheme.accent)
                    .frame(width: 42, height: 42)
                    .background(RTRTheme.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
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
            .background(RTRTheme.panel, in: RoundedRectangle(cornerRadius: 16))
            .overlay { RoundedRectangle(cornerRadius: 16).stroke(RTRTheme.border, lineWidth: 1) }
        }
        .buttonStyle(.plain)
    }

    private var actionMarkerCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Mark an action", systemImage: "bookmark")
                .font(.headline)
            Text("Add a timestamped note to the BLE log just before or after you act on the bike.")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack(spacing: 10) {
                TextField("e.g. Pressed D-pad right", text: $actionNote)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.done)
                    .onSubmit { recordAction() }
                    .accessibilityIdentifier("bleActionMarkerField")
                Button("Log") { recordAction() }
                    .buttonStyle(.borderedProminent)
                    .disabled(actionNote.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RTRTheme.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay { RoundedRectangle(cornerRadius: 16).stroke(RTRTheme.border, lineWidth: 1) }
    }

    private func recordAction() {
        bike.markUserAction(actionNote)
        actionNote = ""
    }

    private var additionalTelemetry: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("CONFIRMED BIKE DATA")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                TelemetryTile(title: "Trip A", value: bike.telemetry.tripDistanceRaw.map { formatted(Double($0) / 10, decimals: 1) } ?? "—", unit: "km", icon: "point.topleft.down.to.point.bottomright.curvepath")
                TelemetryTile(title: "Trip time", value: tripTime ?? "—", unit: "", icon: "timer")
                TelemetryTile(title: "Overspeed limit", value: bike.telemetry.overspeedThresholdRaw.map { String($0) } ?? "—", unit: "km/h", icon: "speedometer")
            }
            rawDataAccordion
        }
    }

    private var rawDataAccordion: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 14) {
                Text("Raw, provisional, and diagnostic fields. A value shown here may have an unknown unit or interpretation.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                detailCard("0x10 · Additional ride fields", items: [
                    ("Mileage (raw)", raw(bike.telemetry.mileageRaw)),
                    ("Switch status / location tag (same byte)", raw(bike.telemetry.switchStatusRaw, hex: true)),
                    ("Average mileage (raw)", raw(bike.telemetry.averageMileageRaw)),
                    ("Trip-F meter (raw)", raw(bike.telemetry.tripFMeterRaw)),
                    ("Checksum", "Known frame checksum; not exposed as telemetry")
                ])

                detailCard("0x11 · Vehicle state and diagnostics", items: [
                    ("Side-stand sensor bit", raw(bike.telemetry.sideStandSensorStatus.map { $0 ? 1 : 0 }, hex: true)),
                    ("Vehicle direction byte", raw(bike.telemetry.vehicleDirectionRaw, hex: true)),
                    ("Vehicle state 1", raw(bike.telemetry.vehicleState1Raw, hex: true)),
                    ("Service reminder", raw(bike.telemetry.serviceReminderRaw, hex: true)),
                    ("Vehicle state 2 / battery field (same byte)", raw(bike.telemetry.vehicleState2Raw, hex: true)),
                    ("Software version field", raw(bike.telemetry.softwareVersionRaw, hex: true)),
                    ("MIL blink code", raw(bike.telemetry.milBlinkCodeRaw, hex: true)),
                    ("Vehicle model", raw(bike.telemetry.vehicleModelRaw, hex: true)),
                    ("Vehicle diagnostics", raw(bike.telemetry.vehicleDiagnosticsRaw, hex: true)),
                    ("Reserved byte", raw(bike.telemetry.reserve3Raw, hex: true)),
                    ("Engine temperature / tell-tale / reserved (same byte)", raw(bike.telemetry.engineTemperatureRaw, hex: true)),
                    ("Screen matrix", raw(bike.telemetry.screenMatrixRaw, hex: true)),
                    ("Vehicle state 3", raw(bike.telemetry.vehicleState3Raw, hex: true)),
                    ("ABS MIL blink code", raw(bike.telemetry.absMilBlinkCodeRaw, hex: true)),
                    ("Full ride-mode byte (low nibble is mode)", raw(bike.telemetry.vehicleModeRaw, hex: true)),
                    ("Checksum", "Known frame checksum; not exposed as telemetry")
                ])

                detailCard("0x12 · Additional ride statistics", items: [
                    ("Lean angle (raw)", raw(bike.telemetry.leanAngleRaw)),
                    ("Cruising range (raw)", raw(bike.telemetry.cruisingRangeRaw)),
                    ("Wheel-angle offset (raw)", raw(bike.telemetry.wheelAngleOffsetRaw)),
                    ("Acceleration (raw)", raw(bike.telemetry.accelerationRaw)),
                    ("Torque (raw)", raw(bike.telemetry.torqueRaw)),
                    ("Ride mileage (raw)", raw(bike.telemetry.tripMileageRaw)),
                    ("Ride fuel (raw)", raw(bike.telemetry.tripFuelRaw)),
                    ("Overspeed setting (raw)", raw(bike.telemetry.overspeedSettingRaw)),
                    ("Reserved byte", "00 observed; meaning unassigned"),
                    ("Checksum", "Known frame checksum; not exposed as telemetry")
                ])

                detailCard("0x5F · Unmapped payload", items: [
                    ("Payload hex", bike.telemetry.unknown5FPayloadHex),
                    ("Meaning", "Unknown; retained for comparison in the BLE log"),
                    ("0x6B · D-pad events", "Commands 2/3/4 map to Down/Enter/Back; event values are in the BLE log"),
                    ("0x7D / 0x42 identity frames", "Contents are redacted in logs; not decoded here")
                ])

                actionMarkerCard
            }
            .padding(.top, 10)
        } label: {
            Label("Other bike data & log notes", systemImage: "chevron.down.circle")
                .font(.headline)
                .foregroundStyle(.primary)
        }
        .tint(RTRTheme.accent)
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RTRTheme.panel, in: RoundedRectangle(cornerRadius: 16))
        .overlay { RoundedRectangle(cornerRadius: 16).stroke(RTRTheme.border, lineWidth: 1) }
    }

    private func raw(_ value: Int?, hex: Bool = false) -> String? {
        guard let value else { return nil }
        return hex ? String(format: "0x%02X", value) : String(value)
    }

    private func raw(_ value: String?) -> String? { value }

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
                .background(RTRTheme.panel, in: RoundedRectangle(cornerRadius: 16))
                .overlay { RoundedRectangle(cornerRadius: 16).stroke(RTRTheme.border, lineWidth: 1) }
            }
        }
    }

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

enum RTRTheme {
    static let background = Color.black
    static let panel = Color(red: 14.0 / 255.0, green: 16.0 / 255.0, blue: 18.0 / 255.0)
    static let border = Color(red: 40.0 / 255.0, green: 43.0 / 255.0, blue: 46.0 / 255.0)
    static let accent = Color(red: 233.0 / 255.0, green: 89.0 / 255.0, blue: 55.0 / 255.0)
    static let status = Color(red: 200.0 / 255.0, green: 241.0 / 255.0, blue: 105.0 / 255.0)
}

private struct BikeSettingsView: View {
    @EnvironmentObject private var bike: BikeBLEManager
    @Environment(\.dismiss) private var dismiss
    @State private var riderNameDraft = ""
    @State private var musicEnterDraft: BikeMusicAction = .nextTrack
    @State private var musicBackDraft: BikeMusicAction = .previousTrack
    @State private var musicDownDraft: BikeMusicAction = .pause

    var body: some View {
        Form {
            Section("BIKE") {
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

            Section("RIDER") {
                TextField("Rider name", text: $riderNameDraft)
                    .accessibilityIdentifier("riderNameField")
                    .submitLabel(.done)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()

                Text("Used for the bike’s rider-name frame when a connection starts and for the welcome button. Changes to the bike’s rider-name frame take effect on the next connection. Up to 17 characters; display text supports 15.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("D-PAD MUSIC MAPPING") {
                Text("Tap a detected switch to choose its double-press phone action.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                DPadMusicConfigurator(
                    enter: $musicEnterDraft,
                    back: $musicBackDraft,
                    down: $musicDownDraft
                )
                Text("Only Enter, Back, and Down events are mapped from current captures. Choose a playback action for each detected double-press.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .scrollContentBackground(.hidden)
        .background(RTRTheme.background)
        .listRowBackground(RTRTheme.panel)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            riderNameDraft = bike.riderName
            musicEnterDraft = bike.enterDoublePressAction
            musicBackDraft = bike.backDoublePressAction
            musicDownDraft = bike.downDoublePressAction
        }
        .onChange(of: musicEnterDraft) { _ in
            bike.setMusicControlActions(enter: musicEnterDraft, back: musicBackDraft, down: musicDownDraft)
        }
        .onChange(of: musicBackDraft) { _ in
            bike.setMusicControlActions(enter: musicEnterDraft, back: musicBackDraft, down: musicDownDraft)
        }
        .onChange(of: musicDownDraft) { _ in
            bike.setMusicControlActions(enter: musicEnterDraft, back: musicBackDraft, down: musicDownDraft)
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    bike.setRiderName(riderNameDraft)
                    bike.setMusicControlActions(enter: musicEnterDraft, back: musicBackDraft, down: musicDownDraft)
                    dismiss()
                }
            }
        }
    }

}

private struct DPadMusicConfigurator: View {
    @Binding var enter: BikeMusicAction
    @Binding var back: BikeMusicAction
    @Binding var down: BikeMusicAction

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 10), count: 3)

    var body: some View {
        LazyVGrid(columns: columns, spacing: 10) {
            emptyCell
            unavailableTile("UP", icon: "chevron.up")
            emptyCell

            actionTile("BACK", icon: "arrow.uturn.backward", action: $back)
            emptyCell
            actionTile("ENT", icon: "", action: $enter)

            emptyCell
            actionTile("DOWN", icon: "chevron.down", action: $down)
            emptyCell
        }
        .padding(.vertical, 4)
    }

    private var emptyCell: some View {
        Color.clear.frame(height: 78)
    }

    private func actionTile(_ title: String, icon: String, action: Binding<BikeMusicAction>) -> some View {
        Menu {
            ForEach(BikeMusicAction.allCases) { choice in
                Button(choice.title) { action.wrappedValue = choice }
            }
        } label: {
            VStack(spacing: 4) {
                if icon.isEmpty {
                    Text(title).font(.system(size: 18, weight: .heavy))
                } else {
                    Image(systemName: icon).font(.system(size: 19, weight: .bold))
                }
                Text(action.wrappedValue.title).font(.system(size: 9)).lineLimit(1).minimumScaleFactor(0.7)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 78)
            .background(RTRTheme.panel, in: RoundedRectangle(cornerRadius: 20))
            .overlay { RoundedRectangle(cornerRadius: 20).stroke(RTRTheme.accent, lineWidth: 1.5) }
            .contentShape(RoundedRectangle(cornerRadius: 20))
            .accessibilityLabel("\(title), \(action.wrappedValue.title)")
        }
        .buttonStyle(.plain)
    }

    private func unavailableTile(_ title: String, icon: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: icon).font(.system(size: 15, weight: .bold))
            Text("Not mapped").font(.system(size: 9)).lineLimit(1)
        }
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity)
        .frame(height: 78)
        .background(Color.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 20))
        .overlay { RoundedRectangle(cornerRadius: 20).stroke(RTRTheme.border, style: StrokeStyle(lineWidth: 1, dash: [4, 3])) }
        .accessibilityLabel("\(title), not mapped")
    }
}

private struct TelemetryTile: View {
    let title: String
    let value: String
    let unit: String
    let icon: String

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Label {
                Text(title).foregroundStyle(.secondary)
            } icon: {
                Image(systemName: icon).foregroundStyle(RTRTheme.accent)
            }
            .font(.caption)
                .lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value).font(.title2.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
                if !unit.isEmpty { Text(unit).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 92, alignment: .leading)
        .padding(14)
        .background(RTRTheme.panel, in: RoundedRectangle(cornerRadius: 14))
        .overlay { RoundedRectangle(cornerRadius: 14).stroke(RTRTheme.border, lineWidth: 1) }
        .monospacedDigit()
    }
}
