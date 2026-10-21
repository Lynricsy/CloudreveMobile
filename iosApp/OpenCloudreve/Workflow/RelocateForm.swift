import SwiftUI
import OpenCloudreveCore

/// 转移存储策略表单：策略列表（负载均衡与加密策略置灰不可选）
struct RelocateForm: View {
    let host: ArchiveTaskHost
    let onCreated: @MainActor () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var isCreatedPresented = false
    @State private var errorMessage: String?

    var body: some View {
        let state = host.state
        Form {
            Section {
                if state.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else {
                    ForEach(state.policies, id: \.id) { option in
                        policyRow(option, selected: option.id == state.selectedPolicyId)
                    }
                }
            } header: {
                Text("目标存储策略")
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    if state.srcUris.count > 1 {
                        Text("将“\(state.sourceName)”等 \(state.srcUris.count) 项转移到所选存储策略", comment: "转移多个文件，参数为第一个文件名与总数")
                    } else {
                        Text("将“\(state.sourceName)”转移到所选存储策略", comment: "转移单个文件，参数为文件名")
                    }
                    if state.policies.contains(where: { !$0.selectable }) {
                        Text("负载均衡与加密的存储策略不能作为转移目标。")
                    }
                }
            }

            if let loadError = state.loadError {
                ErrorRow(message: ErrorText.message(loadError))
            }
            if !state.permitted {
                ErrorRow(message: String(localized: "当前用户组无权转移存储策略"))
            }
            if let errorMessage {
                ErrorRow(message: errorMessage)
            }

            Section {
                PrimaryActionButton(title: "开始转移", busy: state.submitting) {
                    errorMessage = nil
                    host.send(ArchiveTaskIntentSubmit.shared)
                }
                .disabled(!state.canSubmit)
                .accessibilityIdentifier("relocate.submit")
            }
            .listRowBackground(Color.clear)
        }
        .navigationTitle("转移存储策略")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", systemImage: "xmark") { dismiss() }
            }
        }
        .workflowCreatedAlert(
            "已创建转移任务",
            isPresented: $isCreatedPresented,
            message: String(localized: "可以在“任务”中查看进度。")
        ) {
            onCreated()
            dismiss()
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .created:
                    isCreatedPresented = true
                case let .failed(failed):
                    errorMessage = ErrorText.message(failed.error)
                }
            }
        }
    }

    private func policyRow(_ option: RelocatePolicyOption, selected: Bool) -> some View {
        Button {
            host.send(ArchiveTaskIntentSelectPolicy(policyId: option.id))
        } label: {
            HStack {
                Text(verbatim: option.name)
                    .foregroundStyle(option.selectable ? .primary : .secondary)
                Spacer()
                if selected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)
                }
            }
        }
        .disabled(!option.selectable)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("relocate.policy.\(option.name)")
    }
}
