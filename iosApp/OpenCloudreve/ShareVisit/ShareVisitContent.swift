import SwiftUI
import OpenCloudreveCore

/// 分享浏览内容：错误/加载/密码 → 头部（分享者、访问数、有效期）+ 目录内容，README 弹层，保存到我的文件（快捷方式）
struct ShareVisitContent: View {
    let accountId: String
    @Bindable var host: ShareVisitHost

    @State private var password = ""
    @State private var pickingDestination = false
    @State private var showingReadme = false
    @State private var infoFile: FileInfoTarget?
    @State private var message: ShareVisitMessage?
    @State private var showingMessage = false

    var body: some View {
        let state = host.state
        Group {
            if let error = state.error, !state.unlocked {
                ContentUnavailableView {
                    Label("无法打开分享", systemImage: "link.badge.plus")
                } description: {
                    Text(ErrorText.message(error))
                }
            } else if state.needsPassword && !state.unlocked {
                ShareVisitPasswordForm(
                    share: state.share,
                    password: $password,
                    incorrect: state.passwordIncorrect,
                    submitting: state.submittingPassword
                ) {
                    host.send(ShareVisitIntentSubmitPassword(password: password))
                }
            } else if state.unlocked {
                unlockedList(state)
            } else {
                ProgressView()
                    .controlSize(.large)
            }
        }
        .navigationTitle(title(state))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if state.unlocked {
                if state.canGoUp {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("上一级", systemImage: "chevron.up") {
                            host.send(ShareVisitIntentUp.shared)
                        }
                    }
                }
                if state.readme != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("说明", systemImage: "doc.text") { showingReadme = true }
                    }
                }
                ToolbarItem(placement: .bottomBar) {
                    Button("保存到我的文件", systemImage: "square.and.arrow.down") {
                        pickingDestination = true
                    }
                    .buttonStyle(.glassProminent)
                    .disabled(!state.canSave)
                    .accessibilityIdentifier("shareVisit.save")
                }
            }
        }
        .sheet(isPresented: $pickingDestination) {
            FolderPickerSheet(
                accountId: accountId,
                initialUri: CrUri.companion.my(uid: nil).description(),
                title: "保存到"
            ) { destination in
                host.send(ShareVisitIntentSaveToMyFiles(dstUri: destination))
            }
        }
        .sheet(isPresented: $showingReadme) {
            ShareReadmeSheet(
                title: state.readme?.name ?? "README",
                content: state.readmeContent,
                loading: state.readmeLoading
            )
        }
        .sheet(item: $infoFile) { target in
            FileInfoSheet(accountId: accountId, file: target.file)
        }
        .alert(message?.title ?? "", isPresented: $showingMessage, presenting: message) { _ in
            Button("好", role: .cancel) {}
        } message: { message in
            Text(message.body)
        }
        .onChange(of: state.readmeContent) { _, content in
            // README 自动下载完成后弹出一次（网页端行为）
            if content != nil { showingReadme = true }
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case let .saved(saved):
                    message = ShareVisitMessage(
                        title: String(localized: "已保存"),
                        body: String(localized: "已在“\(FileFormat.folderTitle(saved.dstUri))”中创建快捷方式“\(saved.shortcut.name)”。", comment: "参数：目录名、快捷方式名")
                    )
                    showingMessage = true
                case let .failed(failure):
                    message = ShareVisitMessage(title: String(localized: "操作失败"), body: ErrorText.message(failure.error))
                    showingMessage = true
                }
            }
        }
    }

    @ViewBuilder
    private func unlockedList(_ state: ShareVisitState) -> some View {
        let thumbnails = FileThumbnailSource(accountId: accountId, contextHint: state.contextHint, enabled: true)
        List {
            if let share = state.share {
                Section {
                    ShareVisitHeader(share: share, siteMismatch: state.siteMismatch)
                }
            }
            Section {
                if let single = state.singleFile {
                    Button {
                        infoFile = FileInfoTarget(file: single)
                    } label: {
                        FileRowLabel(file: single, thumbnails: thumbnails, isTrash: false)
                    }
                    .buttonStyle(.plain)
                } else {
                    if state.loadingList && state.files.isEmpty {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    }
                    ForEach(state.files, id: \.path) { file in
                        Button {
                            if file.isFolder {
                                host.send(ShareVisitIntentOpen(uri: file.path))
                            } else {
                                infoFile = FileInfoTarget(file: file)
                            }
                        } label: {
                            FileRowLabel(file: file, thumbnails: thumbnails, isTrash: false)
                        }
                        .buttonStyle(.plain)
                    }
                    if state.hasMore {
                        LoadMoreRow(loading: state.loadingMore) {
                            host.send(ShareVisitIntentLoadMore.shared)
                        }
                        .id(state.files.count)
                    }
                }
            } header: {
                if state.crumbs.count > 1 {
                    Text(verbatim: state.crumbs.dropFirst().map(\.name).joined(separator: " / "))
                }
            }
        }
        .refreshable { host.send(ShareVisitIntentRefresh.shared) }
    }

    private func title(_ state: ShareVisitState) -> String {
        if let name = state.share?.name, !name.isEmpty { return name }
        return String(localized: "分享")
    }
}
