import UIKit

/// 预览器读取签名地址内容（签名地址无需鉴权头）
enum RemoteFile {
    /// 整个下载到内存（图片、PDF 等）
    nonisolated static func data(from url: URL) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(from: url)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        return data
    }

    /// 下载并解码图片（HEIC/HEIF 由系统原生解码）；失败为 nil
    nonisolated static func image(from url: URL) async -> UIImage? {
        guard let data = try? await data(from: url), let image = UIImage(data: data) else { return nil }
        return await image.byPreparingForDisplay() ?? image
    }

    /// 下载到缓存目录 `Caches/viewer/<随机>/<文件名>`（保留文件名以便系统按扩展名识别类型）
    nonisolated static func download(from url: URL, fileName: String) async throws -> URL {
        let (temporary, response) = try await URLSession.shared.download(from: url)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw URLError(.badServerResponse)
        }
        let destination = try cacheURL(fileName: fileName)
        try FileManager.default.moveItem(at: temporary, to: destination)
        return destination
    }

    /// 新的缓存文件位置（目录已创建）
    nonisolated static func cacheURL(fileName: String) throws -> URL {
        let caches = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let directory = caches.appending(path: "viewer", directoryHint: .isDirectory)
            .appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let safeName = fileName.replacingOccurrences(of: "/", with: "_")
        return directory.appending(path: safeName.isEmpty ? "file" : safeName, directoryHint: .notDirectory)
    }
}
