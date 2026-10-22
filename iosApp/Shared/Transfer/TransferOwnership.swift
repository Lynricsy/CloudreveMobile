import Foundation

/// 跨进程的传输任务归属（App Group `TransferOwners/<taskId>.json`）。
///
/// 主 App 与分享扩展各用一个后台会话，而一个后台会话同时只能由一个进程连接：主 App 平时看不到扩展会话里的请求。
/// 因此由发起请求的进程记下“哪个会话持有该任务”，主 App 接管未完成任务（`reattach`）时据此判断：
/// - 没有记录、或记录属于自己：照常接管；
/// - 其他会话已把请求交给系统（`handedOff`）：跳过——这些请求结束后系统会以
///   `handleEventsForBackgroundURLSession` 把事件交给主 App，那时再临时连接该会话处理；
/// - 其他会话认领了但尚未交给系统（还在准备）：在租期内跳过，超过租期视为对方已退出（例如准备期间被关闭），接管。
struct TransferOwnership: Sendable {
    struct Claim: Codable, Equatable, Sendable {
        /// 持有任务的后台会话标识
        let session: String
        /// 是否已有请求交给该会话
        let handedOff: Bool
        let updatedAt: Date
    }

    /// 其他进程认领后尚未把请求交给系统的最长时间（准备上传会话/签名地址的耗时远小于此值）
    static let preparingLease: TimeInterval = 120

    static let shared = TransferOwnership(
        directory: AppGroup.containerURL.appending(path: "TransferOwners", directoryHint: .isDirectory)
    )

    let directory: URL

    /// 记录 [session] 持有任务；[handedOff] 为 true 表示请求已交给该会话
    func claim(_ taskId: String, session: String, handedOff: Bool, now: Date = .now) {
        let claim = Claim(session: session, handedOff: handedOff, updatedAt: now)
        guard let data = try? JSONEncoder().encode(claim) else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: fileURL(taskId), options: .atomic)
    }

    /// 任务结束（完成/失败/取消）后删除记录
    func release(_ taskId: String) {
        try? FileManager.default.removeItem(at: fileURL(taskId))
    }

    func claim(of taskId: String) -> Claim? {
        guard let data = try? Data(contentsOf: fileURL(taskId)) else { return nil }
        return try? JSONDecoder().decode(Claim.self, from: data)
    }

    /// [session] 能否接管任务（见类型说明）
    func mayTakeOver(_ taskId: String, session: String, now: Date = .now) -> Bool {
        guard let claim = claim(of: taskId), claim.session != session else { return true }
        if claim.handedOff { return false }
        return now.timeIntervalSince(claim.updatedAt) > Self.preparingLease
    }

    /// 删除不在 [unfinished] 中且超过租期的记录（任务已结束或已删除）；
    /// 租期内的保留，避免误删其他进程刚建任务时写入的记录
    func prune(keeping unfinished: Set<String>, now: Date = .now) {
        let fileManager = FileManager.default
        guard let names = try? fileManager.contentsOfDirectory(atPath: directory.path(percentEncoded: false)) else { return }
        for name in names where name.hasSuffix(".json") {
            let taskId = String(name.dropLast(".json".count))
            guard !unfinished.contains(taskId) else { continue }
            let stale = claim(of: taskId).map { now.timeIntervalSince($0.updatedAt) > Self.preparingLease } ?? true
            if stale { try? fileManager.removeItem(at: directory.appending(path: name, directoryHint: .notDirectory)) }
        }
    }

    private func fileURL(_ taskId: String) -> URL {
        directory.appending(path: FileNaming.sanitize(taskId, fallback: "task") + ".json", directoryHint: .notDirectory)
    }
}
