import Foundation

/// 操作结果提示（节点测试、文件地址、任务清理）；`url` 非 nil 时提供拷贝与打开
struct AdminNotice: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    var url: URL?
}
