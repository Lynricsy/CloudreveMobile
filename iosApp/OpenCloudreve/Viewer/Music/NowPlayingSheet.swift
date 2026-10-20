import SwiftUI

/// 从底部附件栏打开的播放面板
struct NowPlayingSheet: View {
    let playback: AudioPlayback
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            MusicPlayerView(playback: playback)
                .navigationTitle("正在播放")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { dismiss() }
                    }
                }
        }
        .onChange(of: playback.isActive) { _, active in
            if !active { dismiss() }
        }
    }
}
