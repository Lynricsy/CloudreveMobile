import Foundation
import Testing
@testable import OpenCloudreve

/// 执行器的运行控制与跨进程归属：暂停/取消作废准备中的启动、扩展会话只在处理系统事件时临时连接、
/// 接管时跳过其他会话仍持有的任务
struct TransferControlTests {
    // MARK: - 运行代次

    @Test func stopInvalidatesRunStartedBefore() {
        var control = TransferRunControl()
        let token = control.begin("t1")
        #expect(control.isValid("t1", token))
        control.stop("t1")
        // 暂停之前开始的准备即使之后返回也不能再发送
        #expect(!control.isValid("t1", token))
        #expect(control.current("t1") == nil)
    }

    @Test func resumeIssuesFreshTokenWhileOldStaysInvalid() {
        var control = TransferRunControl()
        let first = control.begin("t1")
        control.stop("t1")
        let second = control.begin("t1")
        #expect(control.isValid("t1", second))
        #expect(!control.isValid("t1", first))
        #expect(control.current("t1") == second)
    }

    @Test func stoppingOneTaskLeavesOthersRunning() {
        var control = TransferRunControl()
        let a = control.begin("a")
        let b = control.begin("b")
        control.stop("a")
        #expect(!control.isValid("a", a))
        #expect(control.isValid("b", b))
    }

    // MARK: - 跨进程归属

    private func makeOwnership() -> TransferOwnership {
        TransferOwnership(directory: FileManager.default.temporaryDirectory.appending(path: "owners-\(UUID().uuidString)"))
    }

    @Test func unclaimedOrOwnTasksCanBeTakenOver() {
        let ownership = makeOwnership()
        defer { try? FileManager.default.removeItem(at: ownership.directory) }
        #expect(ownership.mayTakeOver("t1", session: "app"))
        ownership.claim("t1", session: "app", handedOff: true)
        #expect(ownership.mayTakeOver("t1", session: "app"))
    }

    @Test func taskHandedToAnotherSessionIsSkippedUntilReleased() {
        let ownership = makeOwnership()
        defer { try? FileManager.default.removeItem(at: ownership.directory) }
        let claimed = Date(timeIntervalSince1970: 1_000)
        ownership.claim("t1", session: "share", handedOff: true, now: claimed)
        // 已交给扩展会话：无论过了多久都不接管（系统会把那些请求的事件交给主 App）
        #expect(!ownership.mayTakeOver("t1", session: "app", now: claimed.addingTimeInterval(86_400)))
        ownership.release("t1")
        #expect(ownership.mayTakeOver("t1", session: "app", now: claimed))
    }

    @Test func preparingClaimOfAnotherSessionExpiresAfterLease() {
        let ownership = makeOwnership()
        defer { try? FileManager.default.removeItem(at: ownership.directory) }
        let claimed = Date(timeIntervalSince1970: 1_000)
        ownership.claim("t1", session: "share", handedOff: false, now: claimed)
        #expect(!ownership.mayTakeOver("t1", session: "app", now: claimed.addingTimeInterval(60)))
        // 扩展在准备期间退出：租期过后由主 App 接管
        #expect(ownership.mayTakeOver("t1", session: "app", now: claimed.addingTimeInterval(121)))
    }

    @Test func pruneKeepsUnfinishedAndRecentClaims() {
        let ownership = makeOwnership()
        defer { try? FileManager.default.removeItem(at: ownership.directory) }
        let old = Date(timeIntervalSince1970: 1_000)
        let now = old.addingTimeInterval(600)
        ownership.claim("finished", session: "share", handedOff: true, now: old)
        ownership.claim("running", session: "share", handedOff: true, now: old)
        ownership.claim("just-created", session: "share", handedOff: false, now: now)
        ownership.prune(keeping: ["running"], now: now)
        #expect(ownership.claim(of: "finished") == nil)
        #expect(ownership.claim(of: "running")?.session == "share")
        #expect(ownership.claim(of: "just-created") != nil)
    }

    // MARK: - 会话连接

    @Test func anotherSessionIsAttachedOnlyWhileHandlingItsEvents() async {
        let ownership = makeOwnership()
        defer { try? FileManager.default.removeItem(at: ownership.directory) }
        let executor = BackgroundTransferExecutor(
            configuration: .ephemeral,
            ownership: ownership,
            chunkDelay: nil,
            attachConfiguration: { _ in .ephemeral }
        )
        defer { executor.invalidate() }
        let identifier = "plus.ling.opencloudreve.transfer.share"
        // 启动时不连接扩展会话
        #expect(executor.attachedSessionIdentifiers.isEmpty)
        await confirmation("事件处理完后调用系统的完成回调") { completed in
            executor.handleEvents(forSessionIdentifier: identifier) { completed() }
            #expect(executor.attachedSessionIdentifiers == [identifier])
            await executor.finishEvents(forSessionIdentifier: identifier)
            // 完成回调经主队列调用
            await MainActor.run {}
        }
        // 处理完即释放，扩展再次运行时可以连接自己的会话
        #expect(executor.attachedSessionIdentifiers.isEmpty)
    }
}
