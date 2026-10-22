import Foundation
import Synchronization
import Testing
@testable import OpenCloudreve

/// 上传表单的收件箱副本所有权：任何关闭方式都删除未入队的副本，入队的副本保留给传输任务
@MainActor
struct UploadDraftTests {
    private func inboxCopy(_ name: String) throws -> URL {
        try TransferInbox.write(Data("media".utf8), into: TransferInbox.makeBatchDirectory(), name: name)
    }

    private func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path(percentEncoded: false))
    }

    @Test func cancelDeletesCopiesNotHandedOff() throws {
        let copy = try inboxCopy("photo.heic")
        let draft = UploadDraft()
        draft.add(try UploadCandidate.localFile(copy))
        draft.close()
        #expect(!exists(copy))
    }

    @Test func swipeDismissDeletesCopiesWhenDraftIsReleased() throws {
        let copy = try inboxCopy("clip.mov")
        var draft: UploadDraft? = UploadDraft()
        draft?.add(try UploadCandidate.localFile(copy))
        // 下拉关闭：表单状态随视图销毁，不经过“取消”按钮
        draft = nil
        #expect(!exists(copy))
    }

    @Test func handedOffCopiesStayForTheTransferTask() throws {
        let copy = try inboxCopy("photo.jpg")
        let draft = UploadDraft()
        draft.add(try UploadCandidate.localFile(copy))
        #expect(draft.handOff().count == 1)
        draft.close()
        #expect(exists(copy))
        TransferInbox.discard(sourceRef: copy.path(percentEncoded: false))
    }

    @Test func failedHandOffReturnsCopiesToTheDraft() throws {
        let copy = try inboxCopy("photo.png")
        let draft = UploadDraft()
        draft.add(try UploadCandidate.localFile(copy))
        _ = draft.handOff()
        draft.handOffFailed()
        draft.close()
        #expect(!exists(copy))
    }

    @Test(.timeLimit(.minutes(1)))
    func importFinishingAfterCloseDeletesItsCopy() async throws {
        let draft = UploadDraft()
        let produced = Mutex<URL?>(nil)
        let importing = draft.importMedia(count: 1) { _ in
            // 系统照片导入不响应取消：关闭表单后仍会把副本写完
            await withCheckedContinuation { continuation in
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { continuation.resume() }
            }
            let url = try TransferInbox.write(Data("late".utf8), into: TransferInbox.makeBatchDirectory(), name: "late.heic")
            produced.withLock { $0 = url }
            return url
        } onFinish: { _ in
            Issue.record("表单关闭后不应再回报导入结果")
        }
        draft.close()
        await importing.value
        let url = try #require(produced.withLock { $0 })
        #expect(!exists(url))
        #expect(draft.candidates.isEmpty)
    }
}
