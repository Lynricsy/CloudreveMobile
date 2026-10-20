import SwiftUI

/// 封面 + 曲目信息 + 进度条 + 上一首/播放/下一首
struct MusicNowPlayingHeader: View {
    let playback: AudioPlayback

    /// 拖动进度条时的临时位置（松手后才跳转）
    @State private var scrubbing: Double?

    var body: some View {
        let current = playback.state?.current
        VStack(spacing: 16) {
            MusicArtworkView(image: playback.artwork, size: 220)
            VStack(spacing: 4) {
                Text(verbatim: current?.title ?? "")
                    .font(.title3.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("music.title")
                if let subtitle = [current?.artist, current?.album].compactMap({ $0 }).joined(separator: " · ").nilIfEmpty {
                    Text(verbatim: subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            if let error = current?.error {
                VStack(spacing: 8) {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { playback.retry() }
                        .buttonStyle(.glass)
                }
            }
            VStack(spacing: 4) {
                Slider(value: position, in: 0...max(playback.duration, 1)) { editing in
                    if !editing, let target = scrubbing {
                        playback.seek(to: target)
                        scrubbing = nil
                    }
                }
                .disabled(playback.duration <= 0)
                .accessibilityLabel(Text("播放进度"))
                HStack {
                    Text(verbatim: Self.clock(scrubbing ?? playback.elapsed))
                    Spacer()
                    Text(verbatim: Self.clock(playback.duration))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
            MusicControls(playback: playback)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    private var position: Binding<Double> {
        Binding(
            get: { scrubbing ?? playback.elapsed },
            set: { scrubbing = $0 }
        )
    }

    static func clock(_ seconds: Double) -> String {
        let value = seconds.isFinite ? max(seconds, 0) : 0
        return Duration.seconds(value).formatted(.time(pattern: value >= 3600 ? .hourMinuteSecond : .minuteSecond))
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
