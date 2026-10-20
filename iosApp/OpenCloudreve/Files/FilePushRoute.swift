import SwiftUI

/// 文件操作在当前导航栈中推入的页面
enum FilePushRoute: Hashable {
    /// 目录列表（搜索结果的“前往所在目录”）
    case folder(String)
    /// 后续阶段接入的界面
    case hook(id: UUID, content: AnyView)

    static func == (lhs: FilePushRoute, rhs: FilePushRoute) -> Bool {
        switch (lhs, rhs) {
        case let (.folder(a), .folder(b)): a == b
        case let (.hook(a, _), .hook(b, _)): a == b
        default: false
        }
    }

    func hash(into hasher: inout Hasher) {
        switch self {
        case let .folder(uri): hasher.combine(uri)
        case let .hook(id, _): hasher.combine(id)
        }
    }
}
