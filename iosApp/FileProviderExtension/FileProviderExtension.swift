import FileProvider
import UniformTypeIdentifiers

/// 系统“文件”集成：每个账户一个域（域标识 = 账户 id），根 = `cloudreve://my`。
/// 远端操作见 `FileProviderService`；传输在本进程内用共享层 `DirectTransferRunner` 完成。
final class FileProviderExtension: NSObject, NSFileProviderReplicatedExtension {
    private let service: FileProviderService

    required init(domain: NSFileProviderDomain) {
        CoreBootstrap.start()
        service = FileProviderService(accountId: domain.identifier.rawValue, rootName: domain.displayName)
        super.init()
        FileProviderStaging.removeStale()
    }

    func invalidate() {}

    func item(for identifier: NSFileProviderItemIdentifier, request: NSFileProviderRequest,
              completionHandler: @escaping (NSFileProviderItem?, Error?) -> Void) -> Progress {
        let reply = SystemCallback(completionHandler)
        return Progress.running { [service] in
            do {
                let item = try await service.item(for: identifier)
                reply.value(item, nil)
            } catch {
                reply.value(nil, FileProviderErrorMap.providerError(error))
            }
        }
    }

    func fetchContents(for itemIdentifier: NSFileProviderItemIdentifier, version requestedVersion: NSFileProviderItemVersion?,
                       request: NSFileProviderRequest,
                       completionHandler: @escaping (URL?, NSFileProviderItem?, Error?) -> Void) -> Progress {
        let reply = SystemCallback(completionHandler)
        guard case let .file(id) = FileProviderIdentity.target(of: itemIdentifier) else {
            completionHandler(nil, nil, NSFileProviderError(.noSuchItem))
            return Progress()
        }
        return Progress.running { [service] in
            do {
                let (url, item) = try await service.download(fileId: id)
                reply.value(url, item, nil)
            } catch {
                reply.value(nil, nil, FileProviderErrorMap.providerError(error))
            }
        }
    }

    func createItem(basedOn itemTemplate: NSFileProviderItem, fields: NSFileProviderItemFields, contents url: URL?,
                    options: NSFileProviderCreateItemOptions = [], request: NSFileProviderRequest,
                    completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void) -> Progress {
        let reply = SystemCallback(completionHandler)
        let name = itemTemplate.filename
        let parent = itemTemplate.parentItemIdentifier
        let type: UTType? = itemTemplate.contentType
        if type?.conforms(to: .symbolicLink) == true || type?.conforms(to: .aliasFile) == true {
            completionHandler(nil, [], false, CocoaError(.featureUnsupported))
            return Progress()
        }
        let isFolder = type?.conforms(to: .folder) == true
        let mayAlreadyExist = options.contains(.mayAlreadyExist)
        return Progress.running { [service] in
            do {
                let item = try await service.create(
                    name: name, parent: parent, isFolder: isFolder, contents: url, mayAlreadyExist: mayAlreadyExist
                )
                reply.value(item, [], false, nil)
            } catch {
                reply.value(nil, [], false, FileProviderErrorMap.providerError(error))
            }
        }
    }

    func modifyItem(_ item: NSFileProviderItem, baseVersion version: NSFileProviderItemVersion, changedFields: NSFileProviderItemFields,
                    contents newContents: URL?, options: NSFileProviderModifyItemOptions = [], request: NSFileProviderRequest,
                    completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void) -> Progress {
        let reply = SystemCallback(completionHandler)
        guard case let .file(id) = FileProviderIdentity.target(of: item.itemIdentifier) else {
            // 根与回收站不可修改：原样确认
            completionHandler(item, [], false, nil)
            return Progress()
        }
        let newName = changedFields.contains(.filename) ? item.filename : nil
        let newParent = changedFields.contains(.parentItemIdentifier) ? item.parentItemIdentifier : nil
        let contents = changedFields.contains(.contents) ? newContents : nil
        // 其余字段（日期、标签、收藏等）服务端不保存，视为已同步
        return Progress.running { [service] in
            do {
                let updated = try await service.modify(fileId: id, newName: newName, newParent: newParent, contents: contents)
                reply.value(updated, [], false, nil)
            } catch {
                reply.value(nil, [], false, FileProviderErrorMap.providerError(error))
            }
        }
    }

    func deleteItem(identifier: NSFileProviderItemIdentifier, baseVersion version: NSFileProviderItemVersion,
                    options: NSFileProviderDeleteItemOptions = [], request: NSFileProviderRequest,
                    completionHandler: @escaping (Error?) -> Void) -> Progress {
        let reply = SystemCallback(completionHandler)
        guard case let .file(id) = FileProviderIdentity.target(of: identifier) else {
            completionHandler(NSFileProviderError(.deletionRejected))
            return Progress()
        }
        return Progress.running { [service] in
            do {
                try await service.delete(fileId: id)
                reply.value(nil)
            } catch {
                reply.value(FileProviderErrorMap.providerError(error))
            }
        }
    }

    func enumerator(for containerItemIdentifier: NSFileProviderItemIdentifier, request: NSFileProviderRequest) throws -> NSFileProviderEnumerator {
        FileProviderEnumerator(container: containerItemIdentifier, service: service)
    }
}
