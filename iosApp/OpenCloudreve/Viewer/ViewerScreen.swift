import SwiftUI
import OpenCloudreveCore

/// 预览入口（文件浏览器 `FileFeatures.preview`，全屏呈现，自带导航栈）：
/// 解析可用预览器（偏好 → 唯一候选 → 站点默认映射 → 让用户选择），分派到原生/网页预览器；
/// 没有可用预览器或用户选“用其他应用打开”时下载到缓存后用快速查看或分享面板。
struct ViewerScreen: View {
    let accountId: String
    let file: FileItem
    /// 预览指定版本实体
    var version: String?

    @Environment(\.dismiss) private var dismiss
    @State private var loaded: Result<ViewerCatalog, ViewerCatalog.LoadFailure>?
    /// 用户在本页选定的打开方式（nil 表示按解析结果）
    @State private var selection: Selection?
    @State private var isChooserPresented = false

    private enum Selection: Equatable {
        case viewer(String)
        case external
    }

    private enum Route {
        case loading
        case failed(String)
        case choose([ViewerOption])
        case viewer(ViewerOption)
        case external
    }

    var body: some View {
        NavigationStack {
            switch route {
            case .loading:
                ViewerStatusView(status: .loading)
                    .viewerToolbar(chrome, title: file.name)
            case let .failed(message):
                ViewerStatusView(status: .failed(message)) {
                    Task { await load() }
                }
                .viewerToolbar(chrome, title: file.name)
            case let .choose(candidates):
                ViewerChooserList(fileName: file.name, candidates: candidates, current: nil, onPick: pick)
                    .viewerToolbar(chrome, title: file.name)
            case let .viewer(option):
                ViewerContent(
                    request: ViewerRequest(accountId: accountId, file: file, version: version, viewerId: option.id),
                    option: option,
                    chrome: chrome
                )
                .id(option.id)
            case .external:
                ExternalOpenScreen(accountId: accountId, file: file, version: version, chrome: chrome)
            }
        }
        .sheet(isPresented: $isChooserPresented) {
            NavigationStack {
                ViewerChooserList(fileName: file.name, candidates: candidates, current: currentViewerId, onPick: pick)
                    .navigationTitle("打开方式")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("取消", systemImage: "xmark") { isChooserPresented = false }
                        }
                    }
            }
            .presentationDetents([.medium, .large])
        }
        .task { await load() }
    }

    private var fileExtension: String { FileFormat.ext(file) }

    private var candidates: [ViewerOption] {
        guard case let .success(catalog) = loaded else { return [] }
        return catalog.candidates
    }

    private var currentViewerId: String? {
        if case let .viewer(option) = route { return option.id }
        return nil
    }

    private var route: Route {
        guard let loaded else { return .loading }
        switch loaded {
        case let .failure(failure):
            return .failed(failure.message)
        case let .success(catalog):
            switch selection {
            case .external:
                return .external
            case let .viewer(id):
                return catalog.candidates.first { $0.id == id }.map(Route.viewer) ?? .external
            case nil:
                switch catalog.decision {
                case let .open(option): return .viewer(option)
                case .choose: return .choose(catalog.candidates)
                case .none: return .external
                }
            }
        }
    }

    private var chrome: ViewerChrome {
        ViewerChrome(
            canSwitch: candidates.count > 1,
            close: { dismiss() },
            switchViewer: { isChooserPresented = true },
            openExternally: { selection = .external }
        )
    }

    private func pick(_ option: ViewerOption?, remember: Bool) {
        isChooserPresented = false
        guard let option else {
            selection = .external
            return
        }
        if remember { ViewerPreferences.remember(option.id, for: fileExtension) }
        selection = .viewer(option.id)
    }

    private func load() async {
        loaded = nil
        loaded = await ViewerCatalog.load(
            accountId: accountId,
            fileExtension: fileExtension,
            preferredViewerId: ViewerPreferences.preferred(for: fileExtension)
        )
    }
}
