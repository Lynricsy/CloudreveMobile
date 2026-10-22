import Foundation
import OpenCloudreveCore
import Synchronization

/// iOS 传输执行器（共享层 `TransferExecutor`）：用后台 `URLSession` 搬运字节，规划与状态全部交给共享层。
///
/// 上传：`UploadPlanner.prepare` → `nextRequests` → `ChunkMaterializer.materialize`（区间/加密后的临时文件，
/// 位于 App Group `tmp/chunks/`）→ `uploadTask(with:fromFile:)`，`taskDescription` 见 `TransferTaskDescriptor`；
/// 分片完成 → `onChunkDone` / `onChunkFailed` → 删除临时文件 → 按结果补发、重试、重建会话或收尾（`finalize`）。
///
/// 下载：`DownloadPlanner.prepare` → `downloadTask`（有 resumeData 时续传）；完成时文件先移到暂存区，
/// 再移到任务的目标路径（`Documents/Downloads/<账户>/`）；暂停时保存 resumeData（`setResumeBlob`）。
///
/// 同一传输任务的所有回调经 `SerialTaskChains` 串行处理。暂停/取消经 `TransferRunControl` 立即作废
/// 正在准备/物化的启动：作废后不再创建或启动平台任务，已物化未发送的分片退回规划器。
///
/// 会话：每个进程启动时只连接自己的后台会话（同一会话同时只能由一个进程使用）。分享扩展退出后，
/// 系统以 `handleEventsForBackgroundURLSession` 把扩展会话的事件交给主 App，主 App 这时临时连接该会话，
/// 事件处理完即释放；接管时按 `TransferOwnership` 跳过其他会话仍持有的任务。
final class BackgroundTransferExecutor: NSObject, TransferExecutor, @unchecked Sendable {
    // @unchecked：可变状态都在 `state`（Mutex）里；`primary` 只在 init 中写入一次

    private struct State {
        /// 上传响应体（按会话 + 任务编号）
        var bodies: [String: Data] = [:]
        /// 上传：各在途分片已发送字节（taskId → index → bytes）
        var sentBytes: [String: [Int: Int64]] = [:]
        /// 上传：规划器已确认的字节（taskId → bytes）
        var committed: [String: Int64] = [:]
        /// 进度写库节流（taskId → 上次写入时间）
        var lastProgress: [String: Date] = [:]
        /// `handleEventsForBackgroundURLSession` 交来的完成回调
        var eventCompletions: [String: @Sendable () -> Void] = [:]
        /// 为处理系统交来的事件而临时连接的其他进程会话（处理完即释放）
        var attached: [String: URLSession] = [:]
        /// 各任务的运行代次（暂停/取消作废准备中的启动）
        var control = TransferRunControl()
    }

    /// 取消前的任务快照中清理需要的字段
    private struct CancelledTask: Sendable {
        let taskId: String
        let isUpload: Bool
        let sourceRef: String?
        let destPath: String?
    }

    private static let installed = Mutex<BackgroundTransferExecutor?>(nil)
    private static let progressInterval: TimeInterval = 1
    /// File Provider 扩展自行执行的任务（其路径都在 App Group `tmp/FileProvider/` 下），本执行器不接管
    private static let fileProviderMarker = "/tmp/FileProvider/"

    /// UI 测试（仅 Debug）：启动参数 `-OCRUITestChunkDelay <秒>` 让每批分片发送前等待，便于截取进行中的上传
    static var defaultChunkDelay: Duration? {
        #if DEBUG
        let seconds = UserDefaults.standard.double(forKey: "OCRUITestChunkDelay")
        return seconds > 0 ? .seconds(seconds) : nil
        #else
        return nil
        #endif
    }

    private let state = Mutex(State())
    private let chains = SerialTaskChains()
    private let delegateQueue: OperationQueue
    private let stagingDirectory: URL
    private let ownership: TransferOwnership
    private let chunkDelay: Duration?
    private let attachConfiguration: @Sendable (String) -> URLSessionConfiguration
    /// 本进程会话标识（归属记录中的持有者）
    private let owner: String
    private var primary: URLSession?

    /// 当前进程安装的执行器
    static var current: BackgroundTransferExecutor? {
        installed.withLock { $0 }
    }

    /// 创建本进程的执行器并安装到共享层（每个进程调用一次；重复调用返回已安装的实例）。
    /// 主 App 安装后应调用 `CoreGraph.shared.transferManager().reattach()` 接管未完成任务。
    @discardableResult
    static func install(role: TransferSessionRole) -> BackgroundTransferExecutor {
        installed.withLock { current in
            if let current { return current }
            let executor = BackgroundTransferExecutor(
                configuration: TransferSessionRole.backgroundConfiguration(identifier: role.primaryIdentifier)
            )
            CoreGraph.shared.installExecutor(executor: executor)
            current = executor
            return executor
        }
    }

    /// [configuration] 为本进程发起新请求的会话；测试可传前台配置（`.ephemeral`），回调逻辑相同。
    /// [attachConfiguration] 为系统交来其他会话的事件时，按标识构造临时连接用的配置。
    init(
        configuration: URLSessionConfiguration,
        ownership: TransferOwnership = .shared,
        chunkDelay: Duration? = BackgroundTransferExecutor.defaultChunkDelay,
        attachConfiguration: @escaping @Sendable (String) -> URLSessionConfiguration = {
            TransferSessionRole.backgroundConfiguration(identifier: $0)
        }
    ) {
        let queue = OperationQueue()
        queue.name = "plus.ling.opencloudreve.transfer.delegate"
        queue.maxConcurrentOperationCount = 1
        delegateQueue = queue
        stagingDirectory = FileManager.default.temporaryDirectory
            .appending(path: "TransferDownloads", directoryHint: .isDirectory)
        self.ownership = ownership
        self.chunkDelay = chunkDelay
        self.attachConfiguration = attachConfiguration
        owner = configuration.identifier ?? "foreground"
        super.init()
        primary = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }

    private var primarySession: URLSession {
        guard let primary else { preconditionFailure("会话在 init 中创建") }
        return primary
    }

    /// 当前连接着的全部会话（本进程会话 + 临时连接的会话）
    private var sessions: [URLSession] {
        [primarySession] + state.withLock { Array($0.attached.values) }
    }

    /// 为处理系统交来的事件而临时连接的会话标识
    var attachedSessionIdentifiers: Set<String> {
        state.withLock { Set($0.attached.keys) }
    }

    // MARK: - TransferExecutor

    func enqueue(taskId: String) {
        let token = state.withLock { $0.control.begin(taskId) }
        chains.enqueue(taskId) { [self] in await start(taskId, token: token) }
    }

    func pause(taskId: String) {
        state.withLock { $0.control.stop(taskId) }
        Task { [self] in
            for task in await platformTasks(taskId) {
                if let download = task as? URLSessionDownloadTask {
                    let resumeData = await download.cancelByProducingResumeData()
                    if let resumeData {
                        chains.enqueue(taskId) {
                            _ = try? await CoreGraph.shared.transferRepository()
                                .setResumeBlob(id: taskId, blob: KotlinValues.bytes(resumeData))
                        }
                    }
                } else {
                    task.cancel()
                }
            }
        }
    }

    func resume(taskId: String) {
        enqueue(taskId: taskId)
    }

    func cancel(task: TransferItem) {
        let cancelled = CancelledTask(
            taskId: task.id,
            isUpload: task.kind == .upload,
            sourceRef: task.sourceRef,
            destPath: task.destPath
        )
        state.withLock { $0.control.stop(cancelled.taskId) }
        Task { [self] in
            for platformTask in await platformTasks(cancelled.taskId) {
                platformTask.cancel()
            }
            chains.enqueue(cancelled.taskId) { [self] in cleanUp(cancelled) }
        }
    }

    func reattach() {
        Task { [self] in await reattachAll() }
    }

    // MARK: - 进程级入口

    /// `application(_:handleEventsForBackgroundURLSession:completionHandler:)`：保存回调；
    /// 不是本进程的会话（分享扩展的）时临时连接它，事件处理完（`urlSessionDidFinishEvents`）后释放
    func handleEvents(forSessionIdentifier identifier: String, completion: @escaping @Sendable () -> Void) {
        state.withLock { $0.eventCompletions[identifier] = completion }
        guard identifier != primarySession.configuration.identifier,
              state.withLock({ $0.attached[identifier] == nil }) else { return }
        let session = URLSession(configuration: attachConfiguration(identifier), delegate: self, delegateQueue: delegateQueue)
        state.withLock { $0.attached[identifier] = session }
    }

    /// 某会话的事件已全部投递：等已排队的回调处理完（可能在本进程会话补发下一批分片），
    /// 释放临时连接的会话，再让系统挂起进程
    func finishEvents(forSessionIdentifier identifier: String) async {
        await drain()
        let (completion, released) = state.withLock { state in
            (state.eventCompletions.removeValue(forKey: identifier), state.attached.removeValue(forKey: identifier))
        }
        released?.finishTasksAndInvalidate()
        if let completion {
            DispatchQueue.main.async { completion() }
        }
    }

    /// 等待已排队的全部回调处理完（分享扩展在关闭前确认第一批请求已交给系统）
    func drain() async {
        await chains.drain()
    }

    /// 释放会话（仅测试使用前台会话时调用）
    func invalidate() {
        sessions.forEach { $0.invalidateAndCancel() }
    }

    // MARK: - 调度

    private func start(_ taskId: String, token: UInt64) async {
        guard isValid(taskId, token) else { return }
        let repository = CoreGraph.shared.transferRepository()
        guard let item = try? await repository.task(id: taskId), Self.isActive(item.state) else { return }
        let inflight = await platformTasks(taskId)
        switch item.kind {
        case .upload:
            if inflight.isEmpty {
                await prepareUpload(taskId, token: token)
            } else {
                await dispatchUpload(taskId, token: token)
            }
        case .download:
            if inflight.isEmpty { await startDownload(taskId, token: token) }
        }
    }

    private func prepareUpload(_ taskId: String, token: UInt64) async {
        ownership.claim(taskId, session: owner, handedOff: false)
        do {
            _ = try await CoreGraph.shared.uploadPlanner().prepare(taskId: taskId)
        } catch {
            await failIfActive(taskId, error, token: token)
            return
        }
        // 准备期间被暂停/取消：不再发送
        guard isValid(taskId, token) else { return }
        await refreshCommitted(taskId)
        await dispatchUpload(taskId, token: token)
    }

    /// 取规划器给出的空闲并发请求并发送；没有可发请求且没有在途分片时检查是否该收尾
    private func dispatchUpload(_ taskId: String, token: UInt64) async {
        guard isValid(taskId, token) else { return }
        let planner = CoreGraph.shared.uploadPlanner()
        let requests: [ChunkRequest]
        do {
            requests = try await planner.nextRequests(taskId: taskId)
        } catch {
            await failIfActive(taskId, error, token: token)
            return
        }
        if requests.isEmpty {
            guard await platformTasks(taskId).isEmpty else { return }
            if let done = try? await planner.allChunksDone(taskId: taskId), KotlinValues.bool(done) {
                await finalizeUpload(taskId, token: token)
            }
            return
        }
        if let chunkDelay {
            try? await Task.sleep(for: chunkDelay)
        }
        let materializer = CoreGraph.shared.chunkMaterializer()
        for request in requests {
            let chunk: MaterializedChunk
            do {
                chunk = try await materializer.materialize(request: request)
            } catch {
                await markFailed(taskId, code: TransferErrorCode.shared.SOURCE_UNAVAILABLE, message: KotlinValues.message(error), token: token)
                await cancelPlatformTasks(taskId)
                return
            }
            let sent: Bool
            do {
                sent = try send(chunk, taskId: taskId, token: token)
            } catch {
                _ = try? await materializer.discard(taskId: taskId, index: request.index)
                await markFailed(taskId, code: TransferErrorCode.shared.SOURCE_UNAVAILABLE, message: KotlinValues.message(error), token: token)
                await cancelPlatformTasks(taskId)
                return
            }
            if !sent {
                // 物化期间被暂停/取消：删掉这一片的临时文件，把本批未发送的分片退回规划器
                _ = try? await materializer.discard(taskId: taskId, index: request.index)
                await releaseUnsentChunks(taskId)
                return
            }
        }
    }

    /// 创建并启动上传请求；令牌已失效时不启动并返回 false。
    /// 先创建（挂起状态）再核对令牌后启动：核对之后才发生的暂停能在 `platformTasks` 中看到并取消它
    private func send(_ chunk: MaterializedChunk, taskId: String, token: UInt64) throws -> Bool {
        let request = chunk.request
        guard let url = URL(string: request.url) else { throw URLError(.badURL) }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method
        for (name, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: name)
        }
        let index = Int(request.index)
        let task = primarySession.uploadTask(with: urlRequest, fromFile: URL(filePath: chunk.path))
        task.taskDescription = TransferTaskDescriptor.upload(taskId: taskId, index: index).encoded
        let valid = state.withLock { state in
            guard state.control.isValid(taskId, token) else { return false }
            state.sentBytes[taskId, default: [:]][index] = 0
            return true
        }
        guard valid else {
            task.cancel()
            return false
        }
        ownership.claim(taskId, session: owner, handedOff: true)
        task.resume()
        return true
    }

    /// 把已标记为发送中、但没有对应平台任务的分片退回待发送（暂停/取消后不留下“发送中”的分片）
    private func releaseUnsentChunks(_ taskId: String) async {
        let inflight = await platformTasks(taskId).compactMap { task -> KotlinInt? in
            guard case let .upload(_, index)? = TransferTaskDescriptor(parsing: task.taskDescription) else { return nil }
            return KotlinInt(int: Int32(index))
        }
        _ = try? await CoreGraph.shared.uploadPlanner().releaseChunks(taskId: taskId, inFlight: inflight)
    }

    private func finalizeUpload(_ taskId: String, token: UInt64) async {
        guard isValid(taskId, token) else { return }
        do {
            let item = try await CoreGraph.shared.uploadPlanner().finalize(taskId: taskId)
            if item.state == .completed {
                clearProgress(taskId)
                ownership.release(taskId)
                if let source = item.sourceRef { TransferInbox.discard(sourceRef: source) }
            }
        } catch {
            await failIfActive(taskId, error, token: token)
        }
    }

    private func uploadChunkCompleted(
        _ taskId: String,
        index: Int,
        response: ChunkCompletion,
        token: UInt64?
    ) async {
        state.withLock { _ = $0.sentBytes[taskId]?.removeValue(forKey: index) }
        if response.cancelled {
            // 暂停/取消/重建会话时主动取消的请求：状态已由共享层处理，只清理临时文件
            await discardChunk(taskId, index: index)
            return
        }
        let planner = CoreGraph.shared.uploadPlanner()
        let outcome: TransferOutcome
        do {
            if let status = response.status, response.failure == nil {
                let chunkResponse = ChunkResponse(
                    statusCode: Int32(status),
                    etag: response.etag,
                    body: response.body.flatMap { String(data: $0, encoding: .utf8) }
                )
                outcome = try await planner.onChunkDone(taskId: taskId, index: Int32(index), response: chunkResponse)
            } else {
                outcome = try await planner.onChunkFailed(taskId: taskId, index: Int32(index), message: response.failure ?? "network error")
            }
        } catch {
            await discardChunk(taskId, index: index)
            if let token { await failIfActive(taskId, error, token: token) }
            await cancelPlatformTasks(taskId)
            return
        }
        await discardChunk(taskId, index: index)
        await refreshCommitted(taskId)
        // 分片结果已交给规划器；任务已被暂停/取消时不再补发或收尾
        guard let token, isValid(taskId, token) else { return }
        await handle(outcome, taskId: taskId, token: token)
    }

    private func handle(_ outcome: TransferOutcome, taskId: String, token: UInt64) async {
        switch onEnum(of: outcome) {
        case let .continue(next):
            if next.allChunksDone {
                if await platformTasks(taskId).isEmpty { await finalizeUpload(taskId, token: token) }
            } else {
                await dispatchUpload(taskId, token: token)
            }
        case let .retryAfter(retry):
            try? await Task.sleep(for: .milliseconds(retry.delayMillis))
            await dispatchUpload(taskId, token: token)
        case .restart:
            await cancelPlatformTasks(taskId)
            await prepareUpload(taskId, token: token)
        case .failed:
            // 状态已写入，停止本任务其余在途分片
            clearProgress(taskId)
            ownership.release(taskId)
            await cancelPlatformTasks(taskId)
        }
    }

    // MARK: - 下载

    private func startDownload(_ taskId: String, token: UInt64) async {
        ownership.claim(taskId, session: owner, handedOff: false)
        let request: DownloadRequest
        do {
            request = try await CoreGraph.shared.downloadPlanner().prepare(taskId: taskId)
        } catch {
            await failIfActive(taskId, error, token: token)
            return
        }
        let task: URLSessionDownloadTask
        if let blob = request.resumeData {
            task = primarySession.downloadTask(withResumeData: KotlinValues.data(blob))
        } else {
            guard let url = URL(string: request.url) else {
                await markFailed(taskId, code: ErrorCode.shared.TRANSPORT, message: "Invalid download url", token: token)
                return
            }
            var urlRequest = URLRequest(url: url)
            // iOS 不写部分文件（未完成的数据由系统保存在 resumeData 中），没有 resumeData 时总是从头下载
            for (name, value) in request.headers where name.caseInsensitiveCompare("Range") != .orderedSame {
                urlRequest.setValue(value, forHTTPHeaderField: name)
            }
            task = primarySession.downloadTask(with: urlRequest)
        }
        task.taskDescription = TransferTaskDescriptor.download(taskId: taskId).encoded
        // 准备期间被暂停/取消：不启动（先创建再核对，核对之后的暂停能看到并取消它）
        guard isValid(taskId, token) else {
            task.cancel()
            return
        }
        ownership.claim(taskId, session: owner, handedOff: true)
        task.resume()
    }

    private func downloadCompleted(_ taskId: String, response: ChunkCompletion, token: UInt64?) async {
        let repository = CoreGraph.shared.transferRepository()
        let planner = CoreGraph.shared.downloadPlanner()
        let staged = stagedURL(taskId)
        clearProgress(taskId)
        if let resumeData = response.resumeData {
            _ = try? await repository.setResumeBlob(id: taskId, blob: KotlinValues.bytes(resumeData))
        }
        if response.cancelled { return }
        if response.failure == nil, let status = response.status, (200..<300).contains(status),
           FileManager.default.fileExists(atPath: staged.path(percentEncoded: false)) {
            do {
                guard let item = try await repository.task(id: taskId), let destPath = item.destPath else { return }
                let destination = DownloadDestination.resolve(destPath)
                let fileManager = FileManager.default
                try fileManager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                if fileManager.fileExists(atPath: destination.path(percentEncoded: false)) {
                    try fileManager.removeItem(at: destination)
                }
                try fileManager.moveItem(at: staged, to: destination)
                let size = (try? fileManager.attributesOfItem(atPath: destination.path(percentEncoded: false))[.size] as? NSNumber)?
                    .int64Value ?? item.size
                _ = try await planner.onCompleted(taskId: taskId, totalBytes: size)
                ownership.release(taskId)
            } catch {
                await markFailed(taskId, code: TransferErrorCode.shared.SOURCE_UNAVAILABLE, message: KotlinValues.message(error), token: token)
            }
            return
        }
        try? FileManager.default.removeItem(at: staged)
        let outcome: TransferOutcome
        do {
            let httpStatus = response.failure == nil ? response.status.map { KotlinInt(int: Int32($0)) } : nil
            outcome = try await planner.onFailed(
                taskId: taskId,
                httpStatus: httpStatus,
                message: response.failure ?? "HTTP \(response.status ?? 0)"
            )
        } catch {
            if let token { await failIfActive(taskId, error, token: token) }
            return
        }
        switch onEnum(of: outcome) {
        case let .retryAfter(retry):
            try? await Task.sleep(for: .milliseconds(retry.delayMillis))
            if let token { await start(taskId, token: token) }
        case .failed:
            ownership.release(taskId)
        case .continue, .restart:
            break
        }
    }

    private func stagedURL(_ taskId: String) -> URL {
        stagingDirectory.appending(path: taskId + ".download", directoryHint: .notDirectory)
    }

    // MARK: - 接管

    private func reattachAll() async {
        let tasks = await allPlatformTasks()
        let unfinished = (try? await CoreGraph.shared.transferRepository().unfinishedTasks()) ?? []
        var known = Set<String>()
        for item in unfinished where !Self.belongsToFileProvider(item) {
            let taskId = item.id
            known.insert(taskId)
            // 其他进程的会话仍持有：系统会在其请求结束后把事件交给本进程
            guard ownership.mayTakeOver(taskId, session: owner),
                  let token = state.withLock({ $0.control.current(taskId) }) else { continue }
            var indexes: [Int] = []
            var downloading = false
            for (descriptor, _) in tasks where descriptor.taskId == taskId {
                switch descriptor {
                case let .upload(_, index): indexes.append(index)
                case .download: downloading = true
                }
            }
            let inflight = indexes
            switch item.kind {
            case .upload:
                chains.enqueue(taskId) { [self] in
                    let planner = CoreGraph.shared.uploadPlanner()
                    _ = try? await planner.releaseChunks(taskId: taskId, inFlight: inflight.map { KotlinInt(int: Int32($0)) })
                    await refreshCommitted(taskId)
                    if !inflight.isEmpty {
                        await dispatchUpload(taskId, token: token)
                    } else if let done = try? await planner.allChunksDone(taskId: taskId), KotlinValues.bool(done) {
                        await finalizeUpload(taskId, token: token)
                    } else {
                        await prepareUpload(taskId, token: token)
                    }
                }
            case .download:
                if !downloading {
                    chains.enqueue(taskId) { [self] in await startDownload(taskId, token: token) }
                }
            }
        }
        ownership.prune(keeping: known)
        // 数据库里已不存在或已结束的任务：取消残留的系统任务
        let repository = CoreGraph.shared.transferRepository()
        for (descriptor, task) in tasks where !known.contains(descriptor.taskId) {
            guard let item = try? await repository.task(id: descriptor.taskId) else {
                task.cancel()
                continue
            }
            if !Self.isActive(item.state) && !Self.belongsToFileProvider(item) { task.cancel() }
        }
    }

    // MARK: - 辅助

    private func isValid(_ taskId: String, _ token: UInt64) -> Bool {
        state.withLock { $0.control.isValid(taskId, token) }
    }

    /// 本执行器各会话中属于 [taskId] 且仍在运行的系统任务
    private func platformTasks(_ taskId: String) async -> [URLSessionTask] {
        await allPlatformTasks().filter { $0.0.taskId == taskId }.map(\.1)
    }

    private func allPlatformTasks() async -> [(TransferTaskDescriptor, URLSessionTask)] {
        var result: [(TransferTaskDescriptor, URLSessionTask)] = []
        for session in sessions {
            for task in await session.allTasks where task.state == .running || task.state == .suspended {
                if let descriptor = TransferTaskDescriptor(parsing: task.taskDescription) {
                    result.append((descriptor, task))
                }
            }
        }
        return result
    }

    private func cancelPlatformTasks(_ taskId: String) async {
        for task in await platformTasks(taskId) {
            task.cancel()
        }
    }

    /// 删除分片临时文件；同一分片已被重新发送（重建会话/接管）时保留
    private func discardChunk(_ taskId: String, index: Int) async {
        let resent = await platformTasks(taskId).contains {
            TransferTaskDescriptor(parsing: $0.taskDescription) == .upload(taskId: taskId, index: index)
        }
        if !resent {
            _ = try? await CoreGraph.shared.chunkMaterializer().discard(taskId: taskId, index: Int32(index))
        }
    }

    /// 取消后的清理（只依据取消前的快照）：下载删除暂存与未完成的目标文件；上传删除收件箱副本
    private func cleanUp(_ task: CancelledTask) {
        clearProgress(task.taskId)
        ownership.release(task.taskId)
        try? FileManager.default.removeItem(at: stagedURL(task.taskId))
        if task.isUpload {
            if let source = task.sourceRef { TransferInbox.discard(sourceRef: source) }
        } else if let destPath = task.destPath {
            try? FileManager.default.removeItem(at: DownloadDestination.resolve(destPath))
        }
    }

    private func refreshCommitted(_ taskId: String) async {
        let bytes = (try? await CoreGraph.shared.transferRepository().task(id: taskId))?.bytesDone ?? 0
        state.withLock { $0.committed[taskId] = bytes }
    }

    private func clearProgress(_ taskId: String) {
        state.withLock { state in
            state.sentBytes[taskId] = nil
            state.committed[taskId] = nil
            state.lastProgress[taskId] = nil
        }
    }

    /// 是否到了写进度的时间（每任务最多每秒一次）
    private func shouldReportProgress(_ taskId: String) -> Bool {
        state.withLock { state in
            let now = Date()
            if let last = state.lastProgress[taskId], now.timeIntervalSince(last) < Self.progressInterval { return false }
            state.lastProgress[taskId] = now
            return true
        }
    }

    private func reportUploadProgress(_ taskId: String) async {
        let total: Int64? = state.withLock { state in
            guard let sent = state.sentBytes[taskId], !sent.isEmpty else { return nil }
            return (state.committed[taskId] ?? 0) + sent.values.reduce(0, +)
        }
        guard let total else { return }
        _ = try? await CoreGraph.shared.transferRepository().setBytesDone(id: taskId, bytesDone: total)
    }

    private func failIfActive(_ taskId: String, _ error: Error, token: UInt64?) async {
        await markFailed(taskId, code: ErrorCode.shared.TRANSPORT, message: KotlinValues.message(error), token: token)
    }

    /// 任务仍在进行（且未被本进程暂停/取消）时标记失败；规划器已写入失败或已停止的保持不变
    private func markFailed(_ taskId: String, code: Int32, message: String, token: UInt64?) async {
        clearProgress(taskId)
        if let token, !isValid(taskId, token) { return }
        let repository = CoreGraph.shared.transferRepository()
        guard let item = try? await repository.task(id: taskId), Self.isActive(item.state) else { return }
        _ = try? await repository.setState(id: taskId, state: .failed, errorCode: KotlinInt(int: code), errorMessage: message)
        ownership.release(taskId)
    }

    private static func isActive(_ state: TransferState) -> Bool {
        switch state {
        case .queued, .preparing, .running, .finishing: true
        default: false
        }
    }

    private static func belongsToFileProvider(_ item: TransferItem) -> Bool {
        (item.sourceRef?.contains(fileProviderMarker) ?? false) || (item.destPath?.contains(fileProviderMarker) ?? false)
    }

    private static func sessionKey(_ session: URLSession, _ task: URLSessionTask) -> String {
        "\(session.configuration.identifier ?? "foreground")#\(task.taskIdentifier)"
    }
}

/// 一个平台任务结束时交给串行链的结果（全部为 Sendable 值）
private struct ChunkCompletion: Sendable {
    let status: Int?
    let etag: String?
    let body: Data?
    let failure: String?
    let cancelled: Bool
    let resumeData: Data?
}

// MARK: - URLSession 回调

extension BackgroundTransferExecutor: URLSessionDataDelegate, URLSessionDownloadDelegate {
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        let key = Self.sessionKey(session, dataTask)
        state.withLock { $0.bodies[key, default: Data()].append(data) }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        guard case let .upload(taskId, index)? = TransferTaskDescriptor(parsing: task.taskDescription) else { return }
        state.withLock { state in
            if state.sentBytes[taskId] != nil { state.sentBytes[taskId]?[index] = totalBytesSent }
        }
        if shouldReportProgress(taskId) {
            chains.enqueue(taskId) { [self] in await reportUploadProgress(taskId) }
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard case let .download(taskId)? = TransferTaskDescriptor(parsing: downloadTask.taskDescription),
              shouldReportProgress(taskId) else { return }
        chains.enqueue(taskId) {
            _ = try? await CoreGraph.shared.downloadPlanner().onProgress(taskId: taskId, bytesWritten: totalBytesWritten)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // 系统在本回调返回后删除 location：同步移到暂存区，完成回调中再移到目标路径
        guard case let .download(taskId)? = TransferTaskDescriptor(parsing: downloadTask.taskDescription) else { return }
        let staged = stagedURL(taskId)
        let fileManager = FileManager.default
        try? fileManager.createDirectory(at: stagingDirectory, withIntermediateDirectories: true)
        try? fileManager.removeItem(at: staged)
        try? fileManager.moveItem(at: location, to: staged)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let body = state.withLock { $0.bodies.removeValue(forKey: Self.sessionKey(session, task)) }
        guard let descriptor = TransferTaskDescriptor(parsing: task.taskDescription) else { return }
        let http = task.response as? HTTPURLResponse
        let nsError = error as NSError?
        let completion = ChunkCompletion(
            status: http?.statusCode,
            etag: http?.value(forHTTPHeaderField: "ETag"),
            body: body,
            failure: error.map(KotlinValues.message),
            cancelled: nsError?.domain == NSURLErrorDomain && nsError?.code == NSURLErrorCancelled,
            resumeData: nsError?.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        )
        // 回调发生时任务所处的运行（已暂停/取消为 nil）
        let token = state.withLock { $0.control.current(descriptor.taskId) }
        switch descriptor {
        case let .upload(taskId, index):
            chains.enqueue(taskId) { [self] in
                await uploadChunkCompleted(taskId, index: index, response: completion, token: token)
            }
        case let .download(taskId):
            chains.enqueue(taskId) { [self] in
                await downloadCompleted(taskId, response: completion, token: token)
            }
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        guard let identifier = session.configuration.identifier else { return }
        Task { [self] in await finishEvents(forSessionIdentifier: identifier) }
    }
}
