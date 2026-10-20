import SwiftUI
import OpenCloudreveCore

struct ArchiveViewerContent: View {
    let host: ViewerArchiveHost
    let chrome: ViewerChrome
    let accountId: String

    @State private var extract: ArchiveExtractRequest?

    var body: some View {
        let state = host.state
        let children = ArchiveEntryItem.children(of: state)
        Group {
            if let error = state.error {
                ViewerStatusView(status: .failed(ErrorText.message(error))) {
                    host.send(ViewerArchiveIntentRefresh.shared)
                }
            } else if state.loading {
                ViewerStatusView(status: .loading)
            } else {
                List {
                    if state.canGoUp {
                        Section {
                            Button("返回上一级", systemImage: "arrow.turn.left.up") {
                                host.send(ViewerArchiveIntentUp.shared)
                            }
                            .accessibilityIdentifier("archive.up")
                        }
                    }
                    Section {
                        ForEach(children) { entry in
                            ArchiveEntryRow(
                                entry: entry,
                                canSelect: state.canExtract,
                                onToggle: { host.send(ViewerArchiveIntentToggleSelection(path: entry.path)) },
                                onOpen: { host.send(ViewerArchiveIntentOpen(path: entry.path)) }
                            )
                        }
                    } header: {
                        Text(verbatim: "/" + state.breadcrumbs.joined(separator: "/"))
                    }
                }
                .overlay {
                    if children.isEmpty {
                        ContentUnavailableView("压缩包是空的", systemImage: "archivebox")
                    }
                }
            }
        }
        .viewerToolbar(chrome, title: state.file.name)
        .toolbar {
            ToolbarItem(placement: .bottomBar) {
                ArchiveEncodingMenu(current: state.encoding, options: state.encodingOptions) { encoding in
                    host.send(ViewerArchiveIntentSetEncoding(encoding: encoding))
                }
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            if state.canExtract {
                ToolbarItem(placement: .bottomBar) {
                    Menu {
                        Button("解压全部", systemImage: "archivebox") {
                            host.send(ViewerArchiveIntentExtractAll.shared)
                        }
                        Button("解压所选（\(state.selection.count) 项）", systemImage: "checklist") {
                            host.send(ViewerArchiveIntentExtractSelected.shared)
                        }
                        .disabled(state.selection.isEmpty)
                    } label: {
                        Label("解压到…", systemImage: "arrow.up.bin")
                    }
                    .accessibilityIdentifier("archive.extract")
                }
            }
        }
        .sheet(item: $extract) { request in
            ArchiveExtractSheet(accountId: accountId, request: request)
        }
        .task {
            await host.effects { effect in
                switch onEnum(of: effect) {
                case let .extractRequested(requested):
                    extract = ArchiveExtractRequest(
                        fileUri: requested.file.path,
                        encoding: requested.encoding,
                        mask: requested.mask,
                        parentUri: ViewerRequest.parentUri(of: requested.file.path)
                    )
                }
            }
        }
    }
}
