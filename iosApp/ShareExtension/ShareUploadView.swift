import SwiftUI
import OpenCloudreveCore

/// 分享扩展的上传表单：账户（多账户时可选）+ 目标目录 + 文件列表；切换账户时重建上传 VM（目标回到“我的文件”）
struct ShareUploadView: View {
    let session: ShareSession
    let accounts: [Account]
    let initialCandidates: [UploadCandidate]
    let skipped: Int

    @State private var accountId: String
    @State private var candidates: [UploadCandidate]

    init(session: ShareSession, accounts: [Account], initialCandidates: [UploadCandidate], skipped: Int) {
        self.session = session
        self.accounts = accounts
        self.initialCandidates = initialCandidates
        self.skipped = skipped
        _accountId = State(initialValue: (accounts.first { $0.isActive } ?? accounts[0]).id)
        _candidates = State(initialValue: initialCandidates)
    }

    var body: some View {
        ViewModelScope({
            CoreGraph.shared.uploadPickerViewModel(accountId: accountId, targetUri: "cloudreve://my", policy: nil)
        }) { host in
            ShareUploadForm(
                host: host,
                session: session,
                accounts: accounts,
                accountId: $accountId,
                candidates: $candidates,
                skipped: skipped
            )
        }
        .id(accountId)
    }
}

private struct ShareUploadForm: View {
    let host: UploadPickerHost
    let session: ShareSession
    let accounts: [Account]
    @Binding var accountId: String
    @Binding var candidates: [UploadCandidate]
    let skipped: Int

    @State private var errorMessage: String?

    var body: some View {
        let state = host.state
        Form {
            if accounts.count > 1 {
                Section("账户") {
                    Picker("账户", selection: $accountId) {
                        ForEach(accounts, id: \.id) { account in
                            Text(verbatim: "\(account.nickname) @ \(account.host)").tag(account.id)
                        }
                    }
                }
            }
            UploadFormSections(host: host, accountId: accountId, candidates: $candidates)
            if skipped > 0 {
                Section {
                    Label(
                        String(localized: "有 \(skipped) 项无法读取或不支持上传", comment: "分享扩展跳过的附件数"),
                        systemImage: "exclamationmark.triangle"
                    )
                    .foregroundStyle(.secondary)
                }
            }
            if let errorMessage {
                Section { ErrorRow(message: errorMessage) }
            }
            Section {
                PrimaryActionButton(title: "上传", busy: state.enqueuing) {
                    host.send(UploadPickerIntentEnqueue(
                        sources: candidates.map { $0.uploadSource() },
                        targetUri: nil,
                        overwrite: false
                    ))
                }
                .disabled(candidates.isEmpty || state.enqueuing)
            }
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消", role: .cancel) {
                    candidates.forEach { $0.discard() }
                    session.cancel()
                }
            }
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case let .enqueued(enqueued):
                    let accepted = enqueued.result.accepted.count
                    let rejected = enqueued.result.rejected.map { "\($0.name)：\(UploadRejectionText.message($0.reason))" }
                    session.phase = .enqueuing
                    Task {
                        // 等执行器把第一批分片交给后台会话后再关闭扩展
                        await BackgroundTransferExecutor.current?.drain()
                        session.phase = .done(accepted: accepted, rejected: rejected)
                    }
                case let .failed(failure):
                    errorMessage = ErrorText.message(failure.error)
                }
            }
        }
    }
}
