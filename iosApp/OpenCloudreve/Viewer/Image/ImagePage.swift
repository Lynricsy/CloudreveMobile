import SwiftUI

/// 单张图片：签名地址到手后下载解码，可缩放平移
struct ImagePage: View {
    let url: String?
    let errorMessage: String?

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            if let image {
                ZoomableImageView(image: image)
                    .accessibilityLabel(Text("图片"))
                    .accessibilityIdentifier("image.view")
            } else if let errorMessage {
                ViewerStatusView(status: .failed(errorMessage))
            } else if failed {
                ViewerStatusView(status: .failed(String(localized: "无法显示此图片")))
            } else {
                ProgressView()
                    .tint(.white)
                    .controlSize(.large)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: url) {
            guard let url, let address = URL(string: url) else { return }
            failed = false
            if let loaded = await RemoteFile.image(from: address) {
                image = loaded
            } else if !Task.isCancelled {
                failed = true
            }
        }
    }
}
