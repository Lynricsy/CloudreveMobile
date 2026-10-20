import SwiftUI
import OpenCloudreveCore

struct FilesHomeContent: View {
    let accountId: String
    let host: FileListHost
    @Binding var selection: String?

    @State private var openingShareLink = false

    var body: some View {
        let state = host.state
        List(selection: $selection) {
            if let capacity = state.capacity {
                Section {
                    QuotaView(used: capacity.used, total: capacity.total)
                }
            }
            Section {
                locationRow("我的文件", systemImage: "folder", uri: CrUri.companion.my(uid: nil).description(), identifier: "files.location.my")
                locationRow("与我共享", systemImage: "person.2", uri: CrUri.companion.sharedWithMe().description(), identifier: "files.location.shared")
                locationRow("回收站", systemImage: "trash", uri: CrUri.companion.trash().description(), identifier: "files.location.trash")
            } header: {
                // UI 测试以此判断已进入主界面（文件 Tab 根页）
                Text("位置")
                    .accessibilityIdentifier("tab.files")
            }
            let pins = state.user?.pined ?? []
            if !pins.isEmpty {
                Section("固定项") {
                    ForEach(pins, id: \.uri) { pin in
                        NavigationLink(value: pin.uri) {
                            Label {
                                Text(verbatim: pinTitle(pin))
                            } icon: {
                                Image(systemName: pin.locked ? "pin.circle.fill" : "pin.fill")
                            }
                        }
                    }
                }
            }
            Section {
                Button("打开分享链接", systemImage: "link") { openingShareLink = true }
                    .accessibilityIdentifier("files.openShareLink")
            }
        }
        .refreshable { host.send(FileListIntentRefresh.shared) }
        .sheet(isPresented: $openingShareLink) {
            OpenShareLinkSheet(accountId: accountId)
        }
    }

    private func locationRow(_ title: LocalizedStringKey, systemImage: String, uri: String, identifier: String) -> some View {
        NavigationLink(value: uri) {
            Label(title, systemImage: systemImage)
        }
        .accessibilityIdentifier(identifier)
    }

    private func pinTitle(_ pin: PinedFile) -> String {
        if let name = pin.name, !name.isEmpty { return name }
        return FileFormat.folderTitle(pin.uri)
    }
}
