import SwiftUI

@main
struct ANONApp: App {
    init() { TemporaryFiles.clearPreviousSession() }
    var body: some Scene {
        WindowGroup {
            EditorView()
                .preferredColorScheme(.dark)
                .tint(Color(red: 0.76, green: 0.97, blue: 0.34))
        }
    }
}
