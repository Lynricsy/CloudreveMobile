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
/// 同一传输任务的所有回调经 `SerialTaskChains` 串行处理。主 App 同时连接分享扩展的会话，
/// 扩展退出后由主 App 接收其分片完成事件并继续后续分片（`reattach` 也会查询两个会话）。
final class BackgroundTransferExecutor: NSObject, TransferExecutor, @unchecked Sendable {
    // @unchecked：可变状态都在 `state`（Mutex）里；`sessions` 只在 init 中写入一次

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
    }

    private static let installed = Mutex<BackgroundTransferExecutor?>(nil)
    private static let progressInterval: TimeInterval = 1
    /// File Provider 扩展自行执行的任务（其路径都在 App Group `tmp/FileProvider/` 下），本执行器不接管
    private static let fileProviderMarker = "/tmp/FileProvider/"

    private let state = Mutex(State())
    private let chains = SerialTaskChains()
    private let delegateQueue: OperationQueue
    private let stagingDirectory: URL
    private var sessions: [URLSession] = []

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
                configurations: role.sessionIdentifiers.map(TransferSessionRole.backgroundConfiguration(identifier:))
            )
            CoreGraph.shared.installExecutor(executor: executor)
            current = executor
            return executor
        }
    }

    /// [configurations] 第一个为发起新请求的主会话；测试可传前台配置（`.ephemeral`），回调逻辑相同
    init(configurations: [URLSessionConfiguration]) {
        precondition(!configurations.isEmpty, "至少需要一个会话配置")
        let queue = OperationQueue()
        queue.name = "plus.ling.opencloudreve.transfer.delegate"
        queue.maxConcurrentOperationCount = 1
        delegateQueue = queue
        stagingDirectory = FileManager.default.temporaryDirectory
            .appending(path: "TransferDownloads", directoryHint: .isDirectory)
        super.init()
        sessions = configurations.map { URLSession(configuration: $0, delegate: self, delegateQueue: queue) }
    }

    private var primarySession: URLSession { sessions[0] }

    // MARK: - TransferExecutor

    func enqueue(taskId: String) {
        chains.enqueue(taskId) { [self] in await start(taskId) }
    }

    func pause(taskId: String) {
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

    func cancel(taskId: String) {
        Task { [self] in
            for task in await platformTasks(taskId) {
                task.cancel()
            }
            chains.enqueue(taskId) { [self] in await cleanUp(taskId) }
        }
    }

    func reattach() {
        Task { [self] in await reattachAll() }
    }

    // MARK: - 进程级入口

    /// `application(_:handleEventsForBackgroundURLSession:completionHandler:)`：
    /// 连接对应会话并保存回调，事件处理完（`urlSessionDidFinishEvents`）后调用
    func handleEvents(forSessionIdentifier identifier: String, completion: @escaping @Sendable () -> Void) {
        state.withLock { $0.eventCompletions[identifier] = completion }
        if !sessions.contains(where: { $0.configuration.identifier == identifier }) {
            // 不是本进程的会话（理论上不会发生）：立即结束，避免系统一直等待
            let pending = state.withLock { $0.eventCompletions.removeValue(forKey: identifier) }
            DispatchQueue.main.async { pending?() }
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

    private func start(_ taskId: String) async {
        let repository = CoreGraph.shared.transferRepository()
        guard let item = try? await repository.task(id: taskId), Self.isActive(item.state) else { return }
        let inflight = await platformTasks(taskId)
        switch item.kind {
        case .upload:
            if inflight.isEmpty {
                await prepareUpload(taskId)
            } else {
                await dispatchUpload(taskId)
            }
        case .download:
            if inflight.isEmpty { await startDownload(taskId) }
        }
    }

    private func prepareUpload(_ taskId: String) async {
        do {
            _ = try await CoreGraph.shared.uploadPlanner().prepare(taskId: taskId)
        } catch {
            await failIfActive(taskId, error)
            return
        }
        await refreshCommitted(taskId)
        await dispatchUpload(taskId)
    }

    /// 取规划器给出的空闲并发请求并发送；没有可发请求且没有在途分片时检查是否该收尾
    private func dispatchUpload(_ taskId: String) async {
        let planner = CoreGraph.shared.uploadPlanner()
        let requests: [ChunkRequest]
        do {
            requests = try await planner.nextRequests(taskId: taskId)
        } catch {
            await failIfActive(taskId, error)
            return
        }
        if requests.isEmpty {
            guard await platformTasks(taskId).isEmpty else { return }
            if let done = try? await planner.allChunksDone(taskId: taskId), KotlinValues.bool(done) {
                await finalizeUpload(taskId)
            }
            return
        }
        let materializer = CoreGraph.shared.chunkMaterializer()
        for request in requests {
            do {
                let chunk = try await materializer.materialize(request: request)
                try send(chunk, taskId: taskId)
            } catch {
                await markFailed(taskId, code: TransferErrorCode.shared.SOURCE_UNAVAILABLE, message: KotlinValues.message(error))
                await cancelPlatformTasks(taskId)
                return
            }
        }
    }

    private func send(_ chunk: MaterializedChunk, taskId: String) throws {
        let request = chunk.request
        guard let url = URL(string: request.url) else { throw URLError(.badURL) }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method
        for (name, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: name)
        }
        let index = Int(request.index)
        state.withLock { $0.sentBytes[taskId, default: [:]][index] = 0 }
        let task = primarySession.uploadTask(with: urlRequest, fromFile: URL(filePath: chunk.path))
        task.taskDescription = TransferTaskDescriptor.upload(taskId: taskId, index: index).encoded
        task.resume()
    }

    private func finalizeUpload(_ taskId: String) async {
        do {
            let item = try await CoreGraph.shared.uploadPlanner().finalize(taskId: taskId)
            if item.state == .completed {
                clearProgress(taskId)
                if let source = item.sourceRef { TransferInbox.discard(sourceRef: source) }
            }
        } catch {
            await failIfActive(taskId, error)
        }
    }

    private func uploadChunkCompleted(
        _ taskId: String,
        index: Int,
        status: Int?,
        etag: String?,
        body: Data?,
        failure: String?,
        cancelled: Bool
    ) async {
        state.withLock { _ = $0.sentBytes[taskId]?.removeValue(forKey: index) }
        if cancelled {
            // 暂停/取消/重建会话时主动取消的请求：状态已由共享层处理，只清理临时文件
            await discardChunk(taskId, index: index)
            return
        }
        let planner = CoreGraph.shared.uploadPlanner()
        let outcome: TransferOutcome
        do {
            if let status, failure == nil {
                let response = ChunkResponse(
                    statusCode: Int32(status),
                    etag: etag,
                    body: body.flatMap { String(data: $0, encoding: .utf8) }
                )
                outcome = try await planner.onChunkDone(taskId: taskId, index: Int32(index), response: response)
            } else {
                outcome = try await planner.onChunkFailed(taskId: taskId, index: Int32(index), message: failure ?? "network error")
            }
        } catch {
            await discardChunk(taskId, index: index)
            await failIfActive(taskId, error)
            await cancelPlatformTasks(taskId)
            return
        }
        await discardChunk(taskId, index: index)
        await refreshCommitted(taskId)
        await handle(outcome, taskId: taskId)
    }

    private func handle(_ outcome: TransferOutcome, taskId: String) async {
        switch onEnum(of: outcome) {
        case let .continue(next):
            if next.allChunksDone {
                if await platformTasks(taskId).isEmpty { await finalizeUpload(taskId) }
            } else {
                await dispatchUpload(taskId)
            }
        case let .retryAfter(retry):
            try? await Task.sleep(for: .milliseconds(retry.delayMillis))
            await dispatchUpload(taskId)
        case .restart:
            await cancelPlatformTasks(taskId)
            await prepareUpload(taskId)
        case .failed:
            // 状态已写入，停止本任务其余在途分片
            clearProgress(taskId)
            await cancelPlatformTasks(taskId)
        }
    }

    // MARK: - 下载

    private func startDownload(_ taskId: String) async {
        let request: DownloadRequest
        do {
            request = try await CoreGraph.shared.downloadPlanner().prepare(taskId: taskId)
        } catch {
            await failIfActive(taskId, error)
            return
        }
        let task: URLSessionDownloadTask
        if let blob = request.resumeData {
            task = primarySession.downloadTask(withResumeData: KotlinValues.data(blob))
        } else {
            guard let url = URL(string: request.url) else {
                await markFailed(taskId, code: ErrorCode.shared.TRANSPORT, message: "Invalid download url")
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
        task.resume()
    }

    private func downloadCompleted(
        _ taskId: String,
        status: Int?,
        failure: String?,
        cancelled: Bool,
        resumeData: Data?
    ) async {
        let repository = CoreGraph.shared.transferRepository()
        let planner = CoreGraph.shared.downloadPlanner()
        let staged = stagedURL(taskId)
        clearProgress(taskId)
        if let resumeData {
            _ = try? await repository.setResumeBlob(id: taskId, blob: KotlinValues.bytes(resumeData))
        }
        if cancelled { return }
        if failure == nil, let status, (200..<300).contains(status),
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
            } catch {
                await markFailed(taskId, code: TransferErrorCode.shared.SOURCE_UNAVAILABLE, message: KotlinValues.message(error))
            }
            return
        }
        try? FileManager.default.removeItem(at: staged)
        let outcome: TransferOutcome
        do {
            let httpStatus = failure == nil ? status.map { KotlinInt(int: Int32($0)) } : nil
            outcome = try await planner.onFailed(taskId: taskId, httpStatus: httpStatus, message: failure ?? "HTTP \(status ?? 0)")
        } catch {
            await failIfActive(taskId, error)
            return
        }
        if case let .retryAfter(retry) = onEnum(of: outcome) {
            try? await Task.sleep(for: .milliseconds(retry.delayMillis))
            await start(taskId)
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
                        await dispatchUpload(taskId)
                    } else if let done = try? await planner.allChunksDone(taskId: taskId), KotlinValues.bool(done) {
                        await finalizeUpload(taskId)
                    } else {
                        await prepareUpload(taskId)
                    }
                }
            case .download:
                if !downloading {
                    chains.enqueue(taskId) { [self] in await startDownload(taskId) }
                }
            }
        }
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

    /// 取消后的清理：下载删除暂存与未完成的目标文件；上传删除收件箱副本
    private func cleanUp(_ taskId: String) async {
        clearProgress(taskId)
        try? FileManager.default.removeItem(at: stagedURL(taskId))
        guard let item = try? await CoreGraph.shared.transferRepository().task(id: taskId) else { return }
        switch item.kind {
        case .upload:
            if let source = item.sourceRef { TransferInbox.discard(sourceRef: source) }
        case .download:
            if item.state != .completed, let destPath = item.destPath {
                try? FileManager.default.removeItem(at: DownloadDestination.resolve(destPath))
            }
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

    private func failIfActive(_ taskId: String, _ error: Error) async {
        await markFailed(taskId, code: ErrorCode.shared.TRANSPORT, message: KotlinValues.message(error))
    }

    /// 任务仍在进行时标记失败（规划器已写入失败状态的保持不变）
    private func markFailed(_ taskId: String, code: Int32, message: String) async {
        clearProgress(taskId)
        let repository = CoreGraph.shared.transferRepository()
        guard let item = try? await repository.task(id: taskId), Self.isActive(item.state) else { return }
        _ = try? await repository.setState(id: taskId, state: .failed, errorCode: KotlinInt(int: code), errorMessage: message)
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
        let status = http?.statusCode
        let etag = http?.value(forHTTPHeaderField: "ETag")
        let nsError = error as NSError?
        let cancelled = nsError?.domain == NSURLErrorDomain && nsError?.code == NSURLErrorCancelled
        let failure = error.map(KotlinValues.message)
        let resumeData = nsError?.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        switch descriptor {
        case let .upload(taskId, index):
            chains.enqueue(taskId) { [self] in
                await uploadChunkCompleted(
                    taskId, index: index, status: status, etag: etag, body: body, failure: failure, cancelled: cancelled
                )
            }
        case let .download(taskId):
            chains.enqueue(taskId) { [self] in
                await downloadCompleted(taskId, status: status, failure: failure, cancelled: cancelled, resumeData: resumeData)
            }
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        guard let identifier = session.configuration.identifier else { return }
        Task { [self] in
            // 先处理完已排队的回调（可能补发下一批分片），再让系统挂起进程
            await drain()
            let completion = state.withLock { $0.eventCompletions.removeValue(forKey: identifier) }
            DispatchQueue.main.async { completion?() }
        }
    }
}
