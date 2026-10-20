import Foundation
import OpenCloudreveCore

/// 章节（来自 `stream:chapter_*` 元数据）
struct VideoChapterItem: Identifiable, Hashable {
    let id: String
    let start: Double
    let title: String

    init(chapter: VideoChapter) {
        id = chapter.id
        start = chapter.startSeconds
        title = chapter.title
    }

    /// 开始时间 `m:ss` / `h:mm:ss`
    var startText: String {
        Duration.seconds(start).formatted(.time(pattern: start >= 3600 ? .hourMinuteSecond : .minuteSecond))
    }
}
