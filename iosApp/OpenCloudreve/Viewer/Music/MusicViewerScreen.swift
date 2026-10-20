import SwiftUI

/// 音乐预览：交给全局 `AudioPlayback`（关闭页面后继续播放，主界面底部附件栏可控制）
struct MusicViewerScreen: View {
    let request: ViewerRequest
    let chrome: ViewerChrome

    /// 只在首次出现时开始播放（之后用户可能已切到别的曲目）
    @State private var started = false

    var body: some View {
        MusicPlayerView(playback: AudioPlayback.shared)
            .viewerToolbar(chrome, title: request.file.name)
            .onAppear {
                guard !started else { return }
                started = true
                AudioPlayback.shared.open(params: request.params, path: request.file.path)
            }
    }
}
