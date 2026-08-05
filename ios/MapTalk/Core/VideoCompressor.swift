import AVFoundation
import CoreTransferable
import Foundation
import UniformTypeIdentifiers

/// Exports a picked clip into the shared MapTalk envelope:
/// MP4 / H.264-class, long edge ≤1280, ≤30s, ≤12 MB.
enum VideoCompressor {

    static let maxDurationMs = 30_000
    static let maxBytes = 12 * 1024 * 1024

    enum PrepareError: LocalizedError {
        case unreadable
        case tooLong
        case compressFailed
        case tooLarge

        var errorDescription: String? {
            switch self {
            case .unreadable: "That video could not be read"
            case .tooLong: "Keep videos under 30 seconds"
            case .compressFailed: "Video compression failed"
            case .tooLarge: "Video is still too large after compression"
            }
        }
    }

    static func prepare(from sourceURL: URL) async -> Result<PreparedVideo, PrepareError> {
        let asset = AVURLAsset(url: sourceURL)
        guard let duration = try? await asset.load(.duration) else { return .failure(.unreadable) }
        let seconds = CMTimeGetSeconds(duration)
        guard seconds.isFinite, seconds > 0 else { return .failure(.unreadable) }
        let durationMs = Int((seconds * 1_000).rounded())
        guard durationMs <= maxDurationMs else { return .failure(.tooLong) }

        guard let track = try? await asset.loadTracks(withMediaType: .video).first,
              let naturalSize = try? await track.load(.naturalSize),
              let transform = try? await track.load(.preferredTransform)
        else { return .failure(.unreadable) }
        let rect = CGRect(origin: .zero, size: naturalSize).applying(transform)
        let width = Int(abs(rect.width).rounded())
        let height = Int(abs(rect.height).rounded())
        guard width > 0, height > 0 else { return .failure(.unreadable) }

        let outURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("maptalk-video-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: outURL) }

        // 1280×720 preset matches Android Presentation.createForHeight(720).
        guard let session = AVAssetExportSession(
            asset: asset,
            presetName: AVAssetExportPreset1280x720
        ) else { return .failure(.compressFailed) }
        session.outputURL = outURL
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true

        let status = await withCheckedContinuation { (cont: CheckedContinuation<AVAssetExportSession.Status, Never>) in
            session.exportAsynchronously {
                cont.resume(returning: session.status)
            }
        }
        guard status == .completed else { return .failure(.compressFailed) }
        guard let data = try? Data(contentsOf: outURL), !data.isEmpty else {
            return .failure(.compressFailed)
        }
        guard data.count <= maxBytes else { return .failure(.tooLarge) }

        // Prefer encoded track size when available; fall back to source dims scaled to 1280 long edge.
        let encoded = await encodedDimensions(url: outURL) ?? scaledToFit(width: width, height: height, maxEdge: 1_280)
        return .success(
            PreparedVideo(
                data: data,
                durationMs: durationMs,
                width: encoded.width,
                height: encoded.height
            )
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
