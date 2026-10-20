import Foundation
import OpenCloudreveCore

/// 字幕菜单中的一项（同目录字幕文件）
struct VideoSubtitleChoice: Identifiable, Hashable {
    let path: String
    let name: String
    let selected: Bool

    var id: String { path }

    static func choices(of state: ViewerVideoState) -> [VideoSubtitleChoice] {
        let selectedPath = state.selectedSubtitle?.file.path
        return state.subtitles.map { option in
            VideoSubtitleChoice(
                path: option.file.path,
                name: option.file.name,
                selected: option.file.path == selectedPath
            )
        }
    }
}
