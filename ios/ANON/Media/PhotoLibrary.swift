import Photos

enum PhotoLibrary {
    static func save(_ url: URL, video: Bool) async throws {
        let authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard authorization == .authorized || authorization == .limited else {
            throw ProcessingError.message("L’ajout à Photos est désactivé. Tu peux partager le fichier ou autoriser ANON dans Réglages > Confidentialité > Photos.")
        }
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            let options = PHAssetResourceCreationOptions()
            options.originalFilename = url.lastPathComponent
            request.addResource(with: video ? .video : .photo, fileURL: url, options: options)
            request.location = nil
            // Photos supplies its own date for the new asset, never the original capture date.
        }
    }
}
