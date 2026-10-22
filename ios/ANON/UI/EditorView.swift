import AVKit
import SwiftUI
import UniformTypeIdentifiers

struct EditorView: View {
    @StateObject private var model = EditorModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showPicker = false
    @State private var showFiles = false
    @State private var showShare = false
    private let lime = Color(red: 0.76, green: 0.97, blue: 0.34)

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    if !model.hasMedia && model.busy == nil { welcome }
                    if model.hasMedia { editor }
                    if let busy = model.busy { progress(busy) }
                    if model.output != nil { result }
                }
                .padding(20)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(Color(red: 0.045, green: 0.055, blue: 0.05))
            .navigationTitle("ANON")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { showPicker = true } label: { Label("Photos et vidéos", systemImage: "photo.on.rectangle") }
                        Button { showFiles = true } label: { Label("Fichiers", systemImage: "folder") }
                    } label: { Image(systemName: "plus.circle.fill").font(.title2) }
                    .disabled(model.busy != nil)
                    .accessibilityLabel("Importer un média")
                }
            }
            .safeAreaInset(edge: .bottom) {
                if model.hasMedia && model.output == nil {
                    Button(action: model.export) {
                        Label(model.isVideo ? "Exporter la vidéo" : "Exporter la photo", systemImage: "square.and.arrow.up")
                            .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent).foregroundStyle(.black)
                    .disabled(!model.canExport).padding(16).background(.ultraThinMaterial)
                }
            }
        }
        .sheet(isPresented: $showPicker) {
            MediaPicker { response in
                showPicker = false
                if let response {
                    switch response {
                    case .success(let (url, video)): model.openCopied(url, video: video)
                    case .failure(let error): model.error = error.localizedDescription
                    }
                }
            }
        }
        .sheet(isPresented: $showShare) {
            if let url = model.output { ShareSheet(url: url) }
        }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.image, .movie]) { result in
            switch result {
            case .success(let url): model.importFile(url)
            case .failure(let error): model.error = error.localizedDescription
            }
        }
        .alert("ANON", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
        .onChange(of: model.strength) { _ in model.refreshPreview() }
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                model.player?.pause()
                if model.cancellable {
                    model.cancel()
                    model.error = "Le traitement a été interrompu en quittant l’application. Garde ANON ouvert pendant l’export."
                }
            }
        }
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 24) {
            Image(systemName: "person.crop.rectangle.badge.xmark")
                .font(.system(size: 68, weight: .light)).foregroundStyle(lime).padding(.top, 28)
            Text("Les souvenirs restent.\nLes visages changent.")
                .font(.largeTitle.bold()).fixedSize(horizontal: false, vertical: true)
            Text("Des portraits fictifs, puis du flou. Le tout directement sur ton iPhone.")
                .font(.title3).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 18) {
                Label("Photo : choisis les visages à masquer.", systemImage: "hand.tap")
                Label("Vidéo : tous les visages détectés sont traités.", systemImage: "film")
                Label("Les métadonnées sont supprimées à l’export.", systemImage: "location.slash")
            }
            .font(.subheadline)
            Button { showPicker = true } label: {
                Label("Choisir une photo ou une vidéo", systemImage: "plus")
                    .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 9)
            }
            .buttonStyle(.borderedProminent).foregroundStyle(.black)
            Button("Importer depuis Fichiers") { showFiles = true }
                .frame(maxWidth: .infinity)
        }
    }

    private var editor: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Label(model.isVideo ? "VIDÉO" : "PHOTO", systemImage: model.isVideo ? "film" : "photo")
                    .font(.caption.weight(.semibold)).foregroundStyle(lime)
                Spacer()
                Text(model.dimensions).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            if let image = model.preview {
                PhotoCanvas(image: image, faces: model.faces, selected: model.selected,
                    manual: model.manual, showRegions: !model.isVideo,
                    toggle: model.toggle, add: model.add)
                    .frame(height: min(460, max(240, 340 * image.size.height / image.size.width)))
                    .disabled(model.busy != nil)
                    .overlay(alignment: .topTrailing) {
                        if model.rendering { ProgressView().padding(12).background(.ultraThinMaterial, in: Capsule()).padding(8) }
                    }
            } else {
                ProgressView("Préparation de l’aperçu…").frame(maxWidth: .infinity).frame(height: 280)
            }
            if model.isVideo {
                Text("Aperçu de la première image. À l’export, chaque image est analysée et tous les visages détectés sont remplacés.")
                    .font(.subheadline).foregroundStyle(.secondary)
            } else {
                Text(model.manual ? "Dessine un rectangle sur le visage à masquer." : "Touche un cadre pour masquer ou conserver ce visage.")
                    .font(.subheadline).foregroundStyle(.secondary)
                HStack {
                    Button("Tous") { model.selectAll(true) }
                    Button("Aucun") { model.selectAll(false) }
                    Spacer()
                    Button(model.manual ? "Terminer" : "+ Zone") { model.manual.toggle() }
                }.buttonStyle(.bordered).disabled(model.busy != nil)
                if model.faces.isEmpty {
                    Text("Aucun visage détecté. Tu peux en masquer un avec « + Zone ».")
                        .font(.footnote).foregroundStyle(.secondary)
                } else {
                    Text("\(model.selected.count) sur \(model.faces.count) visages masqués")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Intensité du flou").font(.headline)
                    Spacer()
                    Text("\(Int(model.strength)) %").monospacedDigit().foregroundStyle(.secondary)
                }
                Slider(value: $model.strength, in: 20...100, step: 1)
                    .accessibilityLabel("Intensité du flou")
                Button(action: model.shuffle) { Label("Changer les portraits fictifs", systemImage: "shuffle") }
            }
            .padding(16).background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 18))
            .disabled(model.busy != nil)
            Label("Métadonnées supprimées automatiquement", systemImage: "checkmark.shield")
                .font(.footnote).foregroundStyle(.secondary)
            Text("Vérifie le résultat avant de le diffuser : certains petits visages ou profils peuvent échapper à la détection. La voix reste audible.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func progress(_ label: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(label).font(.headline)
            if model.progress > 0 && model.progress < 1 {
                ProgressView(value: model.progress)
                Text("\(Int(model.progress * 100)) % · Garde ANON ouvert")
                    .font(.caption).foregroundStyle(.secondary)
            } else { ProgressView() }
            if model.cancellable { Button("Annuler", role: .cancel, action: model.cancel) }
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(18)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 18))
    }

    private var result: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Export prêt", systemImage: "checkmark.circle.fill").font(.title3.bold()).foregroundStyle(lime)
            Text("Le fichier contient le rendu final et ses métadonnées ont été nettoyées.")
                .font(.subheadline).foregroundStyle(.secondary)
            if let player = model.player { VideoPlayer(player: player).frame(height: 280).clipShape(RoundedRectangle(cornerRadius: 16)) }
            HStack {
                Button(action: model.save) {
                    Label(model.saved ? "Enregistré" : "Enregistrer", systemImage: model.saved ? "checkmark" : "square.and.arrow.down")
                }.disabled(model.saved || model.busy != nil)
                Spacer()
                Button { model.player?.pause(); showShare = true } label: {
                    Label("Partager", systemImage: "square.and.arrow.up")
                }.disabled(model.busy != nil)
            }.buttonStyle(.bordered)
        }
        .padding(18).background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 18))
    }
}
