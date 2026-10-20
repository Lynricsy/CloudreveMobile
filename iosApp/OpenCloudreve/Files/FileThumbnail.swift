import SwiftUI
import NukeUI
import OpenCloudreveCore

/// 文件缩略图（签名 URL 由 Nuke 加载并缓存）；没有缩略图或加载失败时显示类型图标
struct FileThumbnail: View {
    let file: FileItem
    let source: FileThumbnailSource
    let size: CGFloat
    /// 网格单元格用方形裁切填充；列表行用小圆角
    var cornerRadius: CGFloat = 6

    @State private var url: URL?

    var body: some View {
        ZStack {
            if let url {
                LazyImage(url: url) { phase in
                    if let image = phase.image {
                        image
                            .resizable()
                            .scaledToFill()
                    } else {
                        FileTypeIconView(file: file, size: size * 0.8)
                    }
                }
                .frame(width: size, height: size)
                .clipShape(.rect(cornerRadius: cornerRadius))
            } else {
                FileTypeIconView(file: file, size: size * 0.8)
            }
        }
        .frame(width: size, height: size)
        .task(id: TaskKey(path: file.path, source: source)) {
            url = await source.url(for: file)
        }
        .accessibilityHidden(true)
    }

    /// 文件或缩略图设置变化时重新获取地址
    private struct TaskKey: Equatable {
        let path: String
        let source: FileThumbnailSource
    }
}
