import SwiftUI
import UIKit
import OpenCloudreveCore

/// 直链界面：选项（强制下载、复制带文件名）、限制提示、生成结果与失败项、单个文件的已有直链
struct DirectLinksContent: View {
    @Bindable var host: DirectLinksHost

    @State private var failure: String?
    @State private var copiedAll = false

    var body: some View {
        let state = host.state
        Form {
            Section {
                Toggle("强制下载", isOn: $host.forceDownload)
                    .accessibilityIdentifier("directLinks.forceDownload")
                Toggle("链接带文件名", isOn: $host.showFileName)
                    .accessibilityIdentifier("directLinks.showFileName")
            } footer: {
                Text("直链长期有效，任何拿到链接的人都可以下载该文件。")
            }
            if state.exceedsLimit || state.folderNotAllowed {
                Section {
                    if state.exceedsLimit {
                        limitNotice(state.batchLimit)
                    }
                    if state.folderNotAllowed {
                        Label("当前用户组不允许为文件夹生成直链", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    }
                }
            }
            Section {
                PrimaryActionButton(title: "生成", busy: state.generating) {
                    host.send(DirectLinksIntentGenerate.shared)
                }
                .disabled(!state.canGenerate)
                .accessibilityIdentifier("directLinks.generate")
            }
            if !state.generated.isEmpty {
                Section("已生成") {
                    ForEach(state.generated, id: \.fileUri) { link in
                        LinkCopyRow(
                            title: link.name.isEmpty ? nil : link.name,
                            link: link.displayLink,
                            copyText: state.showFileName ? "[\(link.name)] \(link.displayLink)" : link.displayLink,
                            linkIdentifier: "directLinks.link"
                        )
                    }
                    Button(copyAllTitle, systemImage: copiedAll ? "checkmark" : "doc.on.doc") {
                        UIPasteboard.general.string = state.copyText
                        copiedAll = true
                    }
                    .sensoryFeedback(.success, trigger: copiedAll)
                    .accessibilityIdentifier("directLinks.copyAll")
                }
            }
            if !state.failures.isEmpty {
                Section("未能生成") {
                    ForEach(state.failures, id: \.uri) { failure in
                        LabeledContent {
                            Text(ErrorText.message(failure.error))
                        } label: {
                            Text(verbatim: CrUri.companion.parseOrNull(s: failure.uri)?.name() ?? failure.uri)
                        }
                    }
                }
            }
            if state.canManage {
                existingSection(state)
            }
        }
        .refreshable { host.send(DirectLinksIntentRefresh.shared) }
        .errorMessageAlert(message: $failure)
        .task(id: copiedAll) {
            guard copiedAll else { return }
            try? await Task.sleep(for: .seconds(2))
            copiedAll = false
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .generated, .deleted:
                    break
                case let .failed(failed):
                    failure = ErrorText.message(failed.error)
                }
            }
        }
    }

    private var copyAllTitle: LocalizedStringKey {
        copiedAll ? "已复制" : "全部复制"
    }

    @ViewBuilder
    private func limitNotice(_ limit: Int32) -> some View {
        if limit <= 0 {
            Label("当前用户组不允许生成直链", systemImage: "exclamationmark.triangle")
                .foregroundStyle(.orange)
        } else {
            Label("一次最多为 \(Int(limit)) 个文件生成直链", systemImage: "exclamationmark.triangle")
                .foregroundStyle(.orange)
        }
    }

    /// 单个文件的已有直链：复制、删除
    @ViewBuilder
    private func existingSection(_ state: DirectLinksState) -> some View {
        if let error = state.error {
            Section("已有直链") {
                ErrorRow(message: ErrorText.message(error))
                Button("重试") { host.send(DirectLinksIntentRefresh.shared) }
            }
        } else if state.loading {
            Section("已有直链") {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        } else if !state.existing.isEmpty {
            Section("已有直链") {
                ForEach(state.existing, id: \.id) { managed in
                    LinkCopyRow(
                        link: managed.displayLink,
                        note: String(localized: "已下载 \(Int(managed.link.downloaded)) 次", comment: "直链下载次数")
                    )
                    .disabled(state.deletingId == managed.id)
                    .swipeActions {
                        Button("删除", systemImage: "trash", role: .destructive) {
                            host.send(DirectLinksIntentDelete(id: managed.id))
                        }
                    }
                    .contextMenu {
                        Button("删除", systemImage: "trash", role: .destructive) {
                            host.send(DirectLinksIntentDelete(id: managed.id))
                        }
                    }
                }
            }
        }
    }
}
