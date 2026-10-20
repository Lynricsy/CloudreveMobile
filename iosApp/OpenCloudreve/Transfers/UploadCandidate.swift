import Foundation
import OpenCloudreveCore
import UniformTypeIdentifiers

/// 一个待上传的本地文件（选择器/相册/相机/分享扩展得到），入队时转成共享层的 `UploadSource`
struct UploadCandidate: Identifiable, Hashable, Sendable {
    let id = UUID()
    /// 绝对路径（收件箱副本）或 base64 安全作用域书签（文件选择器）
    let sourceRef: String
    let name: String
    let size: Int64
    /// 修改时间（毫秒）
    let lastModified: Int64?
    let mimeType: String?

    /// 是否为收件箱中的临时副本（取消时删除）
    var isInboxCopy: Bool { TransferInbox.contains(sourceRef: sourceRef) }

    func uploadSource() -> UploadSource {
        UploadSource(
            sourceRef: sourceRef,
            name: name,
            size: size,
            lastModified: lastModified.map { KotlinLong(longLong: $0) },
            mimeType: mimeType
        )
    }

    /// 放弃上传时清理临时副本
    func discard() {
        if isInboxCopy { TransferInbox.discard(sourceRef: sourceRef) }
    }

    /// 收件箱（或其它 App 可直接读取的位置）中的文件：以绝对路径作为 sourceRef
    static func localFile(_ url: URL, name: String? = nil) throws -> UploadCandidate {
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .contentTypeKey])
        let fileName = name ?? url.lastPathComponent
        return UploadCandidate(
            sourceRef: url.path(percentEncoded: false),
            name: fileName,
            size: Int64(values.fileSize ?? 0),
            lastModified: values.contentModificationDate.map { Int64($0.timeIntervalSince1970 * 1000) },
            mimeType: mimeType(values.contentType, name: fileName)
        )
    }

    /// 文件选择器返回的外部文件（安全作用域 URL）：保存书签作为 sourceRef，后台上传时由共享层解析并按需访问
    static func pickedFile(_ url: URL) throws -> UploadCandidate {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .contentTypeKey])
        let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        return UploadCandidate(
            sourceRef: bookmark.base64EncodedString(),
            name: url.lastPathComponent,
            size: Int64(values.fileSize ?? 0),
            lastModified: values.contentModificationDate.map { Int64($0.timeIntervalSince1970 * 1000) },
            mimeType: mimeType(values.contentType, name: url.lastPathComponent)
        )
    }

    private static func mimeType(_ type: UTType?, name: String) -> String? {
        type?.preferredMIMEType ?? UTType(filenameExtension: (name as NSString).pathExtension)?.preferredMIMEType
    }
}
