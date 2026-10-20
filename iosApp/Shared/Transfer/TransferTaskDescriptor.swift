import Foundation

/// `URLSessionTask.taskDescription` 的编码：把平台任务映射回共享层的传输任务。
///
/// - 上传分片：`<taskId>#<index>`（index 为非负整数）
/// - 下载：`<taskId>#download`
///
/// taskId 是 UUID，不含 `#`；解析时以最后一个 `#` 分割，容忍 id 中意外出现的 `#`。
enum TransferTaskDescriptor: Hashable, Sendable {
    case upload(taskId: String, index: Int)
    case download(taskId: String)

    private static let downloadMarker = "download"

    var taskId: String {
        switch self {
        case let .upload(taskId, _), let .download(taskId): taskId
        }
    }

    /// 写入 `taskDescription` 的文本
    var encoded: String {
        switch self {
        case let .upload(taskId, index): "\(taskId)#\(index)"
        case let .download(taskId): "\(taskId)#\(Self.downloadMarker)"
        }
    }

    /// 解析 `taskDescription`；不是本执行器创建的任务（或格式损坏）返回 nil
    init?(parsing text: String?) {
        guard let text, let separator = text.lastIndex(of: "#") else { return nil }
        let taskId = String(text[..<separator])
        let suffix = String(text[text.index(after: separator)...])
        guard !taskId.isEmpty else { return nil }
        if suffix == Self.downloadMarker {
            self = .download(taskId: taskId)
        } else if !suffix.isEmpty, suffix.allSatisfy({ $0.isASCII && $0.isNumber }), let index = Int(suffix) {
            self = .upload(taskId: taskId, index: index)
        } else {
            return nil
        }
    }
}
