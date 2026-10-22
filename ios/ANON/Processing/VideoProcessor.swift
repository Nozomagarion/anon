import AVFoundation
import CoreImage

struct ImportedVideo {
    let url: URL
    let firstFrame: CGImage
    let faces: [FaceRegion]
    let duration: Double
    let size: CGSize
}

struct VideoExportResult {
    let url: URL
    let frameCount: Int
}

enum VideoProcessor {
    static func load(_ url: URL) async throws -> ImportedVideo {
        let asset = AVURLAsset(url: url)
        let track = try await videoTrack(asset)
        let duration = try await asset.load(.duration)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 1536, height: 1536)
        let first = try generator.copyCGImage(at: .zero, actualTime: nil)
        let context = CIContext(options: [.cacheIntermediates: false])
        let size = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        let display = CGRect(origin: .zero, size: size).applying(transform).standardized.size
        return ImportedVideo(url: url, firstFrame: first,
            faces: try FaceDetector(context: context).detect(CIImage(cgImage: first)),
            duration: duration.seconds, size: display)
    }

    static func videoTrack(_ asset: AVAsset) async throws -> AVAssetTrack {
        let tracks = try await asset.loadTracks(withMediaType: .video)
        guard let track = tracks.first else { throw ProcessingError.message("Cette vidéo ne contient aucune image.") }
        let characteristics = try await track.load(.mediaCharacteristics)
        guard !characteristics.contains(.containsHDRVideo) else {
            throw ProcessingError.message("Cette version accepte les vidéos SDR. Convertis d’abord la vidéo HDR en SDR.")
        }
        return track
    }

    static func export(_ source: URL, strength: Double, seed: Int,
                       progress: @escaping @Sendable (Double) -> Void) async throws -> VideoExportResult {
        let output = try TemporaryFiles.makeURL(extension: "mp4")
        do {
            let asset = AVURLAsset(url: source)
            let track = try await videoTrack(asset)
            let audio = try await asset.loadTracks(withMediaType: .audio)
            let size = try await track.load(.naturalSize)
            let transform = try await track.load(.preferredTransform)
            let duration = try await asset.load(.duration)
            let rate = try await track.load(.nominalFrameRate)
            guard duration.isNumeric, duration.seconds > 0 else { throw ProcessingError.message("Durée vidéo invalide.") }
            try Task.checkCancellation()
            let job = try VideoExportJob(asset: asset, track: track, audio: audio, size: size,
                transform: transform, duration: duration, frameRate: rate,
                output: output, strength: strength, seed: seed, progress: progress)
            let count = try await job.run()
            try Task.checkCancellation()
            try MetadataCleaner.cleanMP4(at: output)
            try Task.checkCancellation()
            return VideoExportResult(url: output, frameCount: count)
        } catch {
            try? FileManager.default.removeItem(at: output)
            throw error
        }
    }

    /// Track transforms use top-left display coordinates; Core Image uses bottom-left.
    static func uprightTransform(size: CGSize, preferred: CGAffineTransform) -> CGAffineTransform {
        let display = CGRect(origin: .zero, size: size).applying(preferred).standardized
        return CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: size.height)
            .concatenating(preferred)
            .concatenating(CGAffineTransform(translationX: -display.minX, y: -display.minY))
            .concatenating(CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: display.height))
    }
}

/// Reader, writer, tracker and continuations are confined to one serial queue.
/// The encoder requests frames at its own pace, so no decoded frame is dropped.
private final class VideoExportJob: @unchecked Sendable {
    private let queue = DispatchQueue(label: "fr.anon.video", qos: .userInitiated)
    private let reader: AVAssetReader
    private let writer: AVAssetWriter
    private let videoOutput: AVAssetReaderTrackOutput
    private let videoInput: AVAssetWriterInput
    private let audioOutput: AVAssetReaderAudioMixOutput?
    private let audioInput: AVAssetWriterInput?
    private let adaptor: AVAssetWriterInputPixelBufferAdaptor
    private let renderer: FaceRenderer
    private let detector: FaceDetector
    private let transform: CGAffineTransform
    private let bounds: CGRect
    private let duration: CMTime
    private let seed: Int
    private let progress: @Sendable (Double) -> Void
    private var tracker = FaceTracker()
    private var continuation: CheckedContinuation<Int, Error>?
    private let cancellation = ExportCancellation()
    private var ended = false
    private var videoDone = false
    private var audioDone = false
    private var frames = 0
    private var lastProgress = -1

    init(asset: AVAsset, track: AVAssetTrack, audio: [AVAssetTrack], size: CGSize,
         transform preferred: CGAffineTransform, duration: CMTime, frameRate: Float,
         output: URL, strength: Double, seed: Int,
         progress: @escaping @Sendable (Double) -> Void) throws {
        self.duration = duration
        self.seed = seed
        self.progress = progress
        transform = VideoProcessor.uprightTransform(size: size, preferred: preferred)
        let display = CGRect(origin: .zero, size: size).applying(preferred).standardized
        let width = Int(ceil(display.width / 2)) * 2
        let height = Int(ceil(display.height / 2)) * 2
        guard width > 0 && height > 0 && width <= 7680 && height <= 7680 else {
            throw ProcessingError.message("Dimensions vidéo non prises en charge.")
        }
        bounds = CGRect(x: 0, y: 0, width: width, height: height)
        let context = CIContext(options: [.cacheIntermediates: false])
        renderer = try FaceRenderer(strength: strength, context: context)
        detector = FaceDetector(context: context)
        reader = try AVAssetReader(asset: asset)
        videoOutput = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ])
        videoOutput.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOutput) else { throw ProcessingError.message("Décodage vidéo indisponible.") }
        reader.add(videoOutput)

        writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
        writer.metadata = []
        writer.shouldOptimizeForNetworkUse = true
        let rate = max(1, min(240, Double(frameRate)))
        videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width, AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: Int(max(2_000_000, min(40_000_000, Double(width * height) * rate * 0.12))),
                AVVideoExpectedSourceFrameRateKey: rate,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel
            ],
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2
            ]
        ])
        videoInput.expectsMediaDataInRealTime = false
        videoInput.metadata = []
        videoInput.languageCode = "und"
        videoInput.mediaTimeScale = 60000
        // Pixels are already upright; do not copy source orientation metadata.
        videoInput.transform = .identity
        adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: videoInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:]
            ])
        guard writer.canAdd(videoInput) else { throw ProcessingError.message("Encodage H.264 indisponible.") }
        writer.add(videoInput)

        if audio.isEmpty {
            audioOutput = nil; audioInput = nil; audioDone = true
        } else {
            let decoded = AVAssetReaderAudioMixOutput(audioTracks: audio, audioSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 48000,
                AVNumberOfChannelsKey: 2, AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false, AVLinearPCMIsNonInterleaved: false
            ])
            let encoded = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 48000,
                AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 192000
            ])
            decoded.alwaysCopiesSampleData = false
            encoded.expectsMediaDataInRealTime = false
            encoded.metadata = []
            encoded.languageCode = "und"
            guard reader.canAdd(decoded), writer.canAdd(encoded) else {
                throw ProcessingError.message("Conversion audio indisponible.")
            }
            reader.add(decoded); writer.add(encoded)
            audioOutput = decoded; audioInput = encoded
        }
    }

    func run() async throws -> Int {
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    self.continuation = continuation
                    if self.cancellation.isCancelled { self.fail(CancellationError()); return }
                    guard self.writer.startWriting(), self.reader.startReading() else {
                        self.fail(self.reader.error ?? self.writer.error ?? ProcessingError.message("Démarrage de l’export impossible.")); return
                    }
                    self.writer.startSession(atSourceTime: .zero)
                    self.videoInput.requestMediaDataWhenReady(on: self.queue) { [weak self] in self?.writeVideo() }
                    self.audioInput?.requestMediaDataWhenReady(on: self.queue) { [weak self] in self?.writeAudio() }
                }
            }
        }, onCancel: {
            self.cancellation.cancel()
            self.queue.async {
                if self.continuation != nil { self.fail(CancellationError()) }
            }
        })
    }

    private func writeVideo() {
        do {
            while videoInput.isReadyForMoreMediaData && !ended && !videoDone {
                try autoreleasepool {
                    if cancellation.isCancelled { throw CancellationError() }
                    guard let sample = videoOutput.copyNextSampleBuffer() else {
                        try checkReader()
                        videoDone = true; videoInput.markAsFinished(); finishIfReady(); return
                    }
                    guard let pixels = CMSampleBufferGetImageBuffer(sample) else {
                        throw ProcessingError.message("Image vidéo illisible.")
                    }
                    let time = CMSampleBufferGetPresentationTimeStamp(sample)
                    guard time.isNumeric else { throw ProcessingError.message("Horodatage vidéo invalide.") }
                    let upright = CIImage(cvPixelBuffer: pixels).transformed(by: transform)
                    let input = upright.composited(over: CIImage(color: .black).cropped(to: bounds)).cropped(to: bounds)
                    let faces = tracker.update(try detector.detect(input), at: time.seconds)
                    let image = renderer.composite(input, faces: faces, seed: seed)
                    guard let pool = adaptor.pixelBufferPool else { throw ProcessingError.message("Tampon vidéo indisponible.") }
                    var target: CVPixelBuffer?
                    guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &target) == kCVReturnSuccess, let target else {
                        throw ProcessingError.message("Mémoire insuffisante pour exporter la vidéo.")
                    }
                    CVBufferRemoveAllAttachments(target)
                    let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
                    renderer.context.render(image, to: target, bounds: bounds, colorSpace: srgb)
                    CVBufferSetAttachment(target, kCVImageBufferCGColorSpaceKey, srgb, .shouldPropagate)
                    guard adaptor.append(target, withPresentationTime: time) else {
                        throw writer.error ?? ProcessingError.message("Écriture d’une image vidéo impossible.")
                    }
                    frames += 1
                    let fraction = max(0, min(0.99, time.seconds / duration.seconds))
                    if Int(fraction * 100) != lastProgress {
                        lastProgress = Int(fraction * 100); progress(fraction)
                    }
                }
            }
        } catch { fail(error) }
    }

    private func writeAudio() {
        guard let audioInput, let audioOutput else { return }
        do {
            while audioInput.isReadyForMoreMediaData && !ended && !audioDone {
                try autoreleasepool {
                    if cancellation.isCancelled { throw CancellationError() }
                    guard let sample = audioOutput.copyNextSampleBuffer() else {
                        try checkReader()
                        audioDone = true; audioInput.markAsFinished(); finishIfReady(); return
                    }
                    guard audioInput.append(sample) else { throw writer.error ?? ProcessingError.message("Écriture audio impossible.") }
                }
            }
        } catch { fail(error) }
    }

    private func checkReader() throws {
        if reader.status == .failed || reader.status == .cancelled {
            throw reader.error ?? ProcessingError.message("Lecture du média interrompue.")
        }
    }

    private func finishIfReady() {
        guard videoDone && audioDone && !ended else { return }
        guard frames > 0 else { fail(ProcessingError.message("Aucune image à exporter.")); return }
        writer.endSession(atSourceTime: duration)
        writer.finishWriting {
            self.queue.async {
                guard !self.ended else { return }
                guard self.writer.status == .completed else {
                    self.fail(self.writer.error ?? ProcessingError.message("Finalisation MP4 impossible.")); return
                }
                self.ended = true
                self.continuation?.resume(returning: self.frames)
                self.continuation = nil
            }
        }
    }

    private func fail(_ error: Error) {
        guard !ended else { return }
        ended = true
        if reader.status == .reading { reader.cancelReading() }
        if writer.status == .writing { writer.cancelWriting() }
        continuation?.resume(throwing: error)
        continuation = nil
    }
}

private final class ExportCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isCancelled: Bool {
        lock.lock(); defer { lock.unlock() }; return value
    }
    func cancel() { lock.lock(); value = true; lock.unlock() }
}
