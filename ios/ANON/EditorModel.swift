import AVKit
import CoreImage
import SwiftUI
import UniformTypeIdentifiers

private actor PhotoWorker {
    func load(_ url: URL) throws -> ImportedPhoto {
        try Task.checkCancellation()
        return try PhotoProcessor.load(url)
    }
    func preview(_ image: CGImage, faces: [FaceRegion], strength: Double, seed: Int) throws -> CGImage {
        try Task.checkCancellation()
        let result = try FaceRenderer(strength: strength).render(image, faces: faces, seed: seed, maxSide: 1536)
        try Task.checkCancellation()
        return result
    }
    func export(_ image: CGImage, faces: [FaceRegion], strength: Double, seed: Int) throws -> URL {
        try Task.checkCancellation()
        let url = try PhotoProcessor.export(image, faces: faces, strength: strength, seed: seed)
        do { try Task.checkCancellation(); return url }
        catch { try? FileManager.default.removeItem(at: url); throw error }
    }
    func copy(_ url: URL) throws -> URL { try TemporaryFiles.copyImport(url) }
}

@MainActor
final class EditorModel: ObservableObject {
    @Published private(set) var preview: UIImage?
    @Published private(set) var faces: [FaceRegion] = []
    @Published private(set) var selected: Set<Int> = []
    @Published private(set) var isVideo = false
    @Published private(set) var dimensions = ""
    @Published private(set) var busy: String?
    @Published private(set) var rendering = false
    @Published private(set) var progress: Double = 0
    @Published private(set) var output: URL?
    @Published private(set) var player: AVPlayer?
    @Published private(set) var saved = false
    @Published var strength: Double = 65
    @Published var manual = false
    @Published var error: String?
    private var input: URL?
    private var sourceImage: CGImage?
    private var seed = Int.random(in: 0..<1_000_000)
    private var operation: Task<Void, Never>?
    private var renderTask: Task<Void, Never>?
    private var generation = 0
    private let worker = PhotoWorker()
    var hasMedia: Bool { sourceImage != nil }
    var canExport: Bool { hasMedia && busy == nil && !rendering }
    var cancellable: Bool { busy != nil && busy != "Enregistrement dans Photos…" }

    func importFile(_ url: URL) {
        guard busy == nil else { return }
        busy = "Copie du média…"
        operation = Task {
            do {
                let copied = try await worker.copy(url)
                if Task.isCancelled {
                    try? FileManager.default.removeItem(at: copied)
                    throw CancellationError()
                }
                busy = nil
                openCopied(copied, video: UTType(filenameExtension: url.pathExtension)?.conforms(to: .movie) == true)
            } catch {
                if !(error is CancellationError) { self.error = error.localizedDescription }
                busy = nil
            }
        }
    }

    func openCopied(_ url: URL, video: Bool) {
        guard busy == nil else { try? FileManager.default.removeItem(at: url); return }
        renderTask?.cancel(); generation += 1
        clearOutput()
        if let input { try? FileManager.default.removeItem(at: input) }
        input = url; sourceImage = nil; preview = nil; faces = []; selected = []
        manual = false; error = nil; rendering = false; isVideo = video; progress = 0
        busy = "Recherche des visages…"
        operation = Task {
            do {
                if video {
                    let clip = try await VideoProcessor.load(url)
                    try Task.checkCancellation()
                    sourceImage = clip.firstFrame; faces = clip.faces
                    dimensions = "\(Int(clip.size.width)) × \(Int(clip.size.height)) · \(Int(clip.duration.rounded())) s"
                } else {
                    let photo = try await worker.load(url)
                    try Task.checkCancellation()
                    sourceImage = photo.image; faces = photo.faces
                    dimensions = "\(photo.image.width) × \(photo.image.height)"
                }
                selected = Set(faces.map(\.id))
                busy = nil; refreshPreview()
            } catch {
                busy = nil
                try? FileManager.default.removeItem(at: url)
                input = nil
                if !(error is CancellationError) { self.error = error.localizedDescription }
            }
        }
    }

    func toggle(_ id: Int) {
        guard !isVideo && busy == nil else { return }
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
        refreshPreview()
    }
    func selectAll(_ all: Bool) {
        guard !isVideo && busy == nil else { return }
        selected = all ? Set(faces.map(\.id)) : []
        refreshPreview()
    }
    func add(_ region: FaceRegion) {
        guard !isVideo && busy == nil && region.isValid else { return }
        var face = region; face.id = (faces.map(\.id).max() ?? -1) + 1
        faces.append(face); selected.insert(face.id); manual = false
        refreshPreview()
    }
    func shuffle() { seed += Int.random(in: 1...3); refreshPreview() }

    func refreshPreview() {
        guard let sourceImage, busy == nil else { return }
        clearOutput()
        renderTask?.cancel(); generation += 1
        let version = generation
        let masks = isVideo ? faces : faces.filter { selected.contains($0.id) }
        let strength = strength, seed = seed
        rendering = true
        renderTask = Task {
            do {
                try await Task.sleep(nanoseconds: 80_000_000)
                let image = try await worker.preview(sourceImage, faces: masks, strength: strength, seed: seed)
                guard version == generation else { return }
                preview = UIImage(cgImage: image); rendering = false
            } catch {
                guard version == generation else { return }
                rendering = false
                if !(error is CancellationError) { self.error = error.localizedDescription }
            }
        }
    }

    func export() {
        guard canExport, let input, let sourceImage else { return }
        clearOutput(); error = nil; progress = 0; manual = false
        busy = isVideo ? "Remplacement de tous les visages…" : "Préparation du PNG…"
        UIApplication.shared.isIdleTimerDisabled = true
        let masks = faces.filter { selected.contains($0.id) }
        let video = isVideo, strength = strength, seed = seed
        operation = Task {
            defer { busy = nil; UIApplication.shared.isIdleTimerDisabled = false }
            do {
                let url: URL
                if video {
                    let result = try await VideoProcessor.export(input, strength: strength, seed: seed) { [weak self] value in
                        Task { @MainActor [weak self] in self?.progress = value }
                    }
                    url = result.url
                } else {
                    url = try await worker.export(sourceImage, faces: masks, strength: strength, seed: seed)
                }
                if Task.isCancelled { try? FileManager.default.removeItem(at: url); throw CancellationError() }
                output = url; progress = 1
                if video { player = AVPlayer(url: url) }
            } catch {
                if !(error is CancellationError) { self.error = error.localizedDescription }
            }
        }
    }

    func save() {
        guard let output, busy == nil else { return }
        busy = "Enregistrement dans Photos…"
        player?.pause()
        operation = Task {
            defer { busy = nil }
            do { try await PhotoLibrary.save(output, video: isVideo); saved = true }
            catch { self.error = error.localizedDescription }
        }
    }

    func cancel() {
        guard cancellable else { return }
        operation?.cancel()
        // Keep controls locked until the writer has closed and removed the partial file.
        busy = "Annulation…"
    }

    private func clearOutput() {
        player?.pause(); player = nil
        if let output { try? FileManager.default.removeItem(at: output) }
        output = nil; saved = false
    }
}
