import AVFoundation
import CoreImage
import ImageIO
import XCTest
@testable import ANON

final class ProcessingTests: XCTestCase {
    func testOpaqueReplacementDoesNotDependOnSourceFacePixels() throws {
        let renderer = try FaceRenderer(strength: 65)
        let face = FaceRegion(id: 0, left: 0.3, top: 0.2, right: 0.7, bottom: 0.6)
        let red = try solid(.red), blue = try solid(.blue)
        let a = try renderer.render(red, faces: [face], seed: 1)
        let b = try renderer.render(blue, faces: [face], seed: 1)
        XCTAssertEqual(try pixel(a, x: 100, y: 80), try pixel(b, x: 100, y: 80))
        XCTAssertNotEqual(try pixel(a, x: 10, y: 10), try pixel(b, x: 10, y: 10))
    }

    func testRealPortraitVideoKeepsEveryFrameAndAudioAndRemovesMetadata() async throws {
        let input = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "faces-portrait", withExtension: "mp4"))
        let source = AVURLAsset(url: input)
        let sourceTags = try await source.load(.metadata)
        XCTAssertFalse(sourceTags.isEmpty, "Positive fixture must contain descriptive metadata")
        let imported = try await VideoProcessor.load(input)
        XCTAssertFalse(imported.faces.isEmpty, "Vision must detect a face in the fictional portrait fixture")
        XCTAssertGreaterThan(imported.size.height, imported.size.width)
        let result = try await VideoProcessor.export(input, strength: 65, seed: 1) { _ in }
        defer { try? FileManager.default.removeItem(at: result.url) }
        let output = AVURLAsset(url: result.url)
        let inputFrames = try await countFrames(source)
        let outputFrames = try await countFrames(output)
        XCTAssertEqual(result.frameCount, inputFrames)
        XCTAssertEqual(outputFrames, inputFrames)
        let tracks = try await output.loadTracks(withMediaType: .audio)
        XCTAssertEqual(tracks.count, 1)
        let duration = try await output.load(.duration)
        let originalDuration = try await source.load(.duration)
        XCTAssertEqual(duration.seconds, originalDuration.seconds, accuracy: 0.05)
        let tags = try await output.load(.metadata)
        XCTAssertTrue(tags.isEmpty)
        let bytes = try Data(contentsOf: result.url)
        XCTAssertNil(bytes.range(of: Data("PRIVATE_TEST".utf8)))

        // Compare against ImageGenerator's upright orientation, independently of our transform.
        let actual = try AVAssetImageGenerator(asset: output).copyCGImage(at: .zero, actualTime: nil)
        let expected = try FaceRenderer(strength: 65).render(imported.firstFrame, faces: imported.faces, seed: 1)
        XCTAssertEqual(actual.width, expected.width)
        XCTAssertEqual(actual.height, expected.height)
        for face in imported.faces {
            let x = Int((face.left + face.right) / 2 * Double(expected.width))
            let y = Int((face.top + face.bottom) / 2 * Double(expected.height))
            let a = try pixel(actual, x: x, y: y)
            let b = try pixel(expected, x: x, y: y)
            for channel in 0..<3 { XCTAssertLessThan(abs(Int(a[channel]) - Int(b[channel])), 30) }
        }
        // Corner color verifies rotation, not just face locations in a symmetric scene.
        let cornerA = try pixel(actual, x: actual.width - 15, y: 15)
        let cornerB = try pixel(expected, x: expected.width - 15, y: 15)
        for channel in 0..<3 { XCTAssertLessThan(abs(Int(cornerA[channel]) - Int(cornerB[channel])), 30) }
    }

    func testCancelledExportDoesNotReturnAFile() async throws {
        let input = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "faces-portrait", withExtension: "mp4"))
        let task = Task { try await VideoProcessor.export(input, strength: 65, seed: 0) { _ in } }
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("A cancelled export must not publish a file")
        } catch is CancellationError { /* expected */ }
    }

    private func countFrames(_ asset: AVAsset) async throws -> Int {
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var count = 0
        while output.copyNextSampleBuffer() != nil { count += 1 }
        XCTAssertEqual(reader.status, .completed)
        return count
    }

    private func solid(_ color: CIColor) throws -> CGImage {
        try XCTUnwrap(CIContext().createCGImage(CIImage(color: color), from: CGRect(x: 0, y: 0, width: 200, height: 200)))
    }

    private func pixel(_ image: CGImage, x: Int, y: Int) throws -> [UInt8] {
        let width = image.width, height = image.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        try bytes.withUnsafeMutableBytes { storage in
            let context = try XCTUnwrap(CGContext(data: storage.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        let offset = (y * width + x) * 4
        return Array(bytes[offset..<(offset + 4)])
    }
}
