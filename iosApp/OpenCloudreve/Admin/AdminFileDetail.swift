import SwiftUI
import OpenCloudreveCore

/// 文件详情：基本信息、存储策略、版本实体、分享与直链；可获取下载地址与删除
struct AdminFileDetail: View {
    let file: AdminFile
    let fetchingUrl: Bool
    let deleting: Bool
    let fetchUrl: () -> Void
    let delete: () -> Void
    @State private var confirmingDelete = false

    var body: some View {
        Form {
            Section("信息") {
                LabeledContent("名称") { Text(verbatim: file.name) }
                LabeledContent("大小") { Text(verbatim: SettingsFormat.bytes(file.size)) }
                if let owner = file.edges.owner {
                    LabeledContent("所有者") { Text(verbatim: "\(owner.nick) (\(owner.email))") }
                }
                if let policy = file.edges.storagePolicies {
                    LabeledContent("存储策略") { Text(verbatim: policy.name) }
                }
                if let created = SettingsFormat.dateTime(file.createdAt) {
                    LabeledContent("创建时间") { Text(verbatim: created) }
                }
                if let updated = SettingsFormat.dateTime(file.updatedAt) {
                    LabeledContent("更新时间") { Text(verbatim: updated) }
                }
                LabeledContent("ID") { Text(verbatim: "\(file.id)") }
            }

            if !file.edges.entities.isEmpty {
                Section("版本与实体") {
                    ForEach(file.edges.entities, id: \.id) { entity in
                        LabeledContent {
                            Text(verbatim: SettingsFormat.bytes(entity.size))
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: "#\(entity.id)")
                                if let created = SettingsFormat.dateTime(entity.createdAt) {
                                    Text(verbatim: created)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }

            if !file.directLinkMap.isEmpty {
                Section("直链") {
                    ForEach(file.directLinkMap.keys.sorted(), id: \.self) { key in
                        CopyableRow(title: "直链 \(key)", value: file.directLinkMap[key] ?? "")
                    }
                }
            }

            if !file.edges.shares.isEmpty {
                Section("分享") {
                    ForEach(file.edges.shares, id: \.id) { share in
                        LabeledContent {
                            Text("浏览 \(Int(share.views)) · 下载 \(Int(share.downloads))")
                        } label: {
                            Text(verbatim: "#\(share.id)")
                        }
                    }
                }
            }

            Section {
                Button(action: fetchUrl) {
                    HStack {
                        Label("获取下载地址", systemImage: "link")
                        Spacer()
                        if fetchingUrl {
                            ProgressView()
                        }
                    }
                }
                .disabled(fetchingUrl)
                Button("删除文件", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                    .disabled(deleting)
            }
        }
        .confirmationDialog("删除此文件？", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("删除文件", role: .destructive, action: delete)
        } message: {
            Text("文件将被永久删除，且不可恢复。")
        }
    }
}
