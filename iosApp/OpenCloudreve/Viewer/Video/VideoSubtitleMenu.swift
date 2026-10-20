import SwiftUI

/// 字幕选择：关闭 / 同目录字幕文件
struct VideoSubtitleMenu: View {
    let choices: [VideoSubtitleChoice]
    let loading: Bool
    /// nil = 关闭字幕
    let onSelect: (String?) -> Void

    var body: some View {
        Menu {
            Button {
                onSelect(nil)
            } label: {
                if choices.contains(where: \.selected) {
                    Text("关闭字幕")
                } else {
                    Label("关闭字幕", systemImage: "checkmark")
                }
            }
            ForEach(choices) { choice in
                Button {
                    onSelect(choice.path)
                } label: {
                    if choice.selected {
                        Label(choice.name, systemImage: "checkmark")
                    } else {
                        Text(verbatim: choice.name)
                    }
                }
            }
        } label: {
            Label("字幕", systemImage: loading ? "hourglass" : "captions.bubble")
        }
        .disabled(choices.isEmpty)
        .accessibilityIdentifier("video.subtitles")
    }
}
