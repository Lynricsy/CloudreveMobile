import SwiftUI

/// 图片信息：文件名、大小、EXIF、位置（逆地理编码）
struct ImageInfoSheet: View {
    let info: ImageInfo
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("名称") { Text(verbatim: info.name) }
                    LabeledContent("大小") { Text(verbatim: FileFormat.bytes(info.size)) }
                }
                Section("EXIF") {
                    if info.exif.isEmpty {
                        Text("没有 EXIF 信息")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(info.exif) { row in
                        MetadataRowView(row: row)
                    }
                }
                .accessibilityIdentifier("image.exif")
                if !info.geocoding.isEmpty {
                    Section("位置") {
                        ForEach(info.geocoding) { row in
                            MetadataRowView(row: row)
                        }
                    }
                }
            }
            .navigationTitle("图片信息")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                        .accessibilityIdentifier("image.infoDone")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
