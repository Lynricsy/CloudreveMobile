import OpenCloudreveCore

/// 主 App 修改远端文件后让系统“文件”看到变化。File Provider 的工作集只读目录缓存（不访问服务端），
/// 所以先重新获取受影响目录的第一页（`listPage` 写入缓存），缓存更新之后才发信号；
/// 若先发信号，扩展会立即枚举旧缓存，之后缓存变新却没有第二次信号。
enum FileProviderSync {
    @MainActor
    static func operationSucceeded(accountId: String, operation: FileOperation) {
        let directories = FileChangeScope.affectedDirectories(of: operation)
        Task {
            await refreshThenSignal(directories: directories) { directory in
                await refreshListing(accountId: accountId, uri: directory)
            } signal: {
                FileProviderSignal.workingSetChanged(accountId: accountId)
            }
        }
    }

    /// 依次刷新 [directories] 的缓存，全部结束后（成功与否）发信号
    @MainActor
    static func refreshThenSignal(
        directories: [String],
        refresh: (String) async -> Void,
        signal: () -> Void
    ) async {
        for directory in directories {
            await refresh(directory)
        }
        signal()
    }

    /// 与 File Provider 列目录相同的页大小，写入的缓存覆盖工作集常见目录
    private nonisolated static func refreshListing(accountId: String, uri: String) async {
        _ = try? await CoreGraph.shared.files(accountId: accountId).listPage(
            uri: uri,
            pageSize: KotlinInt(int: 200),
            orderBy: nil,
            orderDirection: nil,
            page: nil,
            nextPageToken: nil
        )
    }
}
