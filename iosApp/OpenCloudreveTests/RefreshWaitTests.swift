import Testing
@testable import OpenCloudreve

/// 下拉刷新等待：离开页面（动作被取消）时立即结束，而不是在已取消的 sleep 上忙转到超时
@MainActor
struct RefreshWaitTests {
    @MainActor
    private final class Probe {
        var checks = 0
    }

    @Test(.timeLimit(.minutes(1)))
    func cancelledWaitEndsImmediatelyWithoutSpinning() async {
        let probe = Probe()
        let waiting = Task { @MainActor in
            // 一直在刷新（例如请求挂起后账户被移除）
            await RefreshWait.whileBusy(timeout: .seconds(30)) {
                probe.checks += 1
                return true
            }
        }
        try? await Task.sleep(for: .milliseconds(350))
        let checksBeforeCancel = probe.checks
        let cancelled = ContinuousClock.now
        waiting.cancel()
        await waiting.value
        #expect(ContinuousClock.now - cancelled < .seconds(1), "取消后等待没有结束")
        #expect(probe.checks - checksBeforeCancel <= 1, "取消后仍在反复检查状态")
    }

    @Test(.timeLimit(.minutes(1)))
    func waitEndsWhenNoLongerBusy() async {
        let probe = Probe()
        let started = ContinuousClock.now
        await RefreshWait.whileBusy(timeout: .seconds(30)) {
            probe.checks += 1
            return probe.checks < 3
        }
        // 第 3 次检查时结束：两次轮询间隔
        #expect(probe.checks == 3)
        #expect(ContinuousClock.now - started < .seconds(5))
    }
}
