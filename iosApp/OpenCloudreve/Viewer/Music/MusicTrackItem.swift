import Foundation
import OpenCloudreveCore

/// 播放列表中的一首（从 `AudioTrack` 摘出渲染所需字段）
struct MusicTrackItem: Identifiable, Hashable {
    let index: Int
    let path: String
    let name: String
    let title: String
    let artist: String?
    let isCurrent: Bool
    let errorMessage: String?

    var id: String { path }

    static func items(of state: ViewerAudioState) -> [MusicTrackItem] {
        state.tracks.enumerated().map { offset, track in
            MusicTrackItem(
                index: offset,
                path: track.file.path,
                name: track.file.name,
                title: track.title,
                artist: track.artist,
                isCurrent: offset == Int(state.index),
                errorMessage: track.error.map { ErrorText.message($0) }
            )
        }
    }
}
