import SwiftUI

/// 叠加在画面上的外挂字幕（单独的视图：只有它随播放进度刷新）
struct SubtitleOverlay: View {
    let playback: VideoPlayback
    let track: SubtitleTrack?

    var body: some View {
        if let text = track?.text(at: playback.currentTime) {
            Text(verbatim: text)
                .font(.headline)
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(Color.black.opacity(0.6), in: .rect(cornerRadius: 6))
                .accessibilityIdentifier("video.subtitle")
        }
    }
}
