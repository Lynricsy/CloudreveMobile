import SwiftUI
import OpenCloudreveCore

/// 文本 / Markdown 预览与编辑（monaco、markdown 预览器）
struct TextViewerScreen: View {
    let request: ViewerRequest
    let chrome: ViewerChrome

    var body: some View {
        ViewModelScope({ CoreGraph.shared.viewerTextViewModel(params: request.params) }) { host in
            TextViewerContent(host: host, chrome: chrome)
        }
    }
}
