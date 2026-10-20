import Foundation
import OpenCloudreveCore

/// 一条元数据（键已去掉前缀，如 `exif:Model` → `Model`）
struct MetadataRow: Identifiable, Hashable, Sendable {
    let key: String
    let value: String

    var id: String { key }

    init(entry: MetadataEntry) {
        key = entry.key
        value = entry.value
    }
}
