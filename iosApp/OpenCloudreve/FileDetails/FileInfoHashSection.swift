import SwiftUI
import OpenCloudreveCore

/// 信息页“哈希”分组：未加载时“显示哈希”；已计算显示 SHA-256（点按复制）；
/// 缺失且可写时“计算”，计算中可刷新
struct FileInfoHashSection: View {
    let hash: FileHashItem?
    /// 可为缺失的哈希创建计算任务（本人“我的文件”中的文件）
    let canCompute: Bool
    let load: (_ compute: Bool) -> Void

    var body: some View {
        Section("哈希") {
            if let hash {
                if let sha256 = hash.sha256, !sha256.isEmpty {
                    CopyableRow(title: "SHA-256", value: sha256)
                        .accessibilityIdentifier("info.hash")
                } else {
                    LabeledContent("SHA-256") {
                        Text(statusText(hash.status))
                    }
                    .accessibilityIdentifier("info.hash")
                    if hash.status == "missing" && canCompute {
                        Button("计算") { load(true) }
                            .accessibilityIdentifier("info.hashCompute")
                    } else if hash.status == "pending" {
                        Button("刷新") { load(false) }
                    }
                }
            } else {
                Button("显示哈希") { load(false) }
                    .accessibilityIdentifier("info.hash")
            }
        }
    }

    /// 服务端哈希状态：ready/pending/failed/missing/uploading/unsupported/not_file/error
    private func statusText(_ status: String) -> String {
        switch status {
        case "pending": String(localized: "正在计算")
        case "missing": String(localized: "尚未计算")
        case "failed": String(localized: "计算失败")
        case "uploading": String(localized: "文件仍在上传")
        case "unsupported": String(localized: "此存储策略不支持")
        default: String(localized: "不可用")
        }
    }
}
