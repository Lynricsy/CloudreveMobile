import Foundation

/// 预览页发起的解压（`ViewerArchiveEffect.ExtractRequested`），交给 `ArchiveTaskViewModel`
struct ArchiveExtractRequest: Identifiable {
    let id = UUID()
    let fileUri: String
    /// 预览时选的文件名编码（nil = 自动）
    let encoding: String?
    /// 只解压这些路径；空 = 全部
    let mask: [String]
    /// 默认目标：压缩包所在目录
    let parentUri: String
}
