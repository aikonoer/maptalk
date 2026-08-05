import Foundation

/// Files for local-demo image messages. Lives under Application Support so iTunes backups can
/// keep them, and so we never depend on a network path while trying the app on a phone.
enum LocalMediaStore {

    private static let folderName = "maptalk-media"

    static var root: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent(folderName, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Writes JPEG bytes and returns a relative path (the message stores this, not an absolute URL).
    static func save(jpeg: Data, preferredName: String? = nil) throws -> String {
        let name = preferredName ?? "\(UUID().uuidString).jpg"
        let file = root.appendingPathComponent(name)
        try jpeg.write(to: file, options: .atomic)
        return name
    }

    static func save(audio: Data, ext: String = "m4a") throws -> String {
        let name = "\(UUID().uuidString).\(ext)"
        let file = root.appendingPathComponent(name)
        try audio.write(to: file, options: .atomic)
        return name
    }

    static func save(video: Data, ext: String = "mp4") throws -> String {
        let name = "\(UUID().uuidString).\(ext)"
        let file = root.appendingPathComponent(name)
        try video.write(to: file, options: .atomic)
        return name
    }

    static func url(forRelativePath path: String) -> URL {
        root.appendingPathComponent(path)
    }

    static func data(forRelativePath path: String) -> Data? {
        try? Data(contentsOf: url(forRelativePath: path))
    }
}
