import SwiftUI
import OpenCloudreveCore

/// 没有可显示条目时的整页状态：加载中、错误（可重试）、空目录。放在可滚动容器里以支持下拉刷新。
struct FileListPlaceholder: View {
    let loading: Bool
    let error: UiError?
    let fs: String
    let isSearch: Bool
    let retry: () -> Void

    var body: some View {
        ScrollView {
            Group {
                if let error {
                    ContentUnavailableView {
                        Label("无法加载", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(ErrorText.message(error))
                    } actions: {
                        Button("重试", action: retry)
                            .buttonStyle(.glass)
                    }
                } else if loading {
                    ProgressView()
                        .controlSize(.large)
                } else {
                    empty
                }
            }
            .containerRelativeFrame(.vertical)
            .frame(maxWidth: .infinity)
        }
    }

    @ViewBuilder
    private var empty: some View {
        if isSearch {
            ContentUnavailableView.search
        } else {
            switch fs {
            case CrUri.companion.FS_TRASH:
                ContentUnavailableView("回收站是空的", systemImage: "trash", description: Text("删除的文件会在这里保留一段时间。"))
            case CrUri.companion.FS_SHARED_WITH_ME:
                ContentUnavailableView("没有与你共享的内容", systemImage: "person.2", description: Text("他人分享给你的文件夹会显示在这里。"))
            default:
                ContentUnavailableView("此文件夹为空", systemImage: "folder", description: Text("点按右上角的 + 新建文件夹。"))
            }
        }
    }
}
