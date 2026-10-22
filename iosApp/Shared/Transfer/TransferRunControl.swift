/// 每个传输任务的运行代次。
///
/// 暂停/取消（`stop`）立即作废此前开始的全部操作：它们可能正等待准备（上传会话/签名地址）或物化分片返回，
/// 返回后必须在创建并启动平台任务前用 `isValid` 核对令牌，失效则不再发送。
/// 继续/重新开始（`begin`）解除停止并发放新令牌，旧令牌保持失效。
struct TransferRunControl: Sendable {
    private var generations: [String: UInt64] = [:]
    private var stopped: Set<String> = []

    /// 开始或继续执行：返回本次运行的令牌
    mutating func begin(_ taskId: String) -> UInt64 {
        stopped.remove(taskId)
        return generations[taskId, default: 0]
    }

    /// 暂停/取消：作废已发放的令牌，直到下一次 `begin`
    mutating func stop(_ taskId: String) {
        stopped.insert(taskId)
        generations[taskId, default: 0] &+= 1
    }

    /// 回调（分片完成、接管）沿用的当前令牌；任务已停止时为 nil
    func current(_ taskId: String) -> UInt64? {
        stopped.contains(taskId) ? nil : generations[taskId, default: 0]
    }

    /// [token] 是否仍可发布结果（创建/启动平台任务、收尾）
    func isValid(_ taskId: String, _ token: UInt64) -> Bool {
        !stopped.contains(taskId) && generations[taskId, default: 0] == token
    }
}
