import UIKit

/// Shrinks a camera/library photo before it ever leaves the device. Cap long edge at 1280 px and
/// encode JPEG ~0.72 — the same envelope Discord-style chat apps use so a 4–12 MB original
/// becomes a few hundred KB.
enum ImageCompressor {

    static let maxEdge: CGFloat = 1_280
    static let jpegQuality: CGFloat = 0.72
    /// Hard ceiling after compress; anything larger is rejected rather than uploaded raw.
    static let maxBytes = 1_500_000

    static func prepare(_ image: UIImage) -> PreparedImage? {
        let oriented = image.normalizedOrientation()
        let scaled = oriented.scaledToFit(maxEdge: maxEdge)
        guard let data = scaled.jpegData(compressionQuality: jpegQuality),
              data.count <= maxBytes
        else { return nil }
        return PreparedImage(
            jpegData: data,
            width: Int(scaled.size.width.rounded()),
            height: Int(scaled.size.height.rounded())
        )
    }
}

private extension UIImage {
    func normalizedOrientation() -> UIImage {
        guard imageOrientation != .up else { return self }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = scale
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in draw(in: CGRect(origin: .zero, size: size)) }
    }

    func scaledToFit(maxEdge: CGFloat) -> UIImage {
        let longest = max(size.width, size.height)
        guard longest > maxEdge else { return self }
        let scale = maxEdge / longest
        let target = CGSize(width: size.width * scale, height: size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        return renderer.image { _ in draw(in: CGRect(origin: .zero, size: target)) }
    }
}
