import Foundation
import Observation

/// 上传表单的一次会话：持有已选的候选文件、照片导入任务，以及尚未移交给传输队列的收件箱副本。
///
/// 表单以任何方式关闭（“取消”、下拉关闭、父视图移除）都会清理：“取消”调用 `close()`，
/// 其余由析构完成——取消导入，删除没有移交的收件箱副本；关闭后才导入完成的副本直接删除，不再加入表单。
/// 发出入队（`handOff`）后副本归传输任务所有（任务完成/取消时由执行器删除），不再由表单删除。
@MainActor
@Observable
final class UploadDraft {
    var candidates: [UploadCandidate] = []
    private(set) var loadingMedia = false

    @ObservationIgnored private var importTask: Task<Void, Never>?
    /// 本会话产生的收件箱副本（sourceRef）
    @ObservationIgnored private var inboxCopies: Set<String> = []
    @ObservationIgnored private var handedOff = false
    @ObservationIgnored private var closed = false

    /// 加入一个候选文件（文件选择器、相机、导入的照片）
    func add(_ candidate: UploadCandidate) {
        if candidate.isInboxCopy { inboxCopies.insert(candidate.sourceRef) }
        candidates.append(candidate)
    }

    /// 依次导入 [count] 个项目：[load] 把第 i 个复制到收件箱并返回位置（无法读取返回 nil 或抛错），
    /// 结束时以失败数调用 [onFinish]（表单已关闭时不调用）
    @discardableResult
    func importMedia(
        count: Int,
        load: @escaping @MainActor (Int) async throws -> URL?,
        onFinish: @escaping @MainActor (Int) -> Void
    ) -> Task<Void, Never> {
        let task = Task { @MainActor [weak self] in
            self?.loadingMedia = true
            var failed = 0
            for index in 0..<count {
                let url = try? await load(index)
                guard let self, !self.closed, !Task.isCancelled else {
                    // 表单已关闭：这份副本没有人会上传
                    if let url { TransferInbox.discard(sourceRef: url.path(percentEncoded: false)) }
                    return
                }
                guard let url else {
                    failed += 1
                    continue
                }
                if let candidate = try? UploadCandidate.localFile(url) {
                    self.add(candidate)
                } else {
                    TransferInbox.discard(sourceRef: url.path(percentEncoded: false))
                    failed += 1
                }
            }
            guard let self, !self.closed else { return }
            self.loadingMedia = false
            onFinish(failed)
        }
        importTask = task
        return task
    }

    /// 发出入队：返回要上传的来源，副本所有权移交给传输任务
    func handOff() -> [UploadCandidate] {
        handedOff = true
        return candidates
    }

    /// 入队失败：副本仍归表单所有
    func handOffFailed() {
        handedOff = false
    }

    /// 关闭表单：取消导入并删除未移交的副本
    func close() {
        guard !closed else { return }
        closed = true
        loadingMedia = false
        discardOwnedCopies()
    }

    isolated deinit {
        if !closed { discardOwnedCopies() }
    }

    private func discardOwnedCopies() {
        importTask?.cancel()
        guard !handedOff else { return }
        inboxCopies.forEach { TransferInbox.discard(sourceRef: $0) }
        inboxCopies.removeAll()
    }
}
