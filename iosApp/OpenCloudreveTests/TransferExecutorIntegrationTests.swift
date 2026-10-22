import Foundation
import OpenCloudreveCore
import Testing
@testable import OpenCloudreve

/// 执行器端到端：对 CI 内的 OpenCloudreve 服务端（`http://127.0.0.1:5212`，测试账号由 workflow 注册）
/// 真实上传一个两片文件（默认本机存储策略分片 25 MiB），再下载回来逐字节比对。
///
/// 用前台会话（`.ephemeral`）构造执行器——与后台会话共用同一套 delegate 逻辑：
/// `ChunkMaterializer.materialize` → `uploadTask(fromFile:)` → `onChunkDone` → 补发下一片 → `finalize`；
/// 下载 `downloadTask` → 暂存 → 移到目标路径 → `onCompleted`。结束时退出登录（之后的 UI 测试从未登录状态开始）。
@Suite(.serialized)
struct TransferExecutorIntegrationTests {
    private static let siteUrl = "http://127.0.0.1:5212"
    private static let email = "user@example.com"
    private static let password = "User@12345"

    @Test(.timeLimit(.minutes(5)))
    func uploadsMultiChunkFileThenDownloadsItBack() async throws {
        try await Self.withExecutor(chunkDelay: nil) { accountId, _ in
            try await exercise(accountId: accountId)
        }
    }

    /// 回归：分片物化期间暂停，暂停前开始的发送不能再创建请求；继续后用新的运行重新发送
    @Test(.timeLimit(.minutes(3)))
    func pauseWhileChunkIsBeingPreparedSendsNothing() async throws {
        try await Self.withExecutor(chunkDelay: .seconds(3), recording: true) { accountId, _ in
            let name = "ios-pause-\(UUID().uuidString.prefix(8)).bin"
            let source = FileManager.default.temporaryDirectory.appending(path: name)
            try Data(repeating: 7, count: 64 << 10).write(to: source)
            defer { try? FileManager.default.removeItem(at: source) }
            let manager = CoreGraph.shared.transferManager()
            let items = try await manager.enqueueUploads(
                accountId: accountId,
                sources: [UploadSource(sourceRef: source.path(percentEncoded: false), name: name, size: 64 << 10, lastModified: nil, mimeType: nil)],
                targetDirUri: "cloudreve://my",
                policy: nil,
                overwrite: false
            )
            let taskId = try #require(items.first?.id)
            // 上传会话已建好、执行器正在“发送前等待”（3 秒）时暂停
            try await Self.waitForState(taskId, .running)
            try await manager.pause(taskId: taskId)
            try await Task.sleep(for: .seconds(5))
            let paused = try #require(try await CoreGraph.shared.transferRepository().task(id: taskId))
            #expect(paused.state == .paused)
            #expect(RecordingURLProtocol.requests(containing: "/file/upload/").isEmpty, "暂停后仍发出了分片请求")
            // 物化出的分片临时文件已删除
            let chunks = URL(filePath: CoreGraph.shared.platform().fileSystem.tempDir()).appending(path: "chunks")
            let leftovers = (try? FileManager.default.contentsOfDirectory(atPath: chunks.path(percentEncoded: false))) ?? []
            #expect(leftovers.allSatisfy { !$0.hasPrefix(taskId) })

            // 继续：新运行正常发送（记录协议让请求失败，只看是否发出）
            try await manager.resume(taskId: taskId)
            let deadline = ContinuousClock.now + .seconds(20)
            while RecordingURLProtocol.requests(containing: "/file/upload/").isEmpty && ContinuousClock.now < deadline {
                try await Task.sleep(for: .milliseconds(200))
            }
            #expect(!RecordingURLProtocol.requests(containing: "/file/upload/").isEmpty, "继续后没有发送分片")
            try await manager.remove(taskId: taskId)
            let remote = try? await CoreGraph.shared.files(accountId: accountId)
                .info(uri: "cloudreve://my/" + name, extended: false, folderSummary: false)
            if let remote {
                _ = try? await CoreGraph.shared.files(accountId: accountId).delete(uris: [remote.path], unlink: false, skipSoftDelete: true)
            }
        }
    }

    /// 登录测试账号、安装前台会话执行器后执行 [body]，结束时恢复主 App 的执行器并退出登录
    /// （之后的 UI 测试从服务器地址页开始）。[recording] 时执行器的请求经 `RecordingURLProtocol` 记录并以失败结束。
    private static func withExecutor(
        chunkDelay: Duration?,
        recording: Bool = false,
        _ body: (String, BackgroundTransferExecutor) async throws -> Void
    ) async throws {
        let step = try await CoreGraph.shared.loginFlow().login(
            siteUrl: siteUrl,
            email: email,
            password: password,
            captcha: nil
        )
        guard case let .done(done) = onEnum(of: step) else {
            Issue.record("测试账号登录未直接完成（需要两步验证？）")
            return
        }
        let accountId = done.account.id

        let configuration = URLSessionConfiguration.ephemeral
        if recording {
            RecordingURLProtocol.reset()
            configuration.protocolClasses = [RecordingURLProtocol.self]
        }
        let owners = FileManager.default.temporaryDirectory.appending(path: "owners-\(UUID().uuidString)")
        let executor = BackgroundTransferExecutor(
            configuration: configuration,
            ownership: TransferOwnership(directory: owners),
            chunkDelay: chunkDelay
        )
        CoreGraph.shared.installExecutor(executor: executor)
        var failure: Error?
        do {
            try await body(accountId, executor)
        } catch {
            failure = error
        }
        executor.invalidate()
        try? FileManager.default.removeItem(at: owners)
        if let appExecutor = BackgroundTransferExecutor.current {
            CoreGraph.shared.installExecutor(executor: appExecutor)
        }
        _ = try? await CoreGraph.shared.accounts().signOut(id: accountId)
        if let failure { throw failure }
    }

    private static func waitForState(_ taskId: String, _ expected: TransferState) async throws {
        let repository = CoreGraph.shared.transferRepository()
        let deadline = ContinuousClock.now + .seconds(30)
        while ContinuousClock.now < deadline {
            if try await repository.task(id: taskId)?.state == expected { return }
            try await Task.sleep(for: .milliseconds(50))
        }
        Issue.record("任务未进入 \(expected)")
    }

    private func exercise(accountId: String) async throws {
        // 25 MiB + 1 MiB：两片（第二片 1 MiB），内容为可校验的字节序列
        let size = (25 << 20) + (1 << 20)
        let original = Data((0..<size).lazy.map { UInt8(truncatingIfNeeded: $0 &* 31 &+ ($0 >> 12)) })
        let name = "ios-executor-\(UUID().uuidString.prefix(8)).bin"
        let source = FileManager.default.temporaryDirectory.appending(path: name)
        try original.write(to: source)
        defer { try? FileManager.default.removeItem(at: source) }

        let manager = CoreGraph.shared.transferManager()
        let items = try await manager.enqueueUploads(
            accountId: accountId,
            sources: [
                UploadSource(
                    sourceRef: source.path(percentEncoded: false),
                    name: name,
                    size: Int64(size),
                    lastModified: nil,
                    mimeType: "application/octet-stream"
                ),
            ],
            targetDirUri: "cloudreve://my",
            policy: nil,
            overwrite: false
        )
        let uploadId = try #require(items.first?.id)
        let (uploaded, chunkCount) = try await Self.waitUntilFinished(uploadId)
        #expect(uploaded.state == .completed, "上传失败：\(uploaded.errorCode?.int32Value ?? 0) \(uploaded.errorMessage ?? "")")
        // 会话结束后分片信息会被清空，这里看上传过程中观察到的分片数
        #expect(chunkCount == 2)
        #expect(uploaded.bytesDone == Int64(size))
        // 临时分片已删除
        let chunks = URL(filePath: CoreGraph.shared.platform().fileSystem.tempDir()).appending(path: "chunks")
        let leftovers = (try? FileManager.default.contentsOfDirectory(atPath: chunks.path(percentEncoded: false))) ?? []
        #expect(leftovers.allSatisfy { !$0.hasPrefix(uploadId) })

        let files = CoreGraph.shared.files(accountId: accountId)
        let remote = try await files.info(uri: "cloudreve://my/" + name, extended: false, folderSummary: false)
        #expect(remote.size == Int64(size))

        let destination = FileManager.default.temporaryDirectory.appending(path: "download-" + name)
        defer { try? FileManager.default.removeItem(at: destination) }
        let download = try await manager.enqueueDownload(
            accountId: accountId,
            fileUri: remote.path,
            displayName: name,
            size: Int64(size),
            destPath: destination.path(percentEncoded: false)
        )
        let (downloaded, _) = try await Self.waitUntilFinished(download.id)
        #expect(downloaded.state == .completed, "下载失败：\(downloaded.errorMessage ?? "")")
        #expect(try Data(contentsOf: destination) == original)

        _ = try? await files.delete(uris: [remote.path], unlink: false, skipSoftDelete: true)
        _ = try? await manager.remove(taskId: uploadId)
        _ = try? await manager.remove(taskId: download.id)
    }

    /// 轮询任务表直到进入完成/失败/取消；同时返回过程中观察到的最大分片数
    private static func waitUntilFinished(_ taskId: String) async throws -> (TransferItem, Int32) {
        let repository = CoreGraph.shared.transferRepository()
        var chunkCount: Int32 = 0
        while true {
            let item = try #require(try await repository.task(id: taskId))
            chunkCount = max(chunkCount, item.chunkCount)
            switch item.state {
            case .completed, .failed, .canceled: return (item, chunkCount)
            default: try await Task.sleep(for: .milliseconds(100))
            }
        }
    }
}
