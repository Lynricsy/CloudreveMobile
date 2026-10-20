import SwiftUI

/// 主界面底部附件栏中的“正在播放”：点按打开播放面板，右侧播放/暂停与停止
struct NowPlayingAccessoryContent: View {
    let playback: AudioPlayback

    @State private var isPlayerPresented = false

    var body: some View {
        HStack(spacing: 12) {
            Button {
                isPlayerPresented = true
            } label: {
                HStack(spacing: 10) {
                    MusicArtworkView(image: playback.artwork, size: 28)
                    Text(verbatim: playback.state?.current?.title ?? "")
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("正在播放"))
            .accessibilityIdentifier("nowPlaying.open")
            Button(String(localized: playback.isPlaying ? "暂停" : "播放"), systemImage: playback.isPlaying ? "pause.fill" : "play.fill") {
                playback.togglePlay()
            }
            .labelStyle(.iconOnly)
            .accessibilityIdentifier("nowPlaying.playPause")
            Button("停止播放", systemImage: "xmark") {
                playback.stop()
            }
            .labelStyle(.iconOnly)
            .accessibilityIdentifier("nowPlaying.stop")
        }
        .padding(.horizontal, 16)
        .sheet(isPresented: $isPlayerPresented) {
            NowPlayingSheet(playback: playback)
        }
    }
}
