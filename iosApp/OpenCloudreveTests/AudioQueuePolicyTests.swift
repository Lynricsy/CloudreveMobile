import Testing
@testable import OpenCloudreve

/// 全局音乐播放：复用播放列表必须账户与版本都相同；同一曲目的签名地址刷新后要换播放项
struct AudioQueuePolicyTests {
    private let paths = ["cloudreve://my/song-a.mp3", "cloudreve://my/song-b.mp3"]

    @Test func samePathInSameAccountAndVersionReusesPlaylist() {
        let identity = AudioQueuePolicy.Identity(accountId: "1@a.example", version: nil)
        #expect(AudioQueuePolicy.reusableIndex(current: identity, requested: identity, trackPaths: paths, path: paths[1]) == 1)
    }

    @Test func samePathInAnotherAccountBuildsNewPlaylist() {
        let accountA = AudioQueuePolicy.Identity(accountId: "1@a.example", version: nil)
        let accountB = AudioQueuePolicy.Identity(accountId: "7@b.example", version: nil)
        #expect(AudioQueuePolicy.reusableIndex(current: accountA, requested: accountB, trackPaths: paths, path: paths[0]) == nil)
    }

    @Test func historicVersionOfPlayingFileBuildsNewPlaylist() {
        let current = AudioQueuePolicy.Identity(accountId: "1@a.example", version: nil)
        let historic = AudioQueuePolicy.Identity(accountId: "1@a.example", version: "ent-old")
        #expect(AudioQueuePolicy.reusableIndex(current: current, requested: historic, trackPaths: paths, path: paths[0]) == nil)
        #expect(AudioQueuePolicy.reusableIndex(current: historic, requested: current, trackPaths: paths, path: paths[0]) == nil)
    }

    @Test func nothingPlayingBuildsNewPlaylist() {
        let identity = AudioQueuePolicy.Identity(accountId: "1@a.example", version: nil)
        #expect(AudioQueuePolicy.reusableIndex(current: nil, requested: identity, trackPaths: paths, path: paths[0]) == nil)
    }

    @Test func refreshedUrlOfCurrentTrackReplacesItem() {
        let playing = AudioQueuePolicy.Track(path: paths[0], url: "https://s.example/a.mp3?sign=old")
        #expect(AudioQueuePolicy.currentItemAction(playing: playing, path: paths[0], url: "https://s.example/a.mp3?sign=new") == .replace)
        #expect(AudioQueuePolicy.currentItemAction(playing: playing, path: paths[0], url: "https://s.example/a.mp3?sign=old") == .keep)
    }

    @Test func otherTrackStartsAndMissingAddressWaits() {
        let playing = AudioQueuePolicy.Track(path: paths[0], url: "https://s.example/a.mp3")
        #expect(AudioQueuePolicy.currentItemAction(playing: playing, path: paths[1], url: "https://s.example/b.mp3") == .start)
        #expect(AudioQueuePolicy.currentItemAction(playing: nil, path: paths[1], url: "https://s.example/b.mp3") == .start)
        #expect(AudioQueuePolicy.currentItemAction(playing: playing, path: paths[1], url: nil) == .waitForAddress)
        // 正在播放的曲目暂时没有地址（刷新中）：继续播放已有项
        #expect(AudioQueuePolicy.currentItemAction(playing: playing, path: paths[0], url: nil) == .keep)
    }
}
