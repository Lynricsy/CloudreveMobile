import SwiftUI

/// 上一首 / 播放暂停 / 下一首
struct MusicControls: View {
    let playback: AudioPlayback

    var body: some View {
        HStack(spacing: 40) {
            Button("上一首", systemImage: "backward.fill") { playback.previous() }
                .accessibilityIdentifier("music.previous")
            Button(String(localized: playback.isPlaying ? "暂停" : "播放"), systemImage: playback.isPlaying ? "pause.fill" : "play.fill") {
                playback.togglePlay()
            }
            .font(.largeTitle)
            .accessibilityIdentifier("music.playPause")
            Button("下一首", systemImage: "forward.fill") { playback.next() }
                .disabled(playback.state?.hasNext != true)
                .accessibilityIdentifier("music.next")
        }
        .labelStyle(.iconOnly)
        .font(.title)
        .buttonStyle(.borderless)
        .foregroundStyle(.primary)
    }
}
