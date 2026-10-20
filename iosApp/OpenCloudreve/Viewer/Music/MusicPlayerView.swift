import SwiftUI
import OpenCloudreveCore

/// 正在播放：封面、曲目信息、进度、播放控制与播放列表（预览页与“正在播放”面板共用）
struct MusicPlayerView: View {
    let playback: AudioPlayback

    var body: some View {
        List {
            Section {
                MusicNowPlayingHeader(playback: playback)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
            if let state = playback.state, !state.tracks.isEmpty {
                Section("播放列表") {
                    ForEach(MusicTrackItem.items(of: state)) { track in
                        Button {
                            playback.select(track.index)
                        } label: {
                            MusicTrackRow(track: track)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("music.track." + track.name)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .overlay {
            if playback.state?.loading == true && playback.state?.tracks.isEmpty == true {
                ProgressView()
            }
        }
    }
}
