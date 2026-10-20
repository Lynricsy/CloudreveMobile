import Foundation
import OpenCloudreveCore
import UniformTypeIdentifiers

/// 系统分享菜单交来的附件（`NSItemProvider` 只在本扩展的加载流程中使用，加载完成后不再访问）
struct SharedAttachment: @unchecked Sendable {
    let provider: NSItemProvider
}

/// 附件解析结果
struct ShareLoadResult: Sendable {
    /// 已复制到 App Group `Inbox/<uuid>/` 的文件
    var candidates: [UploadCandidate] = []
    /// 识别出的 OpenCloudreve 分享链接（网页 URL 或文本）
    var shareLink: String?
    /// 无法读取或不支持（普通网页链接等）的附件数
    var skipped = 0
}

/// 把分享的附件复制到收件箱：文件/图片/影片按首个数据类型取文件；纯文本先识别分享链接，否则存成 `.txt`；网页链接只接受分享链接
enum ShareAttachmentLoader {
    private enum TextPayload: Sendable {
        case text(String)
        case file(URL)
        case none
    }

    static func load(_ attachments: [SharedAttachment]) async -> ShareLoadResult {
        var result = ShareLoadResult()
        guard let directory = try? TransferInbox.makeBatchDirectory() else {
            result.skipped = attachments.count
            return result
        }
        for attachment in attachments {
            let provider = attachment.provider
            let types = provider.registeredContentTypes
            if let fileType = types.first(where: isFileType) {
                if let url = await loadFile(provider, type: fileType, into: directory),
                   let candidate = try? UploadCandidate.localFile(url) {
                    result.candidates.append(candidate)
                } else {
                    result.skipped += 1
                }
            } else if types.contains(where: { $0.conforms(to: .url) }) {
                if let text = await loadURL(provider), ShareLinkParser.shared.parse(input: text) != nil {
                    result.shareLink = result.shareLink ?? text
                } else {
                    result.skipped += 1
                }
            } else if types.contains(where: { $0.conforms(to: .plainText) }) {
                switch await loadText(provider, into: directory) {
                case let .text(text):
                    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    if ShareLinkParser.shared.parse(input: trimmed) != nil {
                        result.shareLink = result.shareLink ?? trimmed
                    } else if let url = try? TransferInbox.write(
                        Data(text.utf8),
                        into: directory,
                        name: (provider.suggestedName ?? String(localized: "共享的文本")) + ".txt"
                    ), let candidate = try? UploadCandidate.localFile(url) {
                        result.candidates.append(candidate)
                    } else {
                        result.skipped += 1
                    }
                case let .file(url):
                    if let candidate = try? UploadCandidate.localFile(url) {
                        result.candidates.append(candidate)
                    } else {
                        result.skipped += 1
                    }
                case .none:
                    result.skipped += 1
                }
            } else {
                result.skipped += 1
            }
        }
        if result.candidates.isEmpty {
            try? FileManager.default.removeItem(at: directory)
        }
        return result
    }

    /// 需要按文件处理的类型：数据或包，但不是 URL、纯文本
    private static func isFileType(_ type: UTType) -> Bool {
        (type.conforms(to: .data) || type.conforms(to: .package))
            && !type.conforms(to: .url)
            && !type.conforms(to: .plainText)
    }

    /// 取文件表示并在回调内复制到收件箱（回调返回后系统会删除临时文件）
    private static func loadFile(_ provider: NSItemProvider, type: UTType, into directory: URL) async -> URL? {
        let suggested = provider.suggestedName
        return await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: try? TransferInbox.copy(url, into: directory, name: fileName(url, suggested: suggested, type: type)))
            }
        }
    }

    private static func loadURL(_ provider: NSItemProvider) async -> String? {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                let text: String? = switch item {
                case let url as URL: url.isFileURL ? nil : url.absoluteString
                case let string as String: string
                case let data as Data: String(data: data, encoding: .utf8)
                default: nil
                }
                continuation.resume(returning: text)
            }
        }
    }

    private static func loadText(_ provider: NSItemProvider, into directory: URL) async -> TextPayload {
        await withCheckedContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                let payload: TextPayload = switch item {
                case let string as String: .text(string)
                case let data as Data: String(data: data, encoding: .utf8).map(TextPayload.text) ?? .none
                case let url as URL where url.isFileURL:
                    (try? TransferInbox.copy(url, into: directory, name: url.lastPathComponent)).map(TextPayload.file) ?? .none
                default: .none
                }
                continuation.resume(returning: payload)
            }
        }
    }

    /// 文件名：临时文件名通常就是原名；没有扩展名时按建议名 + 类型的首选扩展名
    private static func fileName(_ url: URL, suggested: String?, type: UTType) -> String {
        let name = url.lastPathComponent
        if !(name as NSString).pathExtension.isEmpty { return name }
        let base = suggested ?? name
        guard let ext = type.preferredFilenameExtension, (base as NSString).pathExtension.isEmpty else { return base }
        return base + "." + ext
    }
}
