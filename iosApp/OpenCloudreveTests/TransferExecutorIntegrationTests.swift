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
        let step = try await CoreGraph.shared.loginFlow().login(
            siteUrl: Self.siteUrl,
            email: Self.email,
            password: Self.password,
            captcha: nil
        )
        let done = try #require(step as? LoginStepDone)
        let accountId = done.account.id

        let executor = BackgroundTransferExecutor(configurations: [URLSessionConfiguration.ephemeral])
        CoreGraph.shared.installExecutor(executor: executor)
        var failure: Error?
        do {
            try await exercise(accountId: accountId)
        } catch {
            failure = error
        }
        executor.invalidate()
        if let appExecutor = BackgroundTransferExecutor.current {
            CoreGraph.shared.installExecutor(executor: appExecutor)
        }
        // 恢复未登录状态（之后的 UI 测试从服务器地址页开始）
        _ = try? await CoreGraph.shared.accounts().signOut(id: accountId)
        if let failure { throw failure }
    }

    private func exercise(accountId: String) async throws {
        // 25 MiB + 1 MiB：两片（第二片 1 MiB），内容为可校验的字节序列
        let size = (25 << 20) + (1 << 20)
        var original = Data(count: size)
        original.withUnsafeMutableBytes { buffer in
            for index in 0..<size { buffer[index] = UInt8(truncatingIfNeeded: index &* 31 &+ index >> 12) }
        }
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
        let uploaded = try await Self.waitUntilFinished(uploadId)
        #expect(uploaded.state == .completed, "上传失败：\(uploaded.errorCode?.int32Value ?? 0) \(uploaded.errorMessage ?? "")")
        #expect(uploaded.chunkCount == 2)
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
        let downloaded = try await Self.waitUntilFinished(download.id)
        #expect(downloaded.state == .completed, "下载失败：\(downloaded.errorMessage ?? "")")
        #expect(try Data(contentsOf: destination) == original)

        _ = try? await files.delete(uris: [remote.path], unlink: false, skipSoftDelete: true)
        _ = try? await manager.remove(taskId: uploadId)
        _ = try? await manager.remove(taskId: download.id)
    }

    /// 轮询任务表直到进入完成/失败/取消
    private static func waitUntilFinished(_ taskId: String) async throws -> TransferItem {
        let repository = CoreGraph.shared.transferRepository()
        while true {
            let item = try #require(try await repository.task(id: taskId))
            switch item.state {
            case .completed, .failed, .canceled: return item
            default: try await Task.sleep(for: .milliseconds(250))
            }
        }
    }
}
