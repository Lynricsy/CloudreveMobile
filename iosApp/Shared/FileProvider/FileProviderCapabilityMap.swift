import FileProvider
import OpenCloudreveCore

/// 服务端能力位（`NavigatorCapability`）→ `NSFileProviderItemCapabilities`
enum FileProviderCapabilityMap {
    /// - Parameters:
    ///   - isFolder: 是否目录
    ///   - inTrash: 是否位于回收站（只允许彻底删除与还原）
    ///   - has: 能力位查询；条目与所在目录都没有能力位时调用方应传“全部允许”（由服务端最终校验）
    static func capabilities(isFolder: Bool, inTrash: Bool, has: (Int32) -> Bool) -> NSFileProviderItemCapabilities {
        let bits = NavigatorCapability.shared
        var result: NSFileProviderItemCapabilities = []
        if inTrash {
            if has(bits.DELETE_FILE) { result.insert(.allowsDeleting) }
            if has(bits.RESTORE) { result.insert(.allowsReparenting) }
            return result
        }
        if isFolder {
            if has(bits.LIST_CHILDREN) || has(bits.ENTER_FOLDER) { result.insert(.allowsContentEnumerating) }
            if has(bits.CREATE_FILE) || has(bits.UPLOAD_FILE) { result.insert(.allowsAddingSubItems) }
        } else {
            if has(bits.DOWNLOAD_FILE) { result.insert(.allowsReading) }
            if has(bits.UPLOAD_FILE) { result.insert(.allowsWriting) }
        }
        if has(bits.RENAME_FILE) { result.formUnion([.allowsRenaming, .allowsReparenting]) }
        if has(bits.SOFT_DELETE) { result.insert(.allowsTrashing) }
        if has(bits.DELETE_FILE) { result.insert(.allowsDeleting) }
        return result
    }

    /// 按条目自身能力位、其次所在目录（列表 `props.capability`）的能力位计算；两者都没有时全部允许
    static func capabilities(isFolder: Bool, inTrash: Bool, own: BoolSet?, container: BoolSet?) -> NSFileProviderItemCapabilities {
        guard let set = own ?? container else { return capabilities(isFolder: isFolder, inTrash: inTrash) { _ in true } }
        return capabilities(isFolder: isFolder, inTrash: inTrash) { set.has(bit: $0) }
    }
}

/// 条目版本：内容版本 = 当前版本实体（`primary_entity`，没有时用修改时间），
/// 元数据版本 = 名称 + 修改时间（重命名/移动/元数据变化都会改变修改时间或名称）
enum FileProviderVersion {
    static func content(primaryEntity: String?, updatedAtMillis: Int64?) -> Data {
        if let primaryEntity, !primaryEntity.isEmpty { return Data("e:\(primaryEntity)".utf8) }
        return Data("t:\(updatedAtMillis ?? 0)".utf8)
    }

    static func metadata(name: String, updatedAtMillis: Int64?) -> Data {
        Data("\(updatedAtMillis ?? 0)/\(name)".utf8)
    }
}
