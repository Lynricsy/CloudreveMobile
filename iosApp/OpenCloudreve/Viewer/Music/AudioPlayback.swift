import AVFoundation
import MediaPlayer
import Observation
import OpenCloudreveCore
import UIKit

/// 全局音乐播放（离开预览页继续播放）：持有 `ViewerAudioViewModel`（播放列表 = 同目录音频，VM 预取当前与下一首的签名地址），
/// 用 `AVQueuePlayer` 排队“当前 + 下一首”，并接入锁屏/控制中心（`MPNowPlayingInfoCenter`、`MPRemoteCommandCenter`）。
@MainActor
@Observable
final class AudioPlayback {
    static let shared = AudioPlayback()

    /// 播放列表状态；nil 表示没有在播放
    private(set) var state: ViewerAudioState?
    private(set) var isPlaying = false
    /// 当前曲目进度与时长（秒）
    private(set) var elapsed: Double = 0
    private(set) var duration: Double = 0
    /// 当前曲目封面（`/file/thumb`）
    private(set) var artwork: UIImage?

    var isActive: Bool { state != nil }

    @ObservationIgnored private var viewModel: ViewerAudioViewModel?
    /// 当前播放列表所属的账户与版本
    @ObservationIgnored private var identity: AudioQueuePolicy.Identity?
    @ObservationIgnored private var stateTask: Task<Void, Never>?
    @ObservationIgnored private let player = AVQueuePlayer()
    /// 已排队的播放项 → 曲目路径与地址
    @ObservationIgnored private var queued: [ObjectIdentifier: AudioQueuePolicy.Track] = [:]
    @ObservationIgnored private var artworkPath: String?
    @ObservationIgnored private var timeObserver: Any?
    @ObservationIgnored private var endObserver: (any NSObjectProtocol)?

    private init() {
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] _ in
            // 观察者在主队列回调
            MainActor.assumeIsolated { self?.tick() }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let item = note.object as? AVPlayerItem else { return }
            let id = ObjectIdentifier(item)
            MainActor.assumeIsolated { self?.itemDidFinish(id) }
        }
        Self.registerRemoteCommands(for: self)
    }

    // MARK: - 播放列表

    /// 从预览页打开：同一账户、同一版本的播放列表内的曲目直接切换，否则以该文件所在目录新建播放列表
    func open(params: ViewerParams, path: String) {
        let requested = AudioQueuePolicy.Identity(accountId: params.accountId, version: params.version)
        if let state, let index = AudioQueuePolicy.reusableIndex(
            current: identity,
            requested: requested,
            trackPaths: state.tracks.map(\.file.path),
            path: path
        ) {
            if state.current?.file.path != path { send(ViewerAudioIntentPlay(index: Int32(index))) }
            return
        }
        stop()
        identity = requested
        let viewModel = CoreGraph.shared.viewerAudioViewModel(params: params)
        self.viewModel = viewModel
        stateTask = Task { [weak self, viewModel] in
            for await next in viewModel.state {
                guard let self else { return }
                self.apply(next)
            }
        }
        apply(viewModel.state.value)
    }

    func select(_ index: Int) {
        send(ViewerAudioIntentPlay(index: Int32(index)))
    }

    func retry() {
        send(ViewerAudioIntentRetry.shared)
    }

    // MARK: - 播放控制

    func togglePlay() {
        if isPlaying { pause() } else { play() }
    }

    func play() {
        guard let state else { return }
        if player.currentItem == nil {
            // 播放列表已结束：从当前曲目重新开始
            queued.removeAll()
            apply(state)
            return
        }
        activateSession()
        player.play()
        isPlaying = true
        updateNowPlaying()
    }

    func pause() {
        player.pause()
        isPlaying = false
        updateNowPlaying()
    }

    func next() {
        guard state?.hasNext == true else { return }
        send(ViewerAudioIntentNext.shared)
    }

    /// 播放超过 3 秒时回到开头，否则上一首
    func previous() {
        guard let state else { return }
        if elapsed > 3 || !state.hasPrevious {
            seek(to: 0)
        } else {
            send(ViewerAudioIntentPrevious.shared)
        }
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        elapsed = seconds
        updateNowPlaying()
    }

    /// 停止并清空播放列表
    func stop() {
        stateTask?.cancel()
        stateTask = nil
        viewModel?.dispose()
        viewModel = nil
        identity = nil
        state = nil
        player.pause()
        player.removeAllItems()
        queued.removeAll()
        isPlaying = false
        elapsed = 0
        duration = 0
        artwork = nil
        artworkPath = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - 状态同步

    private func send(_ intent: any ViewerAudioIntent) {
        guard let viewModel else { return }
        viewModel.on(intent: intent)
        apply(viewModel.state.value)
    }

    private var playingTrack: AudioQueuePolicy.Track? {
        player.currentItem.flatMap { queued[ObjectIdentifier($0)] }
    }

    private func apply(_ next: ViewerAudioState) {
        state = next
        guard let current = next.current else { return }
        let path = current.file.path
        let address = current.url.flatMap { resolved in URL(string: resolved.url).map { (resolved.url, $0) } }
        switch AudioQueuePolicy.currentItemAction(playing: playingTrack, path: path, url: address?.0) {
        case .keep:
            break
        case .waitForAddress:
            player.pause()
            return
        case .start:
            guard case let (url, location)? = address else { return }
            player.removeAllItems()
            queued.removeAll()
            enqueue(path: path, url: url, address: location)
            elapsed = 0
            duration = 0
            activateSession()
            player.play()
            isPlaying = true
        case .replace:
            // VM 在旧地址过期前刷新了当前曲目：换成新地址的播放项，接着原进度，保持播放/暂停
            guard case let (url, location)? = address else { return }
            let position = player.currentTime()
            let wasPlaying = player.timeControlStatus != .paused
            player.removeAllItems()
            queued.removeAll()
            enqueue(path: path, url: url, address: location)
            player.seek(to: position, toleranceBefore: .zero, toleranceAfter: .zero)
            if wasPlaying { player.play() }
        }
        queueNext(of: next)
        loadArtwork(path: path, cover: current.coverUrl)
        updateNowPlaying()
    }

    /// 保证队列中当前曲目之后是播放列表的下一首（地址刷新时替换）
    private func queueNext(of state: ViewerAudioState) {
        guard state.hasNext else { return }
        let next = state.tracks[Int(state.index) + 1]
        guard let url = next.url?.url, let address = URL(string: url) else { return }
        let items = player.items()
        guard !items.isEmpty else { return }
        for item in items.dropFirst() {
            let entry = queued[ObjectIdentifier(item)]
            if entry?.path == next.file.path && entry?.url == url { return }
            player.remove(item)
            queued[ObjectIdentifier(item)] = nil
        }
        enqueue(path: next.file.path, url: url, address: address)
    }

    private func enqueue(path: String, url: String, address: URL) {
        let item = AVPlayerItem(url: address)
        queued[ObjectIdentifier(item)] = AudioQueuePolicy.Track(path: path, url: url)
        player.insert(item, after: nil)
    }

    private func itemDidFinish(_ id: ObjectIdentifier) {
        guard let finished = queued.removeValue(forKey: id), let state else { return }
        guard finished.path == state.current?.file.path else { return }
        if state.hasNext {
            send(ViewerAudioIntentNext.shared)
        } else {
            isPlaying = false
            elapsed = 0
            updateNowPlaying()
        }
    }

    private func tick() {
        let time = player.currentTime().seconds
        elapsed = time.isFinite ? time : 0
        let total = player.currentItem?.duration.seconds ?? 0
        let known = total.isFinite ? total : 0
        let playing = player.timeControlStatus != .paused
        if known != duration || playing != isPlaying {
            duration = known
            isPlaying = playing
            updateNowPlaying()
        }
    }

    private func activateSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
        try? session.setActive(true)
    }

    // MARK: - 锁屏与控制中心

    private func loadArtwork(path: String, cover: String?) {
        guard artworkPath != path else { return }
        artworkPath = path
        artwork = nil
        guard let cover, let url = URL(string: cover) else { return }
        Task { [weak self] in
            let image = await RemoteFile.image(from: url)
            guard let self, self.artworkPath == path else { return }
            self.artwork = image
            self.updateNowPlaying()
        }
    }

    private func updateNowPlaying() {
        guard let current = state?.current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: current.title,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let artist = current.artist { info[MPMediaItemPropertyArtist] = artist }
        if let album = current.album { info[MPMediaItemPropertyAlbumTitle] = album }
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        if let artwork { info[MPMediaItemPropertyArtwork] = Self.makeArtwork(artwork) }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// 在非隔离上下文创建：系统可能在后台队列调用取图回调
    private nonisolated static func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }

    /// 在非隔离上下文注册：回调里再切回主线程
    private nonisolated static func registerRemoteCommands(for playback: AudioPlayback) {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { _ in
            Task { @MainActor in playback.play() }
            return .success
        }
        center.pauseCommand.addTarget { _ in
            Task { @MainActor in playback.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { _ in
            Task { @MainActor in playback.togglePlay() }
            return .success
        }
        center.nextTrackCommand.addTarget { _ in
            Task { @MainActor in playback.next() }
            return .success
        }
        center.previousTrackCommand.addTarget { _ in
            Task { @MainActor in playback.previous() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let position = event.positionTime
            Task { @MainActor in playback.seek(to: position) }
            return .success
        }
    }
}
