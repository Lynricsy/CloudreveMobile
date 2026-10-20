import AVFoundation
import Observation

/// 视频播放状态：持有 `AVPlayer`，发布播放进度（字幕定位用）。
/// 签名地址刷新（VM 在过期前 60 秒换新地址）时替换播放项并回到原进度，避免旧地址过期后断流。
@MainActor
@Observable
final class VideoPlayback {
    let player = AVPlayer()
    /// 当前播放位置（秒），约每 0.2 秒更新
    private(set) var currentTime: Double = 0

    @ObservationIgnored private var currentURL: String?
    @ObservationIgnored private var timeObserver: Any?

    init() {
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.2, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            // 观察者在主队列回调
            MainActor.assumeIsolated {
                self?.currentTime = time.seconds.isFinite ? time.seconds : 0
            }
        }
    }

    /// 播放该地址；地址变化（刷新）时保持进度与播放状态
    func load(_ url: String) {
        guard url != currentURL, let address = URL(string: url) else { return }
        let resumeAt = currentURL == nil ? nil : player.currentTime()
        let wasPlaying = player.timeControlStatus != .paused
        currentURL = url
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        player.replaceCurrentItem(with: AVPlayerItem(url: address))
        if let resumeAt {
            player.seek(to: resumeAt, toleranceBefore: .zero, toleranceAfter: .zero)
        }
        if resumeAt == nil || wasPlaying {
            player.play()
        }
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
    }

    isolated deinit {
        player.pause()
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
    }
}
