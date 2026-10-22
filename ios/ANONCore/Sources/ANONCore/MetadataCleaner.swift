import Foundation

public enum MetadataError: Error, LocalizedError {
    case invalid(String)
    public var errorDescription: String? {
        switch self {
        case .invalid(let detail): return "Nettoyage des métadonnées impossible : \(detail). Export annulé."
        }
    }
}

/// For freshly encoded exports only. Never edits the imported original.
public enum MetadataCleaner {
    private static let signature = Data([137, 80, 78, 71, 13, 10, 26, 10])
    private static let pngPixels: Set<String> = ["IHDR", "PLTE", "IDAT", "IEND", "tRNS"]

    public static func cleanPNG(from input: URL, to output: URL) throws {
        guard input.standardizedFileURL != output.standardizedFileURL else {
            throw MetadataError.invalid("fichiers identiques")
        }
        let source = try FileHandle(forReadingFrom: input)
        defer { try? source.close() }
        guard FileManager.default.createFile(atPath: output.path, contents: nil) else {
            throw MetadataError.invalid("création du PNG")
        }
        let target = try FileHandle(forWritingTo: output)
        defer { try? target.close() }
        guard try read(source, 8) == signature else { throw MetadataError.invalid("signature PNG") }
        try target.write(contentsOf: signature)
        var seenHeader = false
        var seenPixels = false
        while true {
            let header = try read(source, 8)
            let count = integer(header.prefix(4))
            guard count <= UInt64(Int32.max) else { throw MetadataError.invalid("bloc PNG trop grand") }
            let type = String(decoding: header.suffix(4), as: UTF8.self)
            guard seenHeader || (type == "IHDR" && count == 13) else { throw MetadataError.invalid("en-tête PNG") }
            if type == "IHDR" {
                guard !seenHeader else { throw MetadataError.invalid("double en-tête PNG") }
                seenHeader = true
            }
            if type == "IDAT" { seenPixels = true }
            let keep = pngPixels.contains(type)
            guard keep || (header[4] & 0x20 != 0) else { throw MetadataError.invalid("bloc PNG essentiel inconnu") }
            if keep { try target.write(contentsOf: header) }
            var remaining = count + 4 // Copy the existing CRC along with the pixel data.
            while remaining > 0 {
                let bytes = try read(source, Int(min(remaining, 8192)))
                if keep { try target.write(contentsOf: bytes) }
                remaining -= UInt64(bytes.count)
            }
            if type == "IEND" {
                guard count == 0 && seenPixels else { throw MetadataError.invalid("fin PNG") }
                break // Discard anything appended after IEND.
            }
        }
        try target.synchronize()
    }

    public static func cleanMP4(at url: URL) throws {
        let file = try FileHandle(forUpdating: url)
        defer { try? file.close() }
        let length = try file.seekToEnd()
        var hasMovie = false
        let containers: Set<String> = ["moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex"]
        let descriptive: Set<String> = ["udta", "meta", "uuid", "XMP_", "free", "skip"]

        func scan(_ start: UInt64, _ end: UInt64, _ depth: Int) throws {
            guard depth < 16 else { throw MetadataError.invalid("imbrication MP4") }
            var offset = start
            while offset < end {
                guard end - offset >= 8 else { throw MetadataError.invalid("MP4 tronqué") }
                try file.seek(toOffset: offset)
                let bytes = try read(file, 8)
                let shortSize = integer(bytes.prefix(4))
                let type = String(decoding: bytes.suffix(4), as: UTF8.self)
                let header: UInt64 = shortSize == 1 ? 16 : 8
                let size = shortSize == 1 ? try integer(read(file, 8)) : (shortSize == 0 ? end - offset : shortSize)
                guard size >= header && size <= end - offset else { throw MetadataError.invalid("taille MP4") }
                let payload = offset + header
                if containers.contains(type) {
                    if type == "moov" { hasMovie = true }
                    try scan(payload, offset + size, depth + 1)
                } else if descriptive.contains(type) {
                    try file.seek(toOffset: offset + 4)
                    try file.write(contentsOf: Data("free".utf8))
                    try zero(file, at: payload, count: size - header)
                } else if ["mvhd", "tkhd", "mdhd"].contains(type) {
                    guard size - header >= 12 else { throw MetadataError.invalid("en-tête MP4") }
                    try file.seek(toOffset: payload)
                    let version = try read(file, 1)[0]
                    guard version <= 1 else { throw MetadataError.invalid("version MP4") }
                    let clockBytes: UInt64 = version == 1 ? 16 : 8
                    guard size - header >= 4 + clockBytes else { throw MetadataError.invalid("dates MP4") }
                    try zero(file, at: payload + 4, count: clockBytes)
                    if type == "mdhd" {
                        let language: UInt64 = version == 1 ? 32 : 20
                        guard size - header >= language + 4 else { throw MetadataError.invalid("langue MP4") }
                        try file.seek(toOffset: payload + language)
                        try file.write(contentsOf: Data([0x55, 0xc4])) // ISO 639-2: und
                    }
                } else if type == "hdlr" {
                    guard size - header >= 24 else { throw MetadataError.invalid("piste MP4") }
                    try zero(file, at: payload + 24, count: size - header - 24)
                }
                offset += size
            }
        }
        try scan(0, length, 0)
        guard hasMovie else { throw MetadataError.invalid("absence de conteneur vidéo") }
        try file.synchronize()
    }

    private static func read(_ file: FileHandle, _ count: Int) throws -> Data {
        var data = Data()
        while data.count < count {
            guard let part = try file.read(upToCount: count - data.count), !part.isEmpty else {
                throw MetadataError.invalid("fichier tronqué")
            }
            data.append(part)
        }
        return data
    }
    private static func integer<T: Sequence>(_ data: T) -> UInt64 where T.Element == UInt8 {
        data.reduce(0) { ($0 << 8) | UInt64($1) }
    }
    private static func zero(_ file: FileHandle, at offset: UInt64, count: UInt64) throws {
        try file.seek(toOffset: offset)
        let zeros = Data(repeating: 0, count: 8192)
        var remaining = count
        while remaining > 0 {
            let size = Int(min(remaining, 8192))
            try file.write(contentsOf: zeros.prefix(size))
            remaining -= UInt64(size)
        }
    }
}
