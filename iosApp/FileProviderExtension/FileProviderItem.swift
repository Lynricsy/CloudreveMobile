import FileProvider
import OpenCloudreveCore
import UniformTypeIdentifiers

/// 交给系统的条目快照（不可变，可跨线程传递）
final class FileProviderItem: NSObject, NSFileProviderItem, Sendable {
    let itemIdentifier: NSFileProviderItemIdentifier
    let parentItemIdentifier: NSFileProviderItemIdentifier
    let filename: String
    let capabilities: NSFileProviderItemCapabilities
    private let isFolder: Bool
    private let size: Int64
    private let created: Date?
    private let modified: Date?
    private let contentVersion: Data
    private let metadataVersion: Data

    init(
        identifier: NSFileProviderItemIdentifier,
        parent: NSFileProviderItemIdentifier,
        filename: String,
        isFolder: Bool,
        size: Int64,
        created: Date?,
        modified: Date?,
        capabilities: NSFileProviderItemCapabilities,
        contentVersion: Data,
        metadataVersion: Data
    ) {
        itemIdentifier = identifier
        parentItemIdentifier = parent
        self.filename = filename
        self.isFolder = isFolder
        self.size = size
        self.created = created
        self.modified = modified
        self.capabilities = capabilities
        self.contentVersion = contentVersion
        self.metadataVersion = metadataVersion
        super.init()
    }

    /// 由服务端文件构造；[containerCapability] 为所在目录列表的 `props.capability`（条目自身没有能力位时使用）
    convenience init(file: FileItem, parent: NSFileProviderItemIdentifier, containerCapability: BoolSet?) {
        let updatedMillis = file.updatedAt?.toEpochMilliseconds()
        self.init(
            identifier: FileProviderIdentity.identifier(fileId: file.id, uri: file.path),
            parent: parent,
            filename: file.name,
            isFolder: file.isFolder,
            size: file.size,
            created: file.createdAt.map { Date(timeIntervalSince1970: TimeInterval($0.toEpochMilliseconds()) / 1000) },
            modified: updatedMillis.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) },
            capabilities: FileProviderCapabilityMap.capabilities(
                isFolder: file.isFolder,
                inTrash: FileProviderIdentity.isInTrash(file.path),
                own: file.capability,
                container: containerCapability
            ),
            contentVersion: FileProviderVersion.content(primaryEntity: file.primaryEntity, updatedAtMillis: updatedMillis),
            metadataVersion: FileProviderVersion.metadata(name: file.name, updatedAtMillis: updatedMillis)
        )
    }

    /// “我的文件”根（名称为域显示名）
    static func root(name: String) -> FileProviderItem {
        container(.rootContainer, name: name)
    }

    /// 回收站根
    static func trash() -> FileProviderItem {
        container(.trashContainer, name: String(localized: "回收站"))
    }

    private static func container(_ identifier: NSFileProviderItemIdentifier, name: String) -> FileProviderItem {
        FileProviderItem(
            identifier: identifier,
            parent: identifier,
            filename: name,
            isFolder: true,
            size: 0,
            created: nil,
            modified: nil,
            capabilities: identifier == .trashContainer
                ? [.allowsContentEnumerating]
                : [.allowsContentEnumerating, .allowsAddingSubItems],
            contentVersion: Data("container".utf8),
            metadataVersion: Data(name.utf8)
        )
    }

    var contentType: UTType {
        if isFolder { return .folder }
        let ext = (filename as NSString).pathExtension
        return ext.isEmpty ? .data : UTType(filenameExtension: ext) ?? .data
    }

    var documentSize: NSNumber? {
        isFolder ? nil : NSNumber(value: size)
    }

    var creationDate: Date? { created }

    var contentModificationDate: Date? { modified }

    var itemVersion: NSFileProviderItemVersion {
        NSFileProviderItemVersion(contentVersion: contentVersion, metadataVersion: metadataVersion)
    }
}
