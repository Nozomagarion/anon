import Foundation

enum TemporaryFiles {
    static var directory: URL { FileManager.default.temporaryDirectory.appendingPathComponent("ANON", isDirectory: true) }

    static func makeURL(extension suffix: String) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("ANON_\(UUID().uuidString).\(suffix)")
    }

    static func copyImport(_ source: URL) throws -> URL {
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        let target = try makeURL(extension: source.pathExtension.isEmpty ? "media" : source.pathExtension)
        try FileManager.default.copyItem(at: source, to: target)
        return target
    }

    static func clearPreviousSession() {
        // This dedicated temporary directory contains only our imports and unfinished exports.
        if let contents = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) {
            for url in contents { try? FileManager.default.removeItem(at: url) }
        }
    }
}
