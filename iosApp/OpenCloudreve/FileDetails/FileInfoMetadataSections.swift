import SwiftUI
import OpenCloudreveCore

/// 信息页的媒体元数据（EXIF、位置、音乐、流媒体，键名用预览器的元数据标签）与自定义属性（只读）
struct FileInfoMetadataSections: View {
    let metadata: [String: String]
    /// 站点自定义属性定义（值在 `props:<id>`）
    let customProps: [CustomPropDefinition]

    var body: some View {
        group("EXIF", prefix: MetadataKeys.shared.EXIF_PREFIX)
        group("位置", prefix: MetadataKeys.shared.GEOCODING_PREFIX)
        group("音乐", prefix: MetadataKeys.shared.MUSIC_PREFIX)
        group("流媒体", prefix: MetadataKeys.shared.STREAM_PREFIX)
        let props = propEntries
        if !props.isEmpty {
            Section("自定义属性") {
                ForEach(props, id: \.id) { entry in
                    CustomPropValueView(name: entry.definition?.name ?? entry.id, definition: entry.definition, value: entry.value)
                }
            }
        }
    }

    @ViewBuilder
    private func group(_ title: LocalizedStringKey, prefix: String) -> some View {
        let rows = rows(prefix: prefix)
        if !rows.isEmpty {
            Section(title) {
                ForEach(rows) { row in
                    MetadataRowView(row: row)
                }
            }
        }
    }

    /// 某前缀的条目（去掉前缀，按键排序）
    private func rows(prefix: String) -> [MetadataRow] {
        metadata
            .filter { $0.key.hasPrefix(prefix) }
            .map { MetadataRow(entry: MetadataEntry(key: String($0.key.dropFirst(prefix.count)), value: $0.value)) }
            .sorted { $0.key < $1.key }
    }

    /// 已设置的自定义属性：按站点定义顺序，定义已删除的排在最后
    private var propEntries: [CustomPropEntry] {
        let prefix = MetadataKeys.shared.CUSTOM_PROPS_PREFIX
        let values = metadata.filter { $0.key.hasPrefix(prefix) }
        var entries: [CustomPropEntry] = customProps.compactMap { def in
            guard let value = values[prefix + def.id] else { return nil }
            return CustomPropEntry(id: def.id, definition: def, value: value)
        }
        let known = Set(customProps.map(\.id))
        let orphans = values
            .map { (id: String($0.key.dropFirst(prefix.count)), value: $0.value) }
            .filter { !known.contains($0.id) }
            .sorted { $0.id < $1.id }
            .map { CustomPropEntry(id: $0.id, definition: nil, value: $0.value) }
        entries.append(contentsOf: orphans)
        return entries
    }
}
