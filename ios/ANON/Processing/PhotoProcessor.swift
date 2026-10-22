import CoreImage
import ImageIO
import UniformTypeIdentifiers

struct ImportedPhoto {
    let image: CGImage
    let faces: [FaceRegion]
}

enum PhotoProcessor {
    static func load(_ url: URL) throws -> ImportedPhoto {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 4096,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else {
            throw ProcessingError.message("Impossible d’ouvrir cette photo.")
        }
        let detector = FaceDetector(context: CIContext(options: [.cacheIntermediates: false]))
        return ImportedPhoto(image: image, faces: try detector.detect(CIImage(cgImage: image)))
    }

    static func export(_ photo: CGImage, faces: [FaceRegion], strength: Double, seed: Int) throws -> URL {
        let rendered = try FaceRenderer(strength: strength).render(photo, faces: faces, seed: seed)
        let raw = try TemporaryFiles.makeURL(extension: "png")
        let output = try TemporaryFiles.makeURL(extension: "png")
        defer { try? FileManager.default.removeItem(at: raw) }
        do {
            guard let destination = CGImageDestinationCreateWithURL(raw as CFURL, UTType.png.identifier as CFString, 1, nil) else {
                throw ProcessingError.message("Création de l’export impossible.")
            }
            // Add newly rendered pixels, never the source ImageIO container or properties.
            CGImageDestinationAddImage(destination, rendered, nil)
            guard CGImageDestinationFinalize(destination) else { throw ProcessingError.message("Écriture PNG impossible.") }
            try MetadataCleaner.cleanPNG(from: raw, to: output)
            return output
        } catch {
            try? FileManager.default.removeItem(at: output)
            throw error
        }
    }
}
