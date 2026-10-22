import SwiftUI
import OpenCloudreveCore

/// 没有可用预览器 / “用其他应用打开”：下载到缓存（显示进度）后用快速查看；系统也不支持时提供分享面板
struct ExternalOpenScreen: View {
    let accountId: String
    let file: FileItem
    let version: String?
    let chrome: ViewerChrome

    @State private var phase: Phase = .preparing
    @State private var progress: Progress?
    /// 重试时递增：下载在随视图取消的 `.task(id:)` 里执行
    @State private var attempt = 0

    private enum Phase {
        case preparing
        case downloading
        case ready(URL)
        case failed(String)
    }

    var body: some View {
        Group {
            switch phase {
            case .preparing:
                ViewerStatusView(status: .loading)
            case .downloading:
                VStack(spacing: 16) {
                    if let progress {
                        ProgressView(progress)
                            .frame(maxWidth: 280)
                    } else {
                        ProgressView()
                    }
                    Text("正在下载以便打开…")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            case let .ready(url):
                if QuickLookPreview.canPreview(url) {
                    QuickLookPreview(url: url)
                        .ignoresSafeArea(edges: .bottom)
                        .accessibilityIdentifier("external.quickLook")
                } else {
                    ContentUnavailableView {
                        Label("无法在应用内预览", systemImage: "doc.questionmark")
                    } description: {
                        Text("可以分享给其他应用打开。")
                    } actions: {
                        ShareLink(item: url) {
                            Label("分享", systemImage: "square.and.arrow.up")
                        }
                        .buttonStyle(.glassProminent)
                        .accessibilityIdentifier("external.share")
                    }
                }
            case let .failed(message):
                ViewerStatusView(status: .failed(message)) {
                    attempt += 1
                }
            }
        }
        .viewerToolbar(chrome, title: file.name)
        .toolbar {
            if case let .ready(url) = phase, QuickLookPreview.canPreview(url) {
                ToolbarItem(placement: .bottomBar) {
                    ShareLink(item: url)
                }
            }
        }
        .task(id: attempt) { await run() }
    }

    private func run() async {
        phase = .preparing
        progress = nil
        let linked = CoreGraph.shared.files(accountId: accountId).resolveLinkedUri(file: file)
        // 预览总是针对当前账户（不依赖全屏呈现里的环境对象）
        let siteUrl = CoreGraph.shared.accounts().activeAccount.value?.siteUrl ?? ""
        switch await Self.downloadAddress(accountId: accountId, uri: linked, version: version, siteUrl: siteUrl) {
        case let .failure(failure):
            phase = .failed(failure.message)
        case let .success(address):
            phase = .downloading
            var destination: URL?
            do {
                let target = try RemoteFile.cacheURL(fileName: file.name)
                destination = target
                try await ExternalDownload.download(from: address, to: target) { progress = $0 }
                phase = .ready(target)
            } catch {
                // 本次新建的缓存目录（失败或关闭预览时不留下文件）
                if let destination { try? FileManager.default.removeItem(at: destination.deletingLastPathComponent()) }
                if !Task.isCancelled {
                    phase = .failed(String(localized: "下载失败，请检查网络后重试"))
                }
            }
        }
    }

    /// `POST /file/url {download: true}`；站内相对地址补全为站点地址
    private nonisolated static func downloadAddress(
        accountId: String,
        uri: String,
        version: String?,
        siteUrl: String
    ) async -> Result<URL, ViewerCatalog.LoadFailure> {
        do {
            let response = try await CoreGraph.shared.files(accountId: accountId).fileUrls(
                uris: [uri],
                download: true,
                entity: version,
                usePrimarySiteUrl: false,
                archive: false,
                contextHint: nil
            )
            guard let raw = response.urls.first?.url, !raw.isEmpty else {
                return .failure(ViewerCatalog.LoadFailure(message: String(localized: "请求的内容不存在")))
            }
            let absolute = raw.hasPrefix("/") ? (siteUrl.hasSuffix("/") ? String(siteUrl.dropLast()) : siteUrl) + raw : raw
            guard let url = URL(string: absolute) else {
                return .failure(ViewerCatalog.LoadFailure(message: String(localized: "请求的内容不存在")))
            }
            return .success(url)
        } catch {
            return .failure(ViewerCatalog.LoadFailure(message: FileErrors.message(error)))
        }
    }
}
