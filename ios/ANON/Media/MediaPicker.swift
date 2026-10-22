import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct MediaPicker: UIViewControllerRepresentable {
    var completion: (Result<(URL, Bool), Error>?) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = .any(of: [.images, .videos])
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(completion: completion) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let completion: (Result<(URL, Bool), Error>?) -> Void
        init(completion: @escaping (Result<(URL, Bool), Error>?) -> Void) { self.completion = completion }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let item = results.first?.itemProvider else { completion(nil); return }
            let video = item.hasItemConformingToTypeIdentifier(UTType.movie.identifier)
            let category: UTType = video ? .movie : .image
            let identifier = item.registeredTypeIdentifiers.first { UTType($0)?.conforms(to: category) == true }
                ?? category.identifier
            item.loadFileRepresentation(forTypeIdentifier: identifier) { url, error in
                let result: Result<(URL, Bool), Error>
                do {
                    if let error { throw error }
                    guard let url else { throw ProcessingError.message("Le sélecteur n’a pas fourni de média.") }
                    // The provider's URL is valid only inside this callback.
                    result = .success((try TemporaryFiles.copyImport(url), video))
                } catch { result = .failure(error) }
                DispatchQueue.main.async { self.completion(result) }
            }
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
