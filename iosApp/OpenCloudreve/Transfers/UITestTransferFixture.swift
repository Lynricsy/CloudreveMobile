#if DEBUG
import Foundation
import OpenCloudreveCore

/// UI 测试夹具（仅 Debug）：启动参数 `-OCRUITestLargeUpload <MB>` 时，在进入主界面后生成一个指定大小的稀疏文件
/// （App Group `Inbox/`）并上传到“我的文件”根目录，让传输列表与迷你栏截图能拍到进行中的上传。每次启动只执行一次。
enum UITestTransferFixture {
    static let argumentKey = "OCRUITestLargeUpload"
    static let fileName = "transfer-uitest.bin"

    @MainActor private static var done = false

    @MainActor
    static func runIfRequested(accountId: String) async {
        let megabytes = UserDefaults.standard.integer(forKey: argumentKey)
        guard megabytes > 0, !done else { return }
        done = true
        try? await enqueue(accountId: accountId, size: Int64(megabytes) << 20)
    }

    nonisolated private static func enqueue(accountId: String, size: Int64) async throws {
        let directory = try TransferInbox.makeBatchDirectory()
        let url = directory.appending(path: fileName)
        guard FileManager.default.createFile(atPath: url.path(percentEncoded: false), contents: nil) else { return }
        let handle = try FileHandle(forWritingTo: url)
        try handle.truncate(atOffset: UInt64(size))
        try handle.close()
        let source = UploadSource(
            sourceRef: url.path(percentEncoded: false),
            name: fileName,
            size: size,
            lastModified: nil,
            mimeType: "application/octet-stream"
        )
        _ = try await CoreGraph.shared.transferManager().enqueueUploads(
            accountId: accountId,
            sources: [source],
            targetDirUri: "cloudreve://my",
            policy: nil,
            overwrite: false
        )
    }
}
#endif
