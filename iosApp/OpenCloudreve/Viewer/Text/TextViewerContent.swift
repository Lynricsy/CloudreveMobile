import SwiftUI
import OpenCloudreveCore

struct TextViewerContent: View {
    @Bindable var host: ViewerTextHost
    let chrome: ViewerChrome

    @AppStorage("viewer.text.monospaced") private var monospaced = true
    @AppStorage("viewer.text.wraps") private var wraps = true
    /// Markdown 的编辑/预览切换（nil = 按文件类型默认：Markdown 先预览）
    @State private var mode: TextViewerMode?
    /// 等待确认放弃未保存更改的离开动作（关闭 / 切换打开方式 / 用其他应用打开）
    @State private var pendingLeave: ViewerLeaveAction?
    @State private var isSaveAsPresented = false
    @State private var saveAsName = ""
    @State private var failureMessage: String?
    @State private var savedNoticeID: UUID?

    var body: some View {
        let state = host.state
        let currentMode = mode ?? (state.isMarkdown ? .preview : .edit)
        Group {
            if let error = state.error {
                ViewerStatusView(status: .failed(ErrorText.message(error))) {
                    host.send(ViewerTextIntentReload.shared)
                }
            } else if state.original == nil {
                ViewerStatusView(status: .loading)
            } else if state.isMarkdown && currentMode == .preview {
                MarkdownPreviewPane(text: state.draft)
            } else {
                CodeTextView(text: $host.draft, editable: !state.readOnly, monospaced: monospaced, wraps: wraps)
                    .accessibilityIdentifier("text.editor")
            }
        }
        .overlay(alignment: .top) {
            if savedNoticeID != nil {
                Label("已保存", systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(.regularMaterial, in: .capsule)
                    .padding(.top, 8)
                    .accessibilityIdentifier("text.saved")
            }
        }
        .viewerToolbar(chrome, title: state.file.name) { action in
            guard host.state.dirty else { return false }
            pendingLeave = action
            return true
        }
        .toolbar {
            if !state.readOnly {
                ToolbarItem(placement: .topBarTrailing) {
                    if state.saving {
                        ProgressView()
                    } else {
                        Button("保存", systemImage: "square.and.arrow.down") {
                            host.send(ViewerTextIntentSave.shared)
                        }
                        .disabled(!state.canSave)
                        .accessibilityIdentifier("text.save")
                    }
                }
            }
            if state.isMarkdown {
                ToolbarItem(placement: .bottomBar) {
                    Picker("模式", selection: Binding(get: { currentMode }, set: { mode = $0 })) {
                        Text("编辑").tag(TextViewerMode.edit)
                        Text("预览").tag(TextViewerMode.preview)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("text.mode")
                }
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                TextFormatMenu(monospaced: $monospaced, wraps: $wraps, canDiscard: state.dirty) {
                    host.send(ViewerTextIntentDiscard.shared)
                }
            }
        }
        .confirmationDialog(
            "有未保存的更改",
            isPresented: Binding(get: { pendingLeave != nil }, set: { if !$0 { pendingLeave = nil } }),
            titleVisibility: .visible,
            presenting: pendingLeave
        ) { action in
            Button(Self.discardTitle(action), role: .destructive) { chrome.perform(action) }
                .accessibilityIdentifier("text.discard.confirm")
            Button("取消", role: .cancel) {}
                .accessibilityIdentifier("text.discard.cancel")
        }
        .confirmationDialog("文件已在其他地方被修改", isPresented: $host.isStaleVersionPresented, titleVisibility: .visible) {
            Button("覆盖", role: .destructive) { host.send(ViewerTextIntentOverwrite.shared) }
            Button("另存为…") { prepareSaveAs() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("覆盖会丢弃其他地方的修改；也可以另存为新文件。")
        }
        .alert("文件已被锁定", isPresented: $host.isLockConflictPresented) {
            Button("强制解锁并保存", role: .destructive) { host.send(ViewerTextIntentForceUnlockAndSave.shared) }
            Button("取消", role: .cancel) {}
        } message: {
            Text(verbatim: TextViewerContent.lockMessage(state.lockConflicts))
        }
        .alert("另存为", isPresented: $isSaveAsPresented) {
            TextField("文件名", text: $saveAsName)
                .accessibilityIdentifier("text.saveAsName")
            Button("保存") {
                host.send(ViewerTextIntentSaveAs(parentUri: ViewerRequest.parentUri(of: host.state.file.path), name: saveAsName))
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("保存到同一文件夹")
        }
        .alert("保存失败", isPresented: Binding(get: { failureMessage != nil }, set: { if !$0 { failureMessage = nil } })) {
            Button("好") {}
        } message: {
            Text(verbatim: failureMessage ?? "")
        }
        .task(id: savedNoticeID) {
            guard savedNoticeID != nil else { return }
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled { savedNoticeID = nil }
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case .saved, .savedAs:
                    savedNoticeID = UUID()
                case let .failed(failed):
                    failureMessage = TextViewerContent.saveErrorMessage(failed.error)
                }
            }
        }
    }

    private func prepareSaveAs() {
        let name = host.state.file.name as NSString
        let ext = name.pathExtension
        let base = name.deletingPathExtension
        let copy = String(localized: "\(base) 副本", comment: "另存为的默认文件名，参数为原文件名（不含扩展名）")
        saveAsName = ext.isEmpty ? copy : copy + "." + ext
        isSaveAsPresented = true
    }

    /// 放弃未保存更改后要执行的离开动作
    static func discardTitle(_ action: ViewerLeaveAction) -> LocalizedStringKey {
        switch action {
        case .close: "放弃更改并关闭"
        case .switchViewer: "放弃更改并切换打开方式"
        case .openExternally: "放弃更改并用其他应用打开"
        }
    }

    /// 40049 单独说明（在线编辑的大小上限），其余按通用规则
    static func saveErrorMessage(_ error: UiError) -> String {
        if error.kind == .fileTooLarge || error.code?.int32Value == 40049 {
            return String(localized: "文件超过在线编辑的大小上限，无法保存")
        }
        return ErrorText.message(error)
    }

    static func lockMessage(_ conflicts: [LockConflict]) -> String {
        let header = String(localized: "以下项目正被其他操作占用。强制解锁可能导致对方的修改丢失。")
        let paths = conflicts.compactMap(\.path)
        return paths.isEmpty ? header : header + "\n" + paths.joined(separator: "\n")
    }
}
