import SwiftUI

/// 压缩包条目：左侧勾选（解压所选），点按目录进入
struct ArchiveEntryRow: View {
    let entry: ArchiveEntryItem
    let canSelect: Bool
    let onToggle: () -> Void
    let onOpen: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            if canSelect {
                Button(
                    String(localized: entry.selected ? "取消选择" : "选择"),
                    systemImage: entry.selected ? "checkmark.circle.fill" : "circle",
                    action: onToggle
                )
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .accessibilityIdentifier("archive.select." + entry.name)
            }
            if entry.isDirectory {
                Button(action: onOpen) {
                    ArchiveEntryLabel(entry: entry)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("archive.entry." + entry.name)
            } else {
                ArchiveEntryLabel(entry: entry)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("archive.entry." + entry.name)
            }
        }
    }
}
