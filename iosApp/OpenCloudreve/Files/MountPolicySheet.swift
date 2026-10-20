import SwiftUI
import OpenCloudreveCore

/// 目录挂载存储策略：列出可用策略（`GET /user/setting/policies`），选中后回调策略 id（空串 = 恢复继承上级）
struct MountPolicySheet: View {
    let accountId: String
    let folder: FileItem
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var policies: [PolicyOption] = []
    @State private var loading = true
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        onPick("")
                        dismiss()
                    } label: {
                        Label("继承上级目录的策略", systemImage: "arrow.turn.left.up")
                    }
                }
                Section {
                    if loading {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    }
                    if let errorMessage {
                        ErrorRow(message: errorMessage)
                    }
                    ForEach(policies) { policy in
                        Button {
                            onPick(policy.id)
                            dismiss()
                        } label: {
                            LabeledContent {
                                Text(verbatim: policy.type)
                            } label: {
                                Text(verbatim: policy.name)
                                    .foregroundStyle(.primary)
                            }
                        }
                    }
                } header: {
                    Text("存储策略")
                } footer: {
                    Text("之后上传到此文件夹（及未单独设置的子文件夹）的文件将保存到所选策略。已有文件不受影响。")
                }
            }
            .navigationTitle("挂载存储策略")
            .navigationSubtitle(folder.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", role: .cancel) { dismiss() }
                }
            }
            .task {
                do {
                    policies = try await Self.loadPolicies(accountId: accountId)
                } catch {
                    errorMessage = FileErrors.message(error)
                }
                loading = false
            }
        }
    }

    /// 可发送的策略摘要
    struct PolicyOption: Identifiable, Sendable {
        let id: String
        let name: String
        let type: String
    }

    private nonisolated static func loadPolicies(accountId: String) async throws -> [PolicyOption] {
        let policies = try await CoreGraph.shared.userSettings(accountId: accountId).policies()
        return policies.map { PolicyOption(id: $0.id, name: $0.name, type: $0.type) }
    }
}
