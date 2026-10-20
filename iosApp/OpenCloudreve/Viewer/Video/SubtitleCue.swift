import Foundation

/// 一条字幕（秒）
struct SubtitleCue: Equatable, Sendable {
    let start: Double
    let end: Double
    let text: String
}
