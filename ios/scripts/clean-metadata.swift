import Foundation

// Local validation helper. Compile with the production MetadataCleaner.swift.
@main
enum CleanMetadataFixture {
    static func main() throws {
        guard CommandLine.arguments.count == 4 else {
            throw MetadataError.invalid("usage : clean-metadata png|mp4 entrée sortie")
        }
        let input = URL(fileURLWithPath: CommandLine.arguments[2])
        let output = URL(fileURLWithPath: CommandLine.arguments[3])
        if CommandLine.arguments[1] == "png" {
            try MetadataCleaner.cleanPNG(from: input, to: output)
        } else {
            try FileManager.default.copyItem(at: input, to: output)
            try MetadataCleaner.cleanMP4(at: output)
        }
    }
}
