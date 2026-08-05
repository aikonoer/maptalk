import AVFoundation
import CoreTransferable
import Foundation
import UniformTypeIdentifiers

/// Exports a picked clip to MP4 under the Worker/Firestore caps (≤30s, ≤12 MB).
enum VideoCompressor {

    static let maxDurationMs = 30_000
    static let maxBytes = 12 * 1024 * 1024

    static func prepare(from sourceURL: URL) async -> PreparedVideo? {
        let asset = AVURLAsset(url: sourceURL)
        guard let duration = try? await asset.load(.duration) else { return nil }
        let seconds = CMTimeGetSeconds(duration)
        guard seconds.isFinite, seconds > 0 else { return nil }
        let durationMs = Int((seconds * 1_000).rounded())
        guard durationMs <= maxDurationMs else { return nil }

        guard let track = try? await asset.loadTracks(withMediaType: .video).first,
              let naturalSize = try? await track.load(.naturalSize),
              let transform = try? await track.load(.preferredTransform)
        else { return nil }
        let rect = CGRect(origin: .zero, size: naturalSize).applying(transform)
        let width = Int(abs(rect.width).rounded())
        let height = Int(abs(rect.height).rounded())
        guard width > 0, height > 0 else { return nil }

        let outURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("maptalk-video-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: outURL) }

        guard let session = AVAssetExportSession(
            asset: asset,
            presetName: AVAssetExportPresetMediumQuality
        ) else { return nil }
        session.outputURL = outURL
        session.outputFileType = .mp4
        session.shouldOptimizeForNetworkUse = true

        let status = await withCheckedContinuation { (cont: CheckedContinuation<AVAssetExportSession.Status, Never>) in
            session.exportAsynchronously {
                cont.resume(returning: session.status)
            }
        }
        guard status == .completed else { return nil }
        guard let data = try? Data(contentsOf: outURL),
              !data.isEmpty,
              data.count <= maxBytes
        else { return nil }

        return PreparedVideo(
            data: data,
            durationMs: durationMs,
            width: width,
            height: height
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
