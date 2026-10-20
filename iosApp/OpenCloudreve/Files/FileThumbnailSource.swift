import Foundation
import OpenCloudreveCore

/// 缩略图地址来源：列表与搜索各自带上服务端给的 context hint。
/// 直接用账户的 `FileRepository.thumbUrl`（按 path 缓存到过期前 30 秒；目录/禁用/失败过的文件返回 nil，不再请求）。
struct FileThumbnailSource: Equatable, Sendable {
    let accountId: String
    let contextHint: String?
    /// 用户关闭了缩略图显示
    let enabled: Bool

    /// 缩略图地址；不需要或取不到时为 nil
    @MainActor
    func url(for file: FileItem) async -> URL? {
        guard enabled, !file.isFolder, !file.thumbDisabled else { return nil }
        // Kotlin 对象不是 Sendable：只把可发送的字段交给非隔离的 SKIE 包装，在那一侧重建 FileItem
        return await Self.load(accountId: accountId, path: file.path, metadata: file.metadata, contextHint: contextHint)
    }

    private nonisolated static func load(
        accountId: String,
        path: String,
        metadata: [String: String],
        contextHint: String?
    ) async -> URL? {
        // thumbUrl 只用到 path、类型与元数据（软链接目标、thumb:disabled）
        let item = FileItem(
            type: FileType.shared.FILE,
            id: "",
            name: "",
            createdAt: nil,
            updatedAt: nil,
            size: 0,
            metadata: metadata,
            path: path,
            shared: false,
            capability: nil,
            owned: false,
            primaryEntity: nil,
            sha256: nil,
            hashStatus: nil,
            hashReason: nil,
            folderSummary: nil,
            extendedInfo: nil
        )
        let repository = CoreGraph.shared.files(accountId: accountId)
        guard let raw = try? await repository.thumbUrl(file: item, contextHint: contextHint) else { return nil }
        return URL(string: raw)
    }
}
