import Foundation
import XCTest
#if canImport(ANONCore)
@testable import ANONCore
#else
@testable import ANON
#endif

final class PrivacyTests: XCTestCase {
    private var directory: URL!
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }

    func testPaddingAndUnionStayInsideImage() {
        let a = FaceRegion(id: 4, left: 0.01, top: 0.7, right: 0.3, bottom: 0.99)
        let padded = a.padded()
        XCTAssertEqual(padded.left, 0)
        XCTAssertEqual(padded.bottom, 1)
        XCTAssertTrue(padded.isValid)
        let b = FaceRegion(id: 4, left: 0.1, top: 0.65, right: 0.4, bottom: 0.9)
        XCTAssertEqual(a.union(b), FaceRegion(id: 4, left: 0.01, top: 0.65, right: 0.4, bottom: 0.99))
    }

    func testTrackingCoversMotionRetainsBriefMissThenExpires() {
        var tracker = FaceTracker()
        let first = FaceRegion(id: 99, left: 0.2, top: 0.2, right: 0.4, bottom: 0.4)
        let next = FaceRegion(id: 77, left: 0.25, top: 0.2, right: 0.45, bottom: 0.4)
        let a = tracker.update([first], at: 0)
        let b = tracker.update([next], at: 0.04)
        XCTAssertEqual(a.first?.id, b.first?.id)
        XCTAssertEqual(b.first?.left, 0.2)
        XCTAssertEqual(b.first?.right, 0.45)
        XCTAssertEqual(tracker.update([], at: 0.15).first?.left, 0.25)
        XCTAssertTrue(tracker.update([], at: 0.25).isEmpty)
    }

    func testTrackingDoesNotMatchTwoFacesToSameIDAndResetsAfterSeek() {
        var tracker = FaceTracker()
        let face = FaceRegion(id: 0, left: 0.2, top: 0.2, right: 0.4, bottom: 0.4)
        _ = tracker.update([face], at: 1)
        XCTAssertEqual(Set(tracker.update([face, face], at: 1.04).map(\.id)).count, 2)
        XCTAssertTrue(tracker.update([], at: 0).isEmpty)
    }

    func testPNGRemovesEXIFXMPTextProfilesAndTrailingDataButKeepsPixels() throws {
        let signature = Data([137, 80, 78, 71, 13, 10, 26, 10])
        let header = chunk("IHDR", Data([0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0]))
        let pixels = chunk("IDAT", Data([120, 156, 99, 0, 1, 0, 0, 5, 0, 1]))
        let end = chunk("IEND", Data())
        let clean = signature + header + pixels + end
        let input = directory.appendingPathComponent("source.png")
        let output = directory.appendingPathComponent("export.png")
        var tagged = signature + header
        for type in ["eXIf", "iTXt", "tEXt", "zTXt", "iCCP", "tIME"] {
            tagged += chunk(type, Data("PRIVATE_AUTHOR_GPS_CAMERA_CAPTURE_DATE".utf8))
        }
        tagged += pixels + end + Data("PRIVATE_TRAILING_DATA".utf8)
        try tagged.write(to: input)
        try MetadataCleaner.cleanPNG(from: input, to: output)
        XCTAssertEqual(try Data(contentsOf: output), clean)
        XCTAssertEqual(try Data(contentsOf: input), tagged, "Original must be untouched")
    }

    func testInvalidPNGAndUnknownCriticalChunkAreRejected() throws {
        let input = directory.appendingPathComponent("bad.png")
        let output = directory.appendingPathComponent("out.png")
        try Data([0, 1, 2]).write(to: input)
        XCTAssertThrowsError(try MetadataCleaner.cleanPNG(from: input, to: output))
        let signature = Data([137, 80, 78, 71, 13, 10, 26, 10])
        try (signature + chunk("IHDR", Data(repeating: 0, count: 13)) + chunk("WHAT", Data())).write(to: input)
        XCTAssertThrowsError(try MetadataCleaner.cleanPNG(from: input, to: output))
    }

    func testMP4ScrubsDatesBothVersionsAndDescriptionsWithoutMovingSamples() throws {
        for version: UInt8 in [0, 1] {
            let dateBytes = version == 1 ? 16 : 8
            var header = Data([version, 0, 0, 0]) + Data(repeating: 0x71, count: dateBytes)
            header += Data(repeating: 0x22, count: version == 1 ? 16 : 12)
            let language = version == 1 ? 32 : 20
            var expectedHeader = header
            expectedHeader.replaceSubrange(4..<(4 + dateBytes), with: repeatElement(UInt8(0), count: dateBytes))
            var mediaHeader = header
            mediaHeader.replaceSubrange(language..<(language + 2), with: [0x15, 0xc7]) // eng
            var expectedMedia = expectedHeader
            expectedMedia.replaceSubrange(language..<(language + 2), with: [0x55, 0xc4])
            let handler = Data(repeating: 0, count: 24) + Data("PRIVATE_HANDLER".utf8)
            let privateData = Data("PRIVATE_GPS_AUTHOR_TITLE_DATE".utf8)
            let tracks = box("trak", box("tkhd", header) + box("mdia",
                box("mdhd", mediaHeader) + box("hdlr", handler) + box("meta", privateData)))
            let expectedTracks = box("trak", box("tkhd", expectedHeader) + box("mdia",
                box("mdhd", expectedMedia) + box("hdlr", Data(repeating: 0, count: handler.count))
                    + box("free", Data(repeating: 0, count: privateData.count))))
            let samples = box("mdat", Data("ENCODED_PIXELS_AND_AUDIO".utf8))
            let input = box("ftyp", Data("isom0000".utf8)) + samples
                + box("moov", box("mvhd", header) + tracks + box("udta", privateData))
                + box64("uuid", privateData)
            let expected = box("ftyp", Data("isom0000".utf8)) + samples
                + box("moov", box("mvhd", expectedHeader) + expectedTracks + box("free", Data(repeating: 0, count: privateData.count)))
                + box64("free", Data(repeating: 0, count: privateData.count))
            let url = directory.appendingPathComponent("export-\(version).mp4")
            try input.write(to: url)
            try MetadataCleaner.cleanMP4(at: url)
            let output = try Data(contentsOf: url)
            XCTAssertEqual(output, expected)
            XCTAssertEqual(output.count, input.count)
            XCTAssertEqual(output.range(of: samples), input.range(of: samples))
        }
    }

    func testMalformedMP4IsRejected() throws {
        let url = directory.appendingPathComponent("bad.mp4")
        for data in [Data([0, 0, 0]), box("mdat", Data()), Data([0xff, 0xff, 0xff, 0xff]) + Data("moov".utf8)] {
            try data.write(to: url)
            XCTAssertThrowsError(try MetadataCleaner.cleanMP4(at: url))
        }
    }

    private func be32(_ value: Int) -> Data { Data((0..<4).reversed().map { UInt8((value >> ($0 * 8)) & 255) }) }
    private func box(_ type: String, _ data: Data) -> Data { be32(data.count + 8) + Data(type.utf8) + data }
    private func box64(_ type: String, _ data: Data) -> Data {
        be32(1) + Data(type.utf8) + be32(0) + be32(data.count + 16) + data
    }
    private func chunk(_ type: String, _ data: Data) -> Data {
        let body = Data(type.utf8) + data
        var crc: UInt32 = 0xffffffff
        for byte in body {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = crc & 1 == 1 ? (crc >> 1) ^ 0xedb88320 : crc >> 1 }
        }
        return be32(data.count) + body + be32(Int(crc ^ 0xffffffff))
    }
}
