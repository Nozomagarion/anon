import Foundation

/// Normalized coordinates in the upright image, origin at the TOP left.
public struct FaceRegion: Identifiable, Equatable, Sendable {
    public var id: Int
    public var left: Double
    public var top: Double
    public var right: Double
    public var bottom: Double

    public init(id: Int, left: Double, top: Double, right: Double, bottom: Double) {
        self.id = id
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public var width: Double { right - left }
    public var height: Double { bottom - top }
    public var isValid: Bool {
        [left, top, right, bottom].allSatisfy { $0.isFinite && (0...1).contains($0) }
            && width > 0 && height > 0
    }
    public func contains(x: Double, y: Double) -> Bool {
        x >= left && x <= right && y >= top && y <= bottom
    }
    public func padded(_ amount: Double = 0.20) -> Self {
        Self(id: id, left: max(0, left - width * amount), top: max(0, top - height * amount),
             right: min(1, right + width * amount), bottom: min(1, bottom + height * amount))
    }
    public func union(_ other: Self) -> Self {
        Self(id: id, left: min(left, other.left), top: min(top, other.top),
             right: max(right, other.right), bottom: max(bottom, other.bottom))
    }
    public func overlap(_ other: Self) -> Double {
        let area = max(0, min(right, other.right) - max(left, other.left))
            * max(0, min(bottom, other.bottom) - max(top, other.top))
        return area / max(0.000001, min(width * height, other.width * other.height))
    }
}

/// Geometry only: no biometric identities or face embeddings are stored.
public struct FaceTracker {
    private var history: [Int: (face: FaceRegion, time: Double)] = [:]
    private var nextID = 0
    private var lastTime: Double = -.infinity
    public init() {}

    public mutating func update(_ detections: [FaceRegion], at time: Double) -> [FaceRegion] {
        if time < lastTime { history.removeAll() }
        lastTime = time
        history = history.filter { time - $0.value.time <= 0.20 }
        var available = history
        var result: [FaceRegion] = []
        for var face in detections where face.isValid {
            let match = available.values.max { $0.face.overlap(face) < $1.face.overlap(face) }
            if let match, match.face.overlap(face) > 0.12 {
                face.id = match.face.id
                available.removeValue(forKey: face.id)
                result.append(face.union(match.face))
            } else {
                face.id = nextID
                nextID += 1
                result.append(face)
            }
            // Retain the current detection, not the ever-growing union.
            history[face.id] = (face, time)
        }
        result.append(contentsOf: available.values.map(\.face))
        return result.sorted { $0.id < $1.id }
    }
}
