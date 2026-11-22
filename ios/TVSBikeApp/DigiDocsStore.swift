import Foundation
import Combine
import UIKit

struct BikeDocument: Identifiable, Codable, Equatable {
    let id: UUID
    var name: String
    var imageFilename: String
    let createdAt: Date
}

@MainActor
final class DigiDocsStore: ObservableObject {
    @Published private(set) var documents: [BikeDocument] = []

    private let fileManager = FileManager.default
    private let root: URL
    private var manifestURL: URL { root.appendingPathComponent("documents.json") }

    init() {
        let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = support.appendingPathComponent("TVSDigiDocs", isDirectory: true)
        load()
    }

    func addPhoto(_ data: Data, name: String) throws {
        guard let image = UIImage(data: data), let jpeg = image.jpegData(compressionQuality: 0.78) else {
            throw StoreError.invalidImage
        }
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        let id = UUID()
        let filename = "\(id.uuidString).jpg"
        try jpeg.write(to: root.appendingPathComponent(filename), options: .atomic)
        let cleanedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let document = BikeDocument(
            id: id,
            name: cleanedName.isEmpty ? "Document \(documents.count + 1)" : cleanedName,
            imageFilename: filename,
            createdAt: Date()
        )
        documents.insert(document, at: 0)
        do {
            try saveManifest()
        } catch {
            documents.removeAll { $0.id == id }
            try? fileManager.removeItem(at: root.appendingPathComponent(filename))
            throw error
        }
    }

    func remove(_ document: BikeDocument) {
        let previous = documents
        documents.removeAll { $0.id == document.id }
        do {
            try saveManifest()
            try? fileManager.removeItem(at: root.appendingPathComponent(document.imageFilename))
        } catch {
            documents = previous
        }
    }

    func imageURL(for document: BikeDocument) -> URL {
        root.appendingPathComponent(document.imageFilename)
    }

    private func load() {
        guard let data = try? Data(contentsOf: manifestURL),
              let decoded = try? JSONDecoder().decode([BikeDocument].self, from: data) else { return }
        documents = decoded.sorted { $0.createdAt > $1.createdAt }
    }

    private func saveManifest() throws {
        try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(documents)
        try data.write(to: manifestURL, options: .atomic)
    }

    enum StoreError: LocalizedError {
        case invalidImage
        var errorDescription: String? { "The selected image could not be opened." }
    }
}
