import CoreImage
import Vision

final class FaceDetector {
    private let context: CIContext
    init(context: CIContext) { self.context = context }

    func detect(_ upright: CIImage, crowdSearch: Bool = true) throws -> [FaceRegion] {
        let bounds = upright.extent
        let scale = min(1, 960 / max(bounds.width, bounds.height))
        let image = upright.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let full = image.extent
        var regions = try detectTile(image, rect: full, full: full)
        if crowdSearch && max(full.width, full.height) >= 800 {
            let w = full.width * 0.6, h = full.height * 0.6
            for y in [CGFloat(0), full.height - h] {
                for x in [CGFloat(0), full.width - w] {
                    let tile = CGRect(x: x, y: y, width: w, height: h).integral.intersection(full)
                    for face in try detectTile(image, rect: tile, full: full) {
                        if let index = regions.firstIndex(where: { $0.overlap(face) > 0.35 }) {
                            regions[index] = regions[index].union(face)
                        } else { regions.append(face) }
                    }
                }
            }
        }
        return regions.enumerated().map { index, region in
            var face = region; face.id = index; return face
        }
    }

    private func detectTile(_ image: CIImage, rect: CGRect, full: CGRect) throws -> [FaceRegion] {
        guard let cg = context.createCGImage(image, from: rect, format: .RGBA8,
                                            colorSpace: CGColorSpace(name: CGColorSpace.sRGB)) else {
            throw ProcessingError.message("Impossible de préparer la détection des visages.")
        }
        let request = VNDetectFaceRectanglesRequest()
        try VNImageRequestHandler(cgImage: cg, orientation: .up, options: [:]).perform([request])
        return (request.results ?? []).compactMap { observation in
            // Vision uses bottom-left coordinates. The editor and tracker use top-left.
            let b = observation.boundingBox
            let face = FaceRegion(id: 0,
                left: max(0, Double((rect.minX + b.minX * rect.width) / full.width)),
                top: max(0, Double(1 - (rect.minY + b.maxY * rect.height) / full.height)),
                right: min(1, Double((rect.minX + b.maxX * rect.width) / full.width)),
                bottom: min(1, Double(1 - (rect.minY + b.minY * rect.height) / full.height)))
            return face.isValid ? face : nil
        }
    }
}
