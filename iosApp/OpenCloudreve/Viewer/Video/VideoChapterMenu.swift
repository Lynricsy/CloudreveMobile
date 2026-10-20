import SwiftUI

/// 章节跳转
struct VideoChapterMenu: View {
    let chapters: [VideoChapterItem]
    let onSeek: (Double) -> Void

    var body: some View {
        Menu {
            ForEach(chapters) { chapter in
                Button {
                    onSeek(chapter.start)
                } label: {
                    Text(verbatim: "\(chapter.startText)  \(chapter.title)")
                }
            }
        } label: {
            Label("章节", systemImage: "list.bullet.rectangle")
        }
        .accessibilityIdentifier("video.chapters")
    }
}
