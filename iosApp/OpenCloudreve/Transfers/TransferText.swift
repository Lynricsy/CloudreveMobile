import Foundation
import OpenCloudreveCore

/// 传输列表的状态与说明文案
enum TransferText {
    static func state(_ state: TransferState) -> String {
        switch state {
        case .queued: String(localized: "等待中")
        case .preparing: String(localized: "准备中")
        case .running: String(localized: "传输中")
        case .finishing: String(localized: "正在完成")
        case .completed: String(localized: "已完成")
        case .failed: String(localized: "失败")
        case .canceled: String(localized: "已取消")
        case .paused: String(localized: "已暂停")
        }
    }

    /// 行副标题：状态 · 进度 · 速度 · 剩余时间；失败时为原因
    static func detail(_ row: TransferRow) -> String {
        let item = row.item
        switch item.state {
        case .failed:
            return String(localized: "失败：\(failureReason(row))", comment: "传输失败的原因")
        case .completed:
            return [state(item.state), FileFormat.bytes(item.size)].joined(separator: " · ")
        case .canceled:
            return state(item.state)
        case .running:
            var parts = [progress(item)]
            if row.speedBytesPerSecond > 0 {
                parts.append(String(localized: "\(FileFormat.bytes(row.speedBytesPerSecond))/秒", comment: "传输速度"))
            }
            if let eta = row.etaSeconds?.int64Value, eta > 0 {
                parts.append(String(localized: "剩余 \(remaining(eta))", comment: "预计剩余时间"))
            }
            return parts.joined(separator: " · ")
        default:
            return [state(item.state), progress(item)].joined(separator: " · ")
        }
    }

    /// 失败原因：预校验 → 同名冲突 → 已知错误码 → 服务端/系统原始信息
    static func failureReason(_ row: TransferRow) -> String {
        if let rejection = row.rejection { return UploadRejectionText.message(rejection) }
        let item = row.item
        if item.isConflict { return String(localized: "目标位置已有同名文件") }
        if let code = item.errorCode?.int32Value {
            switch code {
            case TransferErrorCode.shared.SOURCE_UNAVAILABLE: return String(localized: "无法读取本地文件")
            case TransferErrorCode.shared.ONEDRIVE_EMPTY_FILE: return String(localized: "OneDrive 不支持上传空文件")
            case TransferErrorCode.shared.STORAGE_REJECTED: return String(localized: "存储服务拒绝了请求")
            case TransferErrorCode.shared.MISSING_ETAG: return String(localized: "存储服务未返回 ETag，请检查存储桶的跨域设置")
            case TransferErrorCode.shared.UNSUPPORTED_POLICY: return String(localized: "暂不支持此存储策略")
            case TransferErrorCode.shared.RETRIES_EXHAUSTED: return String(localized: "多次重试后仍然失败")
            case ErrorCode.shared.TRANSPORT: return String(localized: "网络连接失败")
            default: break
            }
        }
        if let message = item.errorMessage, !message.isEmpty { return message }
        return String(localized: "发生未知错误")
    }

    /// `已传输 / 总大小`
    static func progress(_ item: TransferItem) -> String {
        "\(FileFormat.bytes(item.bytesDone)) / \(FileFormat.bytes(item.size))"
    }

    static func remaining(_ seconds: Int64) -> String {
        Duration.seconds(seconds).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .abbreviated, maximumUnitCount: 2))
    }
}
