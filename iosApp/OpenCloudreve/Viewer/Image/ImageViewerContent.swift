import SwiftUI
import OpenCloudreveCore

struct ImageViewerContent: View {
    @Bindable var host: ViewerImageHost
    let chrome: ViewerChrome
    let fallbackTitle: String

    @State private var isInfoPresented = false
    @State private var isLivePhotoPresented = false

    var body: some View {
        let state = host.state
        let current = state.current
        Group {
            if state.images.isEmpty {
                ViewerStatusView(status: state.loading ? .loading : .failed(String(localized: "没有可显示的图片")))
            } else {
                TabView(selection: $host.selectedIndex) {
                    ForEach(ImagePageModel.pages(of: state), id: \.path) { page in
                        ImagePage(url: page.url, errorMessage: page.errorMessage)
                            .tag(page.index)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
            }
        }
        .background(Color.black, ignoresSafeAreaEdges: .all)
        .viewerToolbar(chrome, title: current?.file.name ?? fallbackTitle)
        .navigationSubtitle(state.images.count > 1 ? Text(verbatim: "\(Int(state.index) + 1) / \(state.images.count)") : Text(verbatim: ""))
        .toolbar {
            if current?.isLivePhoto == true {
                ToolbarItem(placement: .bottomBar) {
                    Button("实况", systemImage: "livephoto") {
                        host.send(ViewerImageIntentLoadLivePhoto.shared)
                        isLivePhotoPresented = true
                    }
                    .accessibilityIdentifier("image.livePhoto")
                }
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button("信息", systemImage: "info.circle") { isInfoPresented = true }
                    .disabled(current == nil)
                    .accessibilityIdentifier("image.info")
            }
        }
        .sheet(isPresented: $isInfoPresented) {
            if let current {
                ImageInfoSheet(info: ImageInfo(image: current))
            }
        }
        .sheet(isPresented: $isLivePhotoPresented) {
            LivePhotoSheet(host: host)
        }
    }
}
