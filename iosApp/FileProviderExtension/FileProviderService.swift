import FileProvider
import OpenCloudreveCore
import UniformTypeIdentifiers

/// 文件 id ↔ URI 的进程内索引（枚举与查询时记录，避免为找父目录/容器反复请求）。
/// 值保留服务端原样路径（用于请求），按 URI 查找时用规范化形式比较。
actor FileProviderIndex {
    private var uriById: [String: String] = [:]
    private var idByURI: [String: String] = [:]

    func record(id: String, uri: String) {
        guard !id.isEmpty, !uri.isEmpty else { return }
        if let old = uriById[id] { idByURI[FileProviderIdentity.normalized(old)] = nil }
        uriById[id] = uri
        idByURI[FileProviderIdentity.normalized(uri)] = id
    }

    func record(_ entries: [(id: String, uri: String)]) {
        for entry in entries { record(id: entry.id, uri: entry.uri) }
    }

    func uri(forId id: String) -> String? { uriById[id] }

    func id(forURI uri: String) -> String? { idByURI[FileProviderIdentity.normalized(uri)] }
}

/// 一个账户域的远端操作：调用共享层并把结果转换成 `FileProviderItem`
struct FileProviderService: Sendable {
    let accountId: String
    /// “我的文件”根的显示名（域显示名）
    let rootName: String
    private let index = FileProviderIndex()

    static let pageSize: Int32 = 200
    /// 工作集最多取多少个最近缓存的目录
    static let workingSetListings: Int32 = 50

    init(accountId: String, rootName: String) {
        self.accountId = accountId
        self.rootName = rootName
    }

    private var files: FileRepository { CoreGraph.shared.files(accountId: accountId) }

    // MARK: - 查询

    struct Page: Sendable {
        let items: [FileProviderItem]
        let next: FileProviderPageToken?
    }

    /// 列出容器 [container] 的一页（根 = `cloudreve://my`，回收站 = `cloudreve://trash`）；第一页由共享层写入目录缓存
    func list(container: NSFileProviderItemIdentifier, page: FileProviderPageToken) async throws -> Page {
        let uri = try await containerURI(for: container)
        let result = try await files.listPage(
            uri: uri,
            pageSize: KotlinInt(int: Self.pageSize),
            orderBy: nil,
            orderDirection: nil,
            page: page.pageNumber.map { KotlinInt(int: Int32($0)) },
            nextPageToken: page.cursor
        )
        var entries = result.files.map { (id: $0.id, uri: $0.path) }
        if let parent = result.parent { entries.append((id: parent.id, uri: parent.path)) }
        await index.record(entries)
        let containerCapability = result.props.capability
        let items = result.files.map { FileProviderItem(file: $0, parent: container, containerCapability: containerCapability) }
        let pagination = result.pagination
        return Page(
            items: items,
            next: FileProviderPageToken.next(nextToken: pagination.nextToken, hasMore: pagination.hasMore, page: Int(pagination.page))
        )
    }

    /// 工作集：最近缓存过的目录里的全部条目（去重）
    func workingSet() async throws -> [FileProviderItem] {
        let listings = try await files.recentListings(limit: Self.workingSetListings)
        var seen = Set<NSFileProviderItemIdentifier>()
        var items: [FileProviderItem] = []
        var entries: [(id: String, uri: String)] = []
        for listing in listings {
            guard let first = listing.files.first else { continue }
            let parent: NSFileProviderItemIdentifier
            if let dir = listing.parent, !dir.id.isEmpty {
                parent = FileProviderIdentity.identifier(fileId: dir.id, uri: dir.path)
                entries.append((id: dir.id, uri: dir.path))
            } else if let known = FileProviderIdentity.knownParent(ofURI: first.path) {
                parent = known
            } else {
                continue
            }
            for file in listing.files {
                let item = FileProviderItem(file: file, parent: parent, containerCapability: listing.props.capability)
                guard seen.insert(item.itemIdentifier).inserted else { continue }
                items.append(item)
                entries.append((id: file.id, uri: file.path))
            }
        }
        await index.record(entries)
        return items
    }

    /// 条目详情（`GET /file/info?id=`）
    func item(for identifier: NSFileProviderItemIdentifier) async throws -> FileProviderItem {
        switch FileProviderIdentity.target(of: identifier) {
        case .root: return .root(name: rootName)
        case .trash: return .trash()
        case .workingSet: throw NSFileProviderError(.noSuchItem)
        case let .file(id): return try await item(forFileId: id)
        }
    }

    private func item(forFileId id: String) async throws -> FileProviderItem {
        let file = try await files.infoById(id: id, extended: false, folderSummary: false)
        await index.record(id: file.id, uri: file.path)
        let parent = try await parentIdentifier(ofURI: file.path)
        return FileProviderItem(file: file, parent: parent, containerCapability: nil)
    }

    /// 目录 [parentURI] 下的 [name]（创建冲突且系统允许已存在时取回现有条目）
    private func child(parentURI: String, name: String, parent: NSFileProviderItemIdentifier) async throws -> FileProviderItem {
        let file = try await files.info(uri: FileProviderIdentity.childURI(parentURI: parentURI, name: name), extended: false, folderSummary: false)
        await index.record(id: file.id, uri: file.path)
        return FileProviderItem(file: file, parent: parent, containerCapability: nil)
    }

    // MARK: - 内容

    /// 下载文件当前版本到扩展临时目录，返回文件位置与最新条目（系统随后接管该文件）
    func download(fileId id: String) async throws -> (URL, FileProviderItem) {
        let file = try await files.infoById(id: id, extended: false, folderSummary: false)
        await index.record(id: file.id, uri: file.path)
        let parent = try await parentIdentifier(ofURI: file.path)
        let item = FileProviderItem(file: file, parent: parent, containerCapability: nil)
        let destination = try FileProviderStaging.makeDirectory().appending(path: file.name, directoryHint: .notDirectory)
        _ = try await CoreGraph.shared.directTransferRunner().downloadFile(
            accountId: accountId,
            fileUri: files.resolveLinkedUri(file: file),
            displayName: file.name,
            size: file.size,
            destPath: destination.path(percentEncoded: false)
        )
        return (destination, item)
    }

    // MARK: - 修改

    /// 新建目录或文件。[contents] 为 nil 的文件建空文件；[mayAlreadyExist] 时同名冲突返回现有条目
    func create(
        name: String,
        parent: NSFileProviderItemIdentifier,
        isFolder: Bool,
        contents: URL?,
        mayAlreadyExist: Bool
    ) async throws -> FileProviderItem {
        let parentURI = try await containerURI(for: parent)
        do {
            if isFolder {
                let folder = try await files.createFolder(parentUri: parentURI, name: name)
                return await record(folder, parent: parent)
            }
            guard let contents else {
                let empty = try await files.createFile(parentUri: parentURI, name: name)
                return await record(empty, parent: parent)
            }
            return try await upload(contents: contents, name: name, parentURI: parentURI, parent: parent, overwrite: false)
        } catch let error where mayAlreadyExist && Self.isCollision(error) {
            return try await child(parentURI: parentURI, name: name, parent: parent)
        }
    }

    /// 修改条目：[newParent] 为回收站 → 删除到回收站；从回收站移出 → 还原；其余按需重命名、移动、覆盖内容
    func modify(fileId id: String, newName: String?, newParent: NSFileProviderItemIdentifier?, contents: URL?) async throws -> FileProviderItem {
        var file = try await files.infoById(id: id, extended: false, folderSummary: false)
        let inTrash = FileProviderIdentity.isInTrash(file.path)
        if newParent == .trashContainer {
            if !inTrash { _ = try await files.delete(uris: [file.path], unlink: false, skipSoftDelete: false) }
            return try await item(forFileId: id)
        }
        if inTrash {
            _ = try await files.restore(uris: [file.path])
            file = try await files.infoById(id: id, extended: false, folderSummary: false)
        }
        if let newName, newName != file.name {
            file = try await files.rename(uri: file.path, newName: newName)
        }
        if let newParent {
            let destination = try await containerURI(for: newParent)
            let current = FileProviderIdentity.parentURI(of: file.path)
            if FileProviderIdentity.normalized(destination) != current.map(FileProviderIdentity.normalized) {
                _ = try await files.move(uris: [file.path], dst: destination)
                file = try await files.infoById(id: id, extended: false, folderSummary: false)
            }
        }
        if let contents, !file.isFolder, let parentURI = FileProviderIdentity.parentURI(of: file.path) {
            _ = try await upload(contents: contents, name: file.name, parentURI: parentURI, parent: .rootContainer, overwrite: true)
        }
        return try await item(forFileId: id)
    }

    /// 删除到回收站；已在回收站的彻底删除；已不存在视为成功
    func delete(fileId id: String) async throws {
        let file: FileItem
        do {
            file = try await files.infoById(id: id, extended: false, folderSummary: false)
        } catch let error where Self.isNoSuchItem(error) {
            return
        }
        _ = try await files.delete(uris: [file.path], unlink: false, skipSoftDelete: FileProviderIdentity.isInTrash(file.path))
    }

    /// 复制到扩展临时目录后在本进程内上传（[parent] 只用于构造返回条目）
    private func upload(
        contents: URL,
        name: String,
        parentURI: String,
        parent: NSFileProviderItemIdentifier,
        overwrite: Bool
    ) async throws -> FileProviderItem {
        let dir = try FileProviderStaging.makeDirectory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let staged = dir.appending(path: name, directoryHint: .notDirectory)
        try FileManager.default.copyItem(at: contents, to: staged)
        let values = try staged.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
        let modified = values.contentModificationDate.map { KotlinLong(longLong: Int64($0.timeIntervalSince1970 * 1000)) }
        let ext = (name as NSString).pathExtension
        let done = try await CoreGraph.shared.directTransferRunner().uploadFile(
            accountId: accountId,
            sourceRef: staged.path(percentEncoded: false),
            name: name,
            size: Int64(values.fileSize ?? 0),
            lastModifiedMillis: modified,
            mimeType: ext.isEmpty ? nil : UTType(filenameExtension: ext)?.preferredMIMEType,
            targetDirUri: parentURI,
            overwrite: overwrite
        )
        let file = try await files.info(uri: done.targetUri, extended: false, folderSummary: false)
        return await record(file, parent: parent)
    }

    // MARK: - 标识解析

    /// 容器标识 → 目录 URI
    private func containerURI(for identifier: NSFileProviderItemIdentifier) async throws -> String {
        switch FileProviderIdentity.target(of: identifier) {
        case .root: return FileProviderIdentity.rootURI
        case .trash: return FileProviderIdentity.trashURI
        case .workingSet: throw NSFileProviderError(.noSuchItem)
        case let .file(id):
            if let uri = await index.uri(forId: id) { return uri }
            let file = try await files.infoById(id: id, extended: false, folderSummary: false)
            await index.record(id: file.id, uri: file.path)
            return file.path
        }
    }

    /// 文件 URI → 父项标识（根/回收站直接得出，其余按父目录 URI 查 id）
    private func parentIdentifier(ofURI uri: String) async throws -> NSFileProviderItemIdentifier {
        if let known = FileProviderIdentity.knownParent(ofURI: uri) { return known }
        guard let parentURI = FileProviderIdentity.parentURI(of: uri) else { return .rootContainer }
        if let id = await index.id(forURI: parentURI) {
            return FileProviderIdentity.identifier(fileId: id, uri: parentURI)
        }
        let parent = try await files.info(uri: parentURI, extended: false, folderSummary: false)
        await index.record(id: parent.id, uri: parent.path)
        return FileProviderIdentity.identifier(fileId: parent.id, uri: parent.path)
    }

    private func record(_ file: FileItem, parent: NSFileProviderItemIdentifier) async -> FileProviderItem {
        await index.record(id: file.id, uri: file.path)
        return FileProviderItem(file: file, parent: parent, containerCapability: nil)
    }

    private static func isCollision(_ error: any Error) -> Bool {
        providerCode(error) == NSFileProviderError.filenameCollision.rawValue
    }

    private static func isNoSuchItem(_ error: any Error) -> Bool {
        providerCode(error) == NSFileProviderError.noSuchItem.rawValue
    }

    private static func providerCode(_ error: any Error) -> Int? {
        let mapped = FileProviderErrorMap.providerError(error) as NSError
        return mapped.domain == NSFileProviderErrorDomain ? mapped.code : nil
    }
}
