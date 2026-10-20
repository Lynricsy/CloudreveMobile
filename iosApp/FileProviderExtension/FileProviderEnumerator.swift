import FileProvider

/// 容器（根、回收站、目录）与工作集的枚举器。
///
/// 服务端没有增量变更流：`enumerateChanges` 一律返回 `syncAnchorExpired`，系统随即重新完整枚举并与本地副本对比
/// （主 App 修改文件后 `signalEnumerator(for: .workingSet)` 触发这一过程）。
final class FileProviderEnumerator: NSObject, NSFileProviderEnumerator {
    private let container: NSFileProviderItemIdentifier
    private let service: FileProviderService

    init(container: NSFileProviderItemIdentifier, service: FileProviderService) {
        self.container = container
        self.service = service
        super.init()
    }

    func invalidate() {}

    func enumerateItems(for observer: NSFileProviderEnumerationObserver, startingAt page: NSFileProviderPage) {
        let observer = SystemCallback(observer)
        let token = FileProviderPageToken(page)
        Task { [container, service] in
            do {
                if container == .workingSet {
                    let items = try await service.workingSet()
                    observer.value.didEnumerate(items)
                    observer.value.finishEnumerating(upTo: nil)
                } else {
                    let result = try await service.list(container: container, page: token)
                    observer.value.didEnumerate(result.items)
                    observer.value.finishEnumerating(upTo: result.next?.providerPage)
                }
            } catch {
                observer.value.finishEnumeratingWithError(FileProviderErrorMap.providerError(error))
            }
        }
    }

    func enumerateChanges(for observer: NSFileProviderChangeObserver, from anchor: NSFileProviderSyncAnchor) {
        observer.finishEnumeratingWithError(NSFileProviderError(.syncAnchorExpired))
    }

    func currentSyncAnchor(completionHandler: @escaping (NSFileProviderSyncAnchor?) -> Void) {
        completionHandler(NSFileProviderSyncAnchor(Data(String(Date().timeIntervalSince1970).utf8)))
    }
}
