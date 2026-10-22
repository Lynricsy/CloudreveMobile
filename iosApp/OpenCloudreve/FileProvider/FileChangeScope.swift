import OpenCloudreveCore

/// 文件操作成功后，系统“文件”工作集中需要更新的目录（其缓存列表已过时）
enum FileChangeScope {
    /// [operation] 改变了哪些目录的内容（规范化后去重，保持顺序）；不改变目录内容的操作为空
    static func affectedDirectories(of operation: FileOperation) -> [String] {
        let directories: [String]
        switch onEnum(of: operation) {
        case let .rename(rename):
            directories = parents(of: [rename.uri])
        case let .move(move):
            directories = parents(of: move.uris) + [move.dst]
        case let .copy(copy):
            directories = [copy.dst]
        case let .delete(delete):
            // 进回收站的同时回收站内容也变了
            directories = parents(of: delete.uris) + (delete.skipSoftDelete ? [] : [FileProviderIdentity.trashURI])
        case let .restore(restore):
            directories = parents(of: restore.uris)
        case .emptyTrash:
            directories = [FileProviderIdentity.trashURI]
        case let .createFolder(create):
            directories = [create.parentUri]
        case let .createFile(create):
            directories = [create.parentUri]
        case let .setThumbnailDisabled(change):
            directories = parents(of: change.uris)
        case let .patchMetadata(change):
            directories = parents(of: change.uris)
        case .pin, .unpin, .createSourceLinks, .deleteSourceLink, .mountPolicy:
            directories = []
        }
        var seen = Set<String>()
        return directories.filter { seen.insert(FileProviderIdentity.normalized($0)).inserted }
    }

    private static func parents(of uris: [String]) -> [String] {
        uris.compactMap(FileProviderIdentity.parentURI(of:))
    }
}
