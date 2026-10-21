import SwiftUI
import OpenCloudreveCore

/// 分享设置表单：文件名、私密与密码、下载次数（仅文件）、有效期、目录选项（仅目录）、提交
struct ShareEditForm: View {
    @Bindable var host: ShareEditHost
    let failure: String?
    let submit: () -> Void

    var body: some View {
        let state = host.state
        Form {
            Section {
                Label {
                    Text(verbatim: state.name)
                        .lineLimit(2)
                        .truncationMode(.middle)
                } icon: {
                    Image(systemName: state.isFolder ? "folder.fill" : "doc.fill")
                }
            }

            Section {
                Toggle("私密分享", isOn: $host.isPrivate)
                    .accessibilityIdentifier("shareEdit.private")
                if state.isPrivate {
                    TextField("密码", text: $host.password, prompt: Text("留空自动生成"))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("shareEdit.password")
                }
            } footer: {
                if !state.passwordValid {
                    Text("密码须为 1–32 位字母或数字")
                        .foregroundStyle(.red)
                } else if state.isPrivate {
                    Text("访客需要输入密码才能访问。")
                }
            }

            if state.showDownloadLimit {
                ShareDownloadLimitSection(host: host)
            }

            ShareExpireSection(host: host)

            if state.showFolderOptions {
                Section {
                    Toggle("允许访客浏览目录", isOn: $host.shareView)
                        .accessibilityIdentifier("shareEdit.shareView")
                    Toggle("自动展示 README", isOn: $host.showReadme)
                        .accessibilityIdentifier("shareEdit.showReadme")
                } header: {
                    Text("目录选项")
                } footer: {
                    Text("关闭浏览后，访客无法查看目录中的内容。")
                }
            }

            if let failure {
                Section {
                    ErrorRow(message: failure)
                }
            }

            Section {
                PrimaryActionButton(title: state.isEditing ? "保存" : "创建分享", busy: state.submitting, action: submit)
                    .disabled(!state.canSubmit)
                    .accessibilityIdentifier("shareEdit.submit")
            }
        }
    }
}
