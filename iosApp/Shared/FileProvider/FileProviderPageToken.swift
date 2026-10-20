import FileProvider

/// 目录分页位置 ↔ `NSFileProviderPage`：服务端游标模式回传 `next_page_token`，页码模式回传 `page`。
/// 系统给出的初始页（按名称/日期排序）都视为第一页，排序由服务端视图决定。
enum FileProviderPageToken: Equatable, Sendable {
    case first
    case cursor(String)
    case page(Int)

    private static let cursorPrefix = "c:"
    private static let pagePrefix = "p:"

    /// 解码系统回传的分页位置；无法识别的（含系统初始页）按第一页处理
    init(_ providerPage: NSFileProviderPage) {
        guard let text = String(data: providerPage.rawValue, encoding: .utf8) else {
            self = .first
            return
        }
        if text.hasPrefix(Self.cursorPrefix) {
            let token = String(text.dropFirst(Self.cursorPrefix.count))
            self = token.isEmpty ? .first : .cursor(token)
        } else if text.hasPrefix(Self.pagePrefix), let page = Int(text.dropFirst(Self.pagePrefix.count)), page > 0 {
            self = .page(page)
        } else {
            self = .first
        }
    }

    /// 编码给系统保存
    var providerPage: NSFileProviderPage {
        switch self {
        case .first: NSFileProviderPage.initialPageSortedByName
        case let .cursor(token): NSFileProviderPage(Data((Self.cursorPrefix + token).utf8))
        case let .page(page): NSFileProviderPage(Data((Self.pagePrefix + String(page)).utf8))
        }
    }

    /// 本页请求参数：游标（`next_page_token`）与页码（`page`，从 0 起）
    var cursor: String? {
        if case let .cursor(token) = self { token } else { nil }
    }

    var pageNumber: Int? {
        if case let .page(page) = self { page } else { nil }
    }

    /// 由一页响应决定下一页：有游标用游标；页码模式还有更多时页码 + 1；否则没有下一页
    /// - Parameters:
    ///   - nextToken: `pagination.next_token`
    ///   - hasMore: `Pagination.hasMore`（共享层按游标/总数计算）
    ///   - page: 本页 `pagination.page`
    static func next(nextToken: String?, hasMore: Bool, page: Int) -> FileProviderPageToken? {
        if let nextToken, !nextToken.isEmpty { return .cursor(nextToken) }
        return hasMore ? .page(page + 1) : nil
    }
}
