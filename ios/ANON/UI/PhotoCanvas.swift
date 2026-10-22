import SwiftUI

struct PhotoCanvas: View {
    let image: UIImage
    let faces: [FaceRegion]
    let selected: Set<Int>
    let manual: Bool
    let showRegions: Bool
    let toggle: (Int) -> Void
    let add: (FaceRegion) -> Void
    @State private var start: CGPoint?
    @State private var end: CGPoint?
    private let lime = Color(red: 0.76, green: 0.97, blue: 0.34)

    var body: some View {
        GeometryReader { proxy in
            let rect = fitted(in: proxy.size)
            ZStack(alignment: .topLeading) {
                Color.black
                Image(uiImage: image).resizable()
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)
                if showRegions {
                    ForEach(faces) { face in
                        let box = display(face.padded(), in: rect)
                        Button { toggle(face.id) } label: {
                            ZStack(alignment: .topLeading) {
                                Rectangle().fill(Color.white.opacity(0.001))
                                Rectangle().strokeBorder(selected.contains(face.id) ? lime : .white,
                                    style: StrokeStyle(lineWidth: 2, dash: selected.contains(face.id) ? [] : [5, 3]))
                                Text("\(face.id + 1)").font(.caption.bold()).padding(4)
                                    .background(selected.contains(face.id) ? lime : .white).foregroundStyle(.black)
                            }
                        }
                        .buttonStyle(.plain)
                        .frame(width: box.width, height: box.height)
                        .position(x: box.midX, y: box.midY)
                        .allowsHitTesting(!manual)
                        .accessibilityLabel("Visage \(face.id + 1)")
                        .accessibilityValue(selected.contains(face.id) ? "Anonymisé" : "Visible")
                    }
                }
                if let start, let end, manual {
                    let box = CGRect(x: min(start.x, end.x), y: min(start.y, end.y),
                                     width: abs(end.x - start.x), height: abs(end.y - start.y))
                    Rectangle().stroke(lime, style: StrokeStyle(lineWidth: 2, dash: [5, 3]))
                        .frame(width: box.width, height: box.height)
                        .position(x: box.midX, y: box.midY).allowsHitTesting(false)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 1)
                .onChanged { value in
                    guard manual, rect.contains(value.startLocation) else { return }
                    start = value.startLocation
                    end = CGPoint(x: min(rect.maxX, max(rect.minX, value.location.x)),
                                  y: min(rect.maxY, max(rect.minY, value.location.y)))
                }
                .onEnded { _ in
                    defer { start = nil; end = nil }
                    guard manual, let start, let end else { return }
                    let face = FaceRegion(id: 0,
                        left: Double((min(start.x, end.x) - rect.minX) / rect.width),
                        top: Double((min(start.y, end.y) - rect.minY) / rect.height),
                        right: Double((max(start.x, end.x) - rect.minX) / rect.width),
                        bottom: Double((max(start.y, end.y) - rect.minY) / rect.height))
                    if face.width > 0.012 && face.height > 0.012 { add(face) }
                }, including: manual ? .all : .none)
        }
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private func fitted(in size: CGSize) -> CGRect {
        let scale = min(size.width / image.size.width, size.height / image.size.height)
        let width = image.size.width * scale, height = image.size.height * scale
        return CGRect(x: (size.width - width) / 2, y: (size.height - height) / 2, width: width, height: height)
    }
    private func display(_ face: FaceRegion, in rect: CGRect) -> CGRect {
        CGRect(x: rect.minX + face.left * rect.width, y: rect.minY + face.top * rect.height,
               width: face.width * rect.width, height: face.height * rect.height)
    }
}
