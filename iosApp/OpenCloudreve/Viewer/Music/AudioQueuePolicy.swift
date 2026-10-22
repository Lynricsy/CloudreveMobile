/// 全局音乐播放（`AudioPlayback`）的两项判断：打开预览时能否复用现有播放列表、当前曲目的播放项要不要换
enum AudioQueuePolicy {
    /// 播放会话身份：账户 + 指定的版本实体（nil 为当前版本）
    struct Identity: Equatable, Sendable {
        let accountId: String
        let version: String?
    }

    /// 已排队的播放项对应的曲目路径与签名地址
    struct Track: Equatable, Sendable {
        let path: String
        let url: String
    }

    enum CurrentItemAction: Equatable, Sendable {
        /// 正在播放的就是它，地址也没变
        case keep
        /// 地址还在获取：暂停等下一次状态
        case waitForAddress
        /// 换了曲目：从头播放
        case start
        /// 同一曲目的签名地址已刷新：换成新地址的播放项，保持进度与播放/暂停状态
        case replace
    }

    /// 同一身份（账户、版本都相同）且路径在当前播放列表中时返回其位置；否则需要按新参数重建播放列表
    static func reusableIndex(current: Identity?, requested: Identity, trackPaths: [String], path: String) -> Int? {
        guard current == requested else { return nil }
        return trackPaths.firstIndex(of: path)
    }

    /// 比较正在播放的项 [playing] 与 VM 当前曲目（[path]，可用地址 [url]）
    static func currentItemAction(playing: Track?, path: String, url: String?) -> CurrentItemAction {
        guard let url else { return playing?.path == path ? .keep : .waitForAddress }
        guard let playing, playing.path == path else { return .start }
        return playing.url == url ? .keep : .replace
    }
}
