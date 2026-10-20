import Foundation

/// 已解析的字幕：按开始时间排序，二分定位某一时刻正在显示的字幕（重叠时按开始先后合并为多行）
struct SubtitleTrack: Sendable {
    let cues: [SubtitleCue]
    /// 最长一条字幕的时长：向前回溯重叠字幕时的上限
    private let maxDuration: Double

    init(cues: [SubtitleCue]) {
        self.cues = cues.sorted { $0.start < $1.start }
        maxDuration = cues.map { $0.end - $0.start }.max() ?? 0
    }

    /// `time`（秒）时应显示的文本；没有则为 nil。区间为左闭右开 `[start, end)`
    func text(at time: Double) -> String? {
        // 第一条 start > time 的位置
        var low = 0
        var high = cues.count
        while low < high {
            let mid = (low + high) / 2
            if cues[mid].start <= time {
                low = mid + 1
            } else {
                high = mid
            }
        }
        var active: [String] = []
        var index = low - 1
        while index >= 0, time - cues[index].start <= maxDuration {
            if time < cues[index].end { active.append(cues[index].text) }
            index -= 1
        }
        return active.isEmpty ? nil : active.reversed().joined(separator: "\n")
    }
}
