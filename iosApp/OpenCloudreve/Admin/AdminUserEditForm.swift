import SwiftUI
import OpenCloudreveCore

struct AdminUserEditForm: View {
    let accountId: String
    @Bindable var host: AdminUserEditHost
    let onChanged: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var failure: String?
    @State private var webPage: HandoffPage?
    @State private var filesUri: String?
    @State private var confirmingDelete = false
    @State private var saved = false

    var body: some View {
        Form {
            if host.state.loading && host.state.user == nil {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
            if let error = host.state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(AdminUserEditIntentRefresh.shared) }
                }
            }

            if let user = host.state.user {
                Section("基本信息") {
                    TextField("邮箱", text: $host.email)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("昵称", text: $host.nick)
                    Picker("状态", selection: $host.status) {
                        ForEach(host.state.statusOptions, id: \.self) { status in
                            Text(verbatim: AdminLabels.userStatus(status)).tag(status)
                        }
                    }
                    .disabled(host.state.statusAndGroupLocked)
                    Picker("用户组", selection: $host.groupId) {
                        if !host.state.groups.contains(where: { $0.id == host.state.form.groupId }) {
                            Text(verbatim: user.edges.group?.name ?? "#\(host.state.form.groupId)").tag(host.state.form.groupId)
                        }
                        ForEach(host.state.groups, id: \.id) { group in
                            Text(verbatim: group.name).tag(group.id)
                        }
                    }
                    .disabled(host.state.statusAndGroupLocked)
                }

                Section {
                    SecureField("新密码（留空不修改）", text: $host.newPassword)
                        .textContentType(.newPassword)
                    if user.twoFaEnabled {
                        Toggle("清除两步验证", isOn: $host.clearTwoFactor)
                    }
                } header: {
                    Text("安全")
                } footer: {
                    if !host.state.form.newPassword.isEmpty && host.state.form.newPassword.count < 6 {
                        Text("新密码至少 6 位")
                            .foregroundStyle(.red)
                    }
                }

                AdminUserStorageSection(
                    used: user.storage,
                    capacity: user.capacity,
                    calibrating: host.state.calibrating,
                    calibrate: { host.send(AdminUserEditIntentCalibrate.shared) }
                )

                Section {
                    Button("浏览此用户的文件", systemImage: "folder") {
                        host.send(AdminUserEditIntentOpenFiles.shared)
                    }
                    .accessibilityIdentifier("adminUser.files")
                    if let created = SettingsFormat.dateTime(user.createdAt) {
                        LabeledContent("注册时间") { Text(verbatim: created) }
                    }
                    LabeledContent("ID") { Text(verbatim: "\(user.id) · \(user.hashId)") }
                }

                if host.state.canDelete || host.state.deleting {
                    Section {
                        Button("删除用户", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                            .disabled(host.state.deleting)
                    } footer: {
                        Text("删除用户会同时删除其全部文件，且不可恢复。")
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                AdminWebButton(availability: host.state.handoff, opening: host.state.openingWeb) {
                    host.send(AdminUserEditIntentOpenInWeb.shared)
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                if host.state.saving {
                    ProgressView()
                } else {
                    Button("保存", systemImage: saved ? "checkmark.circle.fill" : "checkmark") {
                        host.send(AdminUserEditIntentSave.shared)
                    }
                    .disabled(!host.state.canSave)
                    .accessibilityIdentifier("adminUser.save")
                }
            }
        }
        .confirmationDialog("删除此用户？", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("删除用户", role: .destructive) { host.send(AdminUserEditIntentDelete.shared) }
        } message: {
            Text("用户的全部文件将被一并删除，且不可恢复。")
        }
        .navigationDestination(item: $filesUri) { uri in
            FileListScreen(accountId: accountId, uri: uri)
        }
        .sheet(item: $webPage) { page in
            HandoffSafariView(url: page.url)
                .ignoresSafeArea()
        }
        .sensoryFeedback(.success, trigger: saved)
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .saved:
                    saved.toggle()
                    onChanged()
                case .calibrated:
                    break
                case .deleted:
                    onChanged()
                    dismiss()
                case let .openFiles(open):
                    filesUri = open.uri
                case let .openUrl(open):
                    if let url = URL(string: open.url) { webPage = HandoffPage(url: url) }
                case let .failed(failed):
                    failure = ErrorText.message(failed.error)
                }
            }
        }
        .errorMessageAlert(message: $failure)
    }
}
