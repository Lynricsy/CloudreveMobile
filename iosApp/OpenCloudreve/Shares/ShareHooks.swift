import SwiftUI
import OpenCloudreveCore

/// 文件浏览器接入点：菜单“分享”为单个文件/目录新建分享
@MainActor
enum ShareHooks {
    static let share = FileHook(presentation: .sheet) { ctx in
        guard let file = ctx.files.first else { return nil }
        return AnyView(ShareEditSheet(accountId: ctx.accountId, fileUri: file.path, shareId: nil, onSaved: ctx.refresh))
    }
}
