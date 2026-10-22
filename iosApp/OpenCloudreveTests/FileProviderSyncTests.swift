import Foundation
import OpenCloudreveCore
import Synchronization
import Testing
@testable import OpenCloudreve

/// 主 App 操作后系统“文件”的刷新：先更新受影响目录的缓存再发信号；工作集同一目录只取最新缓存
struct FileProviderSyncTests {
    @MainActor
    @Test(.timeLimit(.minutes(1)))
    func signalIsSentOnlyAfterSlowListingRefreshFinishes() async {
        let events = Mutex<[String]>([])
        let gate = AsyncStream<Void>.makeStream()
        let syncing = Task {
            await FileProviderSync.refreshThenSignal(directories: ["cloudreve://my/a", "cloudreve://my/b"]) { directory in
                if directory.hasSuffix("/a") {
                    // 列表响应很慢
                    for await _ in gate.stream { break }
                }
                events.withLock { $0.append("refreshed " + directory) }
            } signal: {
                events.withLock { $0.append("signal") }
            }
        }
        try? await Task.sleep(for: .milliseconds(300))
        #expect(events.withLock { $0 }.isEmpty, "缓存还没更新就发了信号")
        gate.continuation.yield()
        await syncing.value
        #expect(events.withLock { $0 } == ["refreshed cloudreve://my/a", "refreshed cloudreve://my/b", "signal"])
    }

    @Test func moveAffectsSourceParentsAndDestination() {
        let move = FileOperationMove(uris: ["cloudreve://my/docs/a.txt", "cloudreve://my/docs/b.txt"], dst: "cloudreve://my/archive")
        #expect(FileChangeScope.affectedDirectories(of: move) == ["cloudreve://my/docs", "cloudreve://my/archive"])
    }

    @Test func softDeleteAlsoAffectsTrash() {
        let delete = FileOperationDelete(uris: ["cloudreve://my/docs/a.txt"], unlink: false, skipSoftDelete: false)
        #expect(FileChangeScope.affectedDirectories(of: delete) == ["cloudreve://my/docs", "cloudreve://trash"])
        let permanent = FileOperationDelete(uris: ["cloudreve://my/docs/a.txt"], unlink: false, skipSoftDelete: true)
        #expect(FileChangeScope.affectedDirectories(of: permanent) == ["cloudreve://my/docs"])
    }

    @Test func renameAndCreateAffectTheirFolder() {
        #expect(FileChangeScope.affectedDirectories(of: FileOperationRename(uri: "cloudreve://my/docs/a.txt", newName: "b.txt")) == ["cloudreve://my/docs"])
        #expect(FileChangeScope.affectedDirectories(of: FileOperationCreateFolder(parentUri: "cloudreve://my", name: "新")) == ["cloudreve://my"])
        #expect(FileChangeScope.affectedDirectories(of: FileOperationUnpin(uri: "cloudreve://my/docs")).isEmpty)
    }

    @Test func workingSetKeepsOnlyNewestListingPerDirectory() {
        // 新到旧：同一目录的两份缓存（URI 写法不同）只保留较新的一份
        let listings = [
            (dir: "cloudreve://my/%E6%96%87%E6%A1%A3", files: ["b.txt"]),
            (dir: "cloudreve://my/other", files: ["x"]),
            (dir: "cloudreve://my/文档", files: ["a.txt", "b.txt"]),
        ]
        let kept = FileProviderListings.newestPerDirectory(listings, normalize: { $0.removingPercentEncoding ?? $0 }) { $0.dir }
        #expect(kept.map(\.files) == [["b.txt"], ["x"]])
    }
}
