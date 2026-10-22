/// 下拉刷新（`.refreshable`）的等待：系统在动作返回前一直显示刷新指示，
/// 所以发出刷新意图后轮询 VM 状态，直到不再忙、超时，或动作被取消（离开页面/移除账户）——取消时立即返回
enum RefreshWait {
    static let pollInterval: Duration = .milliseconds(100)
    static let defaultTimeout: Duration = .seconds(15)

    @MainActor
    static func whileBusy(timeout: Duration = defaultTimeout, _ isBusy: @MainActor () -> Bool) async {
        let deadline = ContinuousClock.now + timeout
        while isBusy() && ContinuousClock.now < deadline {
            do {
                try await Task.sleep(for: pollInterval)
            } catch {
                // 已取消：sleep 之后每次都会立即抛错，继续循环就成了忙等
                return
            }
        }
    }
}
