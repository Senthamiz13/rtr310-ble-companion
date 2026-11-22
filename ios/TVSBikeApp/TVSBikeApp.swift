import SwiftUI

@main
struct TVSBikeApp: App {
    @StateObject private var bike = BikeBLEManager()
    @StateObject private var digiDocs = DigiDocsStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bike)
                .environmentObject(digiDocs)
        }
    }
}
