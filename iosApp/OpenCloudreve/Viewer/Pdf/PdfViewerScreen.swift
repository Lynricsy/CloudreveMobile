import SwiftUI
import OpenCloudreveCore

/// PDF 预览（PDFKit）
struct PdfViewerScreen: View {
    let request: ViewerRequest
    let chrome: ViewerChrome

    var body: some View {
        ViewModelScope({ CoreGraph.shared.viewerPdfViewModel(params: request.params) }) { host in
            PdfViewerContent(host: host, chrome: chrome)
        }
    }
}
