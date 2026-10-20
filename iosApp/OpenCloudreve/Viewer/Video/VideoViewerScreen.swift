import SwiftUI
import OpenCloudreveCore

/// 视频预览：系统播放器 + 同目录外挂字幕（SRT/ASS/VTT，同名优先自动选中）+ 章节（`stream:chapter_*`）
struct VideoViewerScreen: View {
    let request: ViewerRequest
    let chrome: ViewerChrome

    var body: some View {
        ViewModelScope({ CoreGraph.shared.viewerVideoViewModel(params: request.params) }) { host in
            VideoViewerContent(host: host, chrome: chrome)
        }
    }
}
