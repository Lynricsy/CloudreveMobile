import PDFKit
import SwiftUI
import OpenCloudreveCore

struct PdfViewerContent: View {
    let host: ViewerPdfHost
    let chrome: ViewerChrome

    @State private var document: PDFDocument?
    @State private var failed = false
    /// 下载失败后重试（地址不变时也重新下载）
    @State private var attempt = 0

    var body: some View {
        let state = host.state
        Group {
            if let error = state.error {
                ViewerStatusView(status: .failed(ErrorText.message(error))) {
                    host.send(ViewerPdfIntentRetry.shared)
                }
            } else if failed {
                ViewerStatusView(status: .failed(String(localized: "无法打开此 PDF 文件"))) {
                    failed = false
                    attempt += 1
                }
            } else if let document {
                PdfKitView(document: document)
                    .accessibilityIdentifier("pdf.view")
                    .ignoresSafeArea(edges: .bottom)
            } else {
                ViewerStatusView(status: .loading)
            }
        }
        .viewerToolbar(chrome, title: state.file.name)
        .task(id: "\(attempt)|\(state.url?.url ?? "")") {
            // 地址刷新不重新下载：已打开的文档在内存中
            guard document == nil, let raw = host.state.url?.url, let url = URL(string: raw) else { return }
            do {
                let data = try await RemoteFile.data(from: url)
                if let loaded = PDFDocument(data: data) {
                    document = loaded
                } else {
                    failed = true
                }
            } catch {
                if !Task.isCancelled { failed = true }
            }
        }
    }
}
