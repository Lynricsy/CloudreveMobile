import SwiftUI
import OpenCloudreveCore

/// 图片预览：左右滑动浏览同目录图片、双击/捏合缩放、EXIF 与位置信息、实况照片
struct ImageViewerScreen: View {
    let request: ViewerRequest
    let chrome: ViewerChrome

    var body: some View {
        ViewModelScope({ CoreGraph.shared.viewerImageViewModel(params: request.params) }) { host in
            ImageViewerContent(host: host, chrome: chrome, fallbackTitle: request.file.name)
        }
    }
}
