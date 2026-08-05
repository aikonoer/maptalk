import AVFoundation
import CoreTransferable
import Foundation
import UIKit
import UniformTypeIdentifiers

/// Exports a picked clip into the shared MapTalk envelope:
/// MP4 / H.264-class, long edge ≤1280, ≤15s, ≤12 MB.
/// Prefer sharpness over length — shorten before crushing quality.
enum VideoCompressor {

    static let maxDurationMs = 15_000
    static let maxBytes = 12 * 1024 * 1024

    /// Kid-friendly messages — no "compression" jargon.
    enum PrepareError: LocalizedError {
        case unreadable
        case tooLong
        case compressFailed
        case tooLarge

        var errorDescription: String? {
            switch self {
            case .unreadable: "Hmm, we couldn’t open that video. Try another one?"
            case .tooLong: "Pick up to 15 seconds of your video"
            case .compressFailed: "Couldn’t get that video ready. Try another one?"
            case .tooLarge: "That part is still a bit big. Try a shorter bit!"
            }
        }
    }

    enum Outcome: Sendable {
        case ready(PreparedVideo)
        /// Source is longer than the max — show the trimmer before compressing.
        case needsTrim(sourceURL: URL, durationMs: Int)
        case failed(PrepareError)
    }

    /// Sharp first. Never drop to "LowQuality" — that looks mushy in chat.
    private static let exportPresets = [
        AVAssetExportPreset1280x720,
        AVAssetExportPreset960x540,
    ]

    /// If still too big at a given quality, shorten before lowering resolution.
    private static let durationLadderMs = [15_000, 12_000, 10_000]

    /// Inspect then compress. Pass `clipStartMs` after the user trims a long clip.
    static func prepare(from sourceURL: URL, clipStartMs: Int? = nil) async -> Outcome {
        let asset = AVURLAsset(url: sourceURL)
        guard let duration = try? await asset.load(.duration) else { return .failed(.unreadable) }
        let seconds = CMTimeGetSeconds(duration)
        guard seconds.isFinite, seconds > 0 else { return .failed(.unreadable) }
        let durationMs = Int((seconds * 1_000).rounded())

        if clipStartMs == nil, durationMs > maxDurationMs {
            return .needsTrim(sourceURL: sourceURL, durationMs: durationMs)
        }

        let startMs = max(0, clipStartMs ?? 0)
        let maxStart = max(0, durationMs - 1)
        let clampedStart = min(startMs, maxStart)

        guard let track = try? await asset.loadTracks(withMediaType: .video).first,
              let naturalSize = try? await track.load(.naturalSize),
              let transform = try? await track.load(.preferredTransform)
        else { return .failed(.unreadable) }
        let rect = CGRect(origin: .zero, size: naturalSize).applying(transform)
        let width = Int(abs(rect.width).rounded())
        let height = Int(abs(rect.height).rounded())
        guard width > 0, height > 0 else { return .failed(.unreadable) }

        // Quality outer, length inner: stay sharp, cut seconds if needed.
        for preset in exportPresets where AVAssetExportSession.allExportPresets().contains(preset) {
            for windowMs in durationLadderMs {
                let clipDurationMs = min(windowMs, durationMs - clampedStart)
                guard clipDurationMs > 500 else { continue }
                if let prepared = await export(
                    asset: asset,
                    startMs: clampedStart,
                    clipDurationMs: clipDurationMs,
                    sourceWidth: width,
                    sourceHeight: height,
                    preset: preset
                ) {
                    return .ready(prepared)
                }
            }
        }

        return .failed(.tooLarge)
    }

    private static func export(
        asset: AVURLAsset,
        startMs: Int,
        clipDurationMs: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        preset: String
    ) async -> PreparedVideo? {
        let outURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("maptalk-video-\(UUID().uuidString).mp4")
        guard let session = AVAssetExportSession(asset: asset, presetName: preset) else { return nil }
        session.outputURL = outURL
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true
        let start = CMTime(value: CMTimeValue(startMs), timescale: 1_000)
        let clipDuration = CMTime(value: CMTimeValue(clipDurationMs), timescale: 1_000)
        session.timeRange = CMTimeRange(start: start, duration: clipDuration)

        let status = await withCheckedContinuation { (cont: CheckedContinuation<AVAssetExportSession.Status, Never>) in
            session.exportAsynchronously {
                cont.resume(returning: session.status)
            }
        }
        guard status == .completed else {
            try? FileManager.default.removeItem(at: outURL)
            return nil
        }
        let byteCount = (try? FileManager.default.attributesOfItem(atPath: outURL.path)[.size] as? NSNumber)?.intValue ?? 0
        guard byteCount > 0, byteCount <= maxBytes else {
            try? FileManager.default.removeItem(at: outURL)
            return nil
        }
        let encoded = await encodedDimensions(url: outURL)
            ?? scaledToFit(width: sourceWidth, height: sourceHeight, maxEdge: 1_280)
        return PreparedVideo(
            fileURL: outURL,
            durationMs: clipDurationMs,
            width: encoded.width,
            height: encoded.height
        )
    }

    private static func encodedDimensions(url: URL) async -> (width: Int, height: Int)? {
        let asset = AVURLAsset(url: url)
        guard let track = try? await asset.loadTracks(withMediaType: .video).first,
              let naturalSize = try? await track.load(.naturalSize),
              let transform = try? await track.load(.preferredTransform)
        else { return nil }
        let rect = CGRect(origin: .zero, size: naturalSize).applying(transform)
        let w = Int(abs(rect.width).rounded())
        let h = Int(abs(rect.height).rounded())
        guard w > 0, h > 0 else { return nil }
        return (w, h)
    }

    private static func scaledToFit(width: Int, height: Int, maxEdge: Int) -> (width: Int, height: Int) {
        let longest = max(width, height)
        guard longest > maxEdge else { return (width, height) }
        let scale = Double(maxEdge) / Double(longest)
        return (
            max(1, Int((Double(width) * scale).rounded())),
            max(1, Int((Double(height) * scale).rounded()))
        )
    }

    /// Thumbnails for the trimmer filmstrip.
    static func filmstrip(from url: URL, count: Int = 8) async -> [UIImage] {
        let asset = AVURLAsset(url: url)
        guard let duration = try? await asset.load(.duration) else { return [] }
        let seconds = CMTimeGetSeconds(duration)
        guard seconds.isFinite, seconds > 0 else { return [] }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 120, height: 120)
        var images: [UIImage] = []
        for index in 0..<count {
            let t = seconds * (Double(index) + 0.5) / Double(count)
            let time = CMTime(seconds: t, preferredTimescale: 600)
            if let cg = try? await generator.image(at: time).image {
                images.append(UIImage(cgImage: cg))
            }
        }
        return images
    }
}

/// PhotosPicker transferable for movie files.
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent("maptalk-pick-\(UUID().uuidString).\(ext)")
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.copyItem(at: received.file, to: dest)
            return Self(url: dest)
        }
    }
}
