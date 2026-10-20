import SwiftUI

/// 按预览器分派：原生实现的内置预览器（`BuiltinViewerId.NATIVE`）走对应页面，其余内置、wopi 与 custom 走网页预览
struct ViewerContent: View {
    let request: ViewerRequest
    let option: ViewerOption
    let chrome: ViewerChrome

    var body: some View {
        switch option.isNative ? option.id : "" {
        case "image":
            ImageViewerScreen(request: request, chrome: chrome)
        case "video":
            VideoViewerScreen(request: request, chrome: chrome)
        case "music":
            MusicViewerScreen(request: request, chrome: chrome)
        case "pdf":
            PdfViewerScreen(request: request, chrome: chrome)
        case "monaco", "markdown":
            TextViewerScreen(request: request, chrome: chrome)
        case "archive":
            ArchiveViewerScreen(request: request, chrome: chrome)
        default:
            WebViewerScreen(request: request, chrome: chrome)
        }
    }
}
