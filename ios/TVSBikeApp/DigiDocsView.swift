import PhotosUI
import SwiftUI
import UIKit

struct DigiDocsView: View {
    @ObservedObject var store: DigiDocsStore
    @ObservedObject var bike: BikeBLEManager
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var documentName = ""
    @State private var cropImage: UIImage?
    @State private var errorMessage: String?
    @State private var sendingDocumentID: UUID?
    @State private var selectedSlot = 1

    var body: some View {
        List {
            Section {
                Text("Choose a photo, crop it to the bike’s portrait display, then send it while connected.")
                    .font(.footnote).foregroundStyle(.secondary)
                TextField("Document name", text: $documentName)
                    .textInputAutocapitalization(.words)
                PhotosPicker(selection: $selectedPhoto, matching: .images) {
                    Label("Choose and crop document", systemImage: "crop.rotate")
                }
            }

            Section("Bike transfer") {
                Label(bike.isConnected ? bike.connectionMessage : "Connect to the bike before uploading.",
                      systemImage: bike.isConnected ? "dot.radiowaves.left.and.right" : "exclamationmark.bluetooth")
                    .font(.footnote).foregroundColor(bike.isConnected ? .secondary : .orange)
                Picker("Bike document slot", selection: $selectedSlot) {
                    ForEach(1...5, id: \.self) { slot in Text("Slot \(slot)").tag(slot) }
                }
                if bike.documentTransferActive {
                    ProgressView(value: bike.documentTransferProgress)
                    Text(bike.documentTransferMessage).font(.caption).foregroundStyle(.secondary)
                } else if !bike.documentTransferMessage.isEmpty {
                    Text(bike.documentTransferMessage).font(.caption).foregroundStyle(.secondary)
                }
                Text("The app prepares an 800 × 480 RGB565 image, matching the bike’s 5:3 transfer canvas.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("Saved documents") {
                if store.documents.isEmpty {
                    Label("No documents yet", systemImage: "doc.text.image").foregroundStyle(.secondary)
                } else {
                    ForEach(store.documents) { document in
                        HStack(spacing: 12) {
                            thumbnail(for: document)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(document.name).font(.headline)
                                Text(document.createdAt.formatted(date: .abbreviated, time: .shortened))
                                    .font(.caption).foregroundStyle(.secondary)
                                Button {
                                    Task { await send(document) }
                                } label: {
                                    Label(sendingDocumentID == document.id ? "Preparing…" : "Send to bike", systemImage: "arrow.up.to.line")
                                        .font(.subheadline.weight(.medium))
                                }
                                .disabled(!bike.isConnected || bike.documentTransferActive || sendingDocumentID != nil)
                            }
                            Spacer(minLength: 0)
                            Button(role: .destructive) {
                                store.remove(document)
                            } label: {
                                Image(systemName: "trash")
                                    .frame(width: 40, height: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Delete \(document.name)")
                        }
                        .swipeActions {
                            Button(role: .destructive) { store.remove(document) } label: { Label("Delete", systemImage: "trash") }
                        }
                    }
                }
            }
        }
        .navigationTitle("Digi Docs")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: selectedPhoto) { item in
            guard let item else { return }
            Task { await loadPhoto(item) }
        }
        .sheet(isPresented: Binding(get: { cropImage != nil }, set: { if !$0 { cropImage = nil } })) {
            if let cropImage {
                NavigationStack {
                    DocumentCropView(image: cropImage) { croppedData in
                        do {
                            try store.addPhoto(croppedData, name: documentName)
                            self.cropImage = nil
                            documentName = ""
                        } catch { errorMessage = error.localizedDescription }
                    }
                }
            }
        }
        .alert("Digi Docs", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: { Text(errorMessage ?? "Please try again.") }
    }

    @ViewBuilder
    private func thumbnail(for document: BikeDocument) -> some View {
        if let image = UIImage(contentsOfFile: store.imageURL(for: document).path) {
            Image(uiImage: image).resizable().scaledToFill().frame(width: 58, height: 70).clipShape(RoundedRectangle(cornerRadius: 8))
        } else {
            Image(systemName: "doc.text.image").frame(width: 58, height: 70).background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
        }
    }

    @MainActor
    private func loadPhoto(_ item: PhotosPickerItem) async {
        defer { selectedPhoto = nil }
        do {
            guard let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
                throw BikeBLEManager.DocumentTransferError.invalidImage
            }
            cropImage = normalized(image)
        } catch { errorMessage = error.localizedDescription }
    }

    private func normalized(_ image: UIImage) -> UIImage {
        guard image.imageOrientation != .up else { return image }
        let renderer = UIGraphicsImageRenderer(size: image.size)
        return renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: image.size)) }
    }

    @MainActor
    private func send(_ document: BikeDocument) async {
        guard let image = UIImage(contentsOfFile: store.imageURL(for: document).path) else {
            errorMessage = "The saved document image could not be opened."
            return
        }
        sendingDocumentID = document.id
        defer { sendingDocumentID = nil }
        do { try await bike.sendDocument(image: image, name: document.name, pictureNumber: selectedSlot) }
        catch { errorMessage = error.localizedDescription }
    }
}

private struct DocumentCropView: View {
    let image: UIImage
    let onSave: (Data) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var zoom: CGFloat = 1
    @State private var savedZoom: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var savedOffset: CGSize = .zero
    @State private var cropError: String?

    var body: some View {
        VStack(spacing: 18) {
            Text("Fit the document inside the frame. Pinch to zoom and drag to position it.")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            GeometryReader { geometry in
                let size = geometry.size
                let imageSize = fittedSize(in: size)
                Image(uiImage: image)
                    .resizable().frame(width: imageSize.width, height: imageSize.height)
                    .scaleEffect(zoom).offset(offset)
                    .frame(width: size.width, height: size.height)
                    .clipped()
                    .overlay { Rectangle().stroke(.white, lineWidth: 2) }
                    .contentShape(Rectangle())
                    .gesture(DragGesture().onChanged { value in
                        let limitX = max(0, (imageSize.width * zoom - size.width) / 2)
                        let limitY = max(0, (imageSize.height * zoom - size.height) / 2)
                        offset = CGSize(
                            width: min(limitX, max(-limitX, savedOffset.width + value.translation.width)),
                            height: min(limitY, max(-limitY, savedOffset.height + value.translation.height))
                        )
                    }.onEnded { _ in savedOffset = offset })
                    .simultaneousGesture(MagnificationGesture().onChanged { zoom = max(1, min(4, savedZoom * $0)) }
                        .onEnded { _ in savedZoom = zoom })
            }
            .aspectRatio(5.0 / 3.0, contentMode: .fit)
            .padding(.horizontal, 18)
            Slider(value: $zoom, in: 1...4, onEditingChanged: { editing in if !editing { savedZoom = zoom } })
                .padding(.horizontal, 28)
            Button {
                do { onSave(try croppedJPEG()) }
                catch { cropError = error.localizedDescription }
            } label: {
                Label("Save cropped document", systemImage: "checkmark.circle.fill")
                    .frame(maxWidth: .infinity).padding(.vertical, 5)
            }
            .buttonStyle(.borderedProminent).padding(.horizontal, 20)
        }
        .padding(.vertical)
        .navigationTitle("Crop document")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } } }
        .alert("Could not crop image", isPresented: Binding(get: { cropError != nil }, set: { if !$0 { cropError = nil } })) {
            Button("OK", role: .cancel) { cropError = nil }
        } message: { Text(cropError ?? "Please try again.") }
    }

    private func fittedSize(in viewport: CGSize) -> CGSize {
        let source = image.size
        let scale = max(viewport.width / source.width, viewport.height / source.height)
        return CGSize(width: source.width * scale, height: source.height * scale)
    }

    private func croppedJPEG() throws -> Data {
        guard let source = image.cgImage else { throw BikeBLEManager.DocumentTransferError.invalidImage }
        let viewport = CGSize(width: 800, height: 480)
        let fitted = fittedSize(in: viewport)
        let displayedWidth = image.size.width * fitted.width / image.size.width * zoom
        let displayedHeight = image.size.height * fitted.height / image.size.height * zoom
        let originX = (viewport.width - displayedWidth) / 2 + offset.width
        let originY = (viewport.height - displayedHeight) / 2 + offset.height
        let pointRect = CGRect(x: max(0, -originX / (displayedWidth / image.size.width)), y: max(0, -originY / (displayedHeight / image.size.height)), width: viewport.width / (displayedWidth / image.size.width), height: viewport.height / (displayedHeight / image.size.height))
        let pixelScaleX = CGFloat(source.width) / image.size.width
        let pixelScaleY = CGFloat(source.height) / image.size.height
        let rect = CGRect(x: pointRect.minX * pixelScaleX, y: pointRect.minY * pixelScaleY, width: pointRect.width * pixelScaleX, height: pointRect.height * pixelScaleY).integral.intersection(CGRect(x: 0, y: 0, width: source.width, height: source.height))
        guard let cropped = source.cropping(to: rect), let data = UIImage(cgImage: cropped, scale: 1, orientation: .up).jpegData(compressionQuality: 0.92) else {
            throw BikeBLEManager.DocumentTransferError.invalidImage
        }
        return data
    }
}
