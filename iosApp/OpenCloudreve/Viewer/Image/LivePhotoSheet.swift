import SwiftUI
import OpenCloudreveCore

/// 实况照片播放页（`customize:live_photo`；配对视频实体地址由 `LoadLivePhoto` 取得）
struct LivePhotoSheet: View {
    let host: ViewerImageHost

    @State private var loader = LivePhotoLoader()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let current = host.state.current
        NavigationStack {
            Group {
                if let photo = loader.livePhoto {
                    LivePhotoPlayerView(livePhoto: photo)
                } else if loader.failed || current?.error != nil {
                    ViewerStatusView(status: .failed(String(localized: "无法播放实况照片")))
                } else {
                    ViewerStatusView(status: .loading)
                }
            }
            .navigationTitle("实况照片")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .task(id: current?.livePhotoUrl?.url) {
            await loader.load(
                imageURL: current?.url?.url,
                videoURL: current?.livePhotoUrl?.url,
                fileName: current?.file.name ?? "live.heic"
            )
        }
    }
}
