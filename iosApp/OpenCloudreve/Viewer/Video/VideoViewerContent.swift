import SwiftUI
import OpenCloudreveCore

struct VideoViewerContent: View {
    let host: ViewerVideoHost
    let chrome: ViewerChrome

    /// 首次出现时创建（`@State` 初始值表达式会在父视图每次重建时求值）
    @State private var playback: VideoPlayback?
    @State private var track: SubtitleTrack?

    var body: some View {
        let state = host.state
        ZStack {
            if let error = state.error {
                ViewerStatusView(status: .failed(ErrorText.message(error))) {
                    host.send(ViewerVideoIntentRetry.shared)
                }
            } else if let playback, state.url != nil {
                VideoPlayerView(player: playback.player)
                    .accessibilityIdentifier("video.player")
                    .overlay(alignment: .bottom) {
                        SubtitleOverlay(playback: playback, track: track)
                            .padding(.horizontal, 16)
                            .padding(.bottom, 88)
                            .allowsHitTesting(false)
                    }
            } else {
                ViewerStatusView(status: .loading)
            }
        }
        .background(Color.black, ignoresSafeAreaEdges: .all)
        .viewerToolbar(chrome, title: state.file.name)
        .toolbar {
            ToolbarItem(placement: .bottomBar) {
                VideoSubtitleMenu(
                    choices: VideoSubtitleChoice.choices(of: state),
                    loading: state.subtitleLoading
                ) { path in
                    host.send(ViewerVideoIntentSelectSubtitle(path: path))
                }
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            if !state.chapters.isEmpty {
                ToolbarItem(placement: .bottomBar) {
                    VideoChapterMenu(chapters: state.chapters.map(VideoChapterItem.init(chapter:))) { seconds in
                        playback?.seek(to: seconds)
                    }
                }
            }
        }
        .task(id: state.url?.url) {
            if playback == nil { playback = VideoPlayback() }
            if let url = host.state.url?.url { playback?.load(url) }
        }
        .task(id: SubtitleKey(path: state.selectedSubtitle?.file.path, text: state.subtitleText)) {
            let current = host.state
            guard let text = current.subtitleText, let format = current.selectedSubtitle?.format else {
                track = nil
                return
            }
            track = SubtitleTrack(cues: SubtitleParser.parse(text, format: format))
        }
    }
}

/// 字幕重新解析的触发键
private struct SubtitleKey: Equatable {
    let path: String?
    let text: String?
}
