import CoreImage
import ImageIO

enum ProcessingError: Error, LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let message): return message }
    }
}

/// The only images ever blurred are the bundled fictional portraits.
final class FaceRenderer {
    let context: CIContext
    private let tiles: [CIImage]
    private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!

    init(strength: Double, context: CIContext = CIContext(options: [.cacheIntermediates: false])) throws {
        self.context = context
        guard let url = Bundle.main.url(forResource: "faces", withExtension: "jpg"),
              let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let atlas = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            throw ProcessingError.message("Les portraits fictifs sont introuvables.")
        }
        var result: [CIImage] = []
        let side = 160
        for index in 0..<4 {
            let crop = CGRect(x: (index % 2) * atlas.width / 2,
                              y: (index / 2) * atlas.height / 2,
                              width: atlas.width / 2, height: atlas.height / 2)
            guard let face = atlas.cropping(to: crop),
                  let bitmap = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8,
                    bytesPerRow: side * 4, space: colorSpace,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
                throw ProcessingError.message("Préparation du remplacement impossible.")
            }
            bitmap.interpolationQuality = .high
            bitmap.draw(face, in: CGRect(x: 0, y: 0, width: side, height: side))
            guard let opaque = bitmap.makeImage() else { throw ProcessingError.message("Portrait invalide.") }
            let image = CIImage(cgImage: opaque)
            let radius = 4 + max(20, min(100, strength)) / 6
            let blurred = image.clampedToExtent().applyingFilter("CIGaussianBlur",
                parameters: [kCIInputRadiusKey: radius]).cropped(to: image.extent)
            guard let cached = context.createCGImage(blurred, from: image.extent,
                                                     format: .RGBA8, colorSpace: colorSpace) else {
                throw ProcessingError.message("Floutage des portraits impossible.")
            }
            result.append(CIImage(cgImage: cached))
        }
        tiles = result
    }

    func composite(_ source: CIImage, faces: [FaceRegion], seed: Int) -> CIImage {
        let bounds = source.extent
        var image = source
        for face in faces where face.isValid {
            let box = face.padded()
            let rect = CGRect(x: box.left * bounds.width, y: (1 - box.bottom) * bounds.height,
                              width: box.width * bounds.width, height: box.height * bounds.height)
                .integral.intersection(bounds)
            guard !rect.isEmpty else { continue }
            let index = ((face.id % tiles.count + seed % tiles.count) % tiles.count + tiles.count) % tiles.count
            let tile = tiles[index]
            let transform = CGAffineTransform(scaleX: rect.width / tile.extent.width,
                                             y: rect.height / tile.extent.height)
                .concatenating(CGAffineTransform(translationX: rect.minX, y: rect.minY))
            // Opaque, integer-aligned replacement. No feathering with original face pixels.
            image = tile.transformed(by: transform).cropped(to: rect).composited(over: image)
        }
        return image.cropped(to: bounds)
    }

    func render(_ source: CGImage, faces: [FaceRegion], seed: Int, maxSide: CGFloat? = nil) throws -> CGImage {
        var input = CIImage(cgImage: source)
        if let maxSide {
            let scale = min(1, maxSide / max(input.extent.width, input.extent.height))
            input = input.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        }
        let output = composite(input, faces: faces, seed: seed)
        guard let cg = context.createCGImage(output, from: output.extent, format: .RGBA8, colorSpace: colorSpace) else {
            throw ProcessingError.message("Rendu de l’image impossible.")
        }
        return cg
    }
}
