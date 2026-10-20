import Foundation
import OpenCloudreveCore

/// 图片预览中的一页（从 `ViewerImage` 摘出渲染所需字段）
struct ImagePageModel {
    let index: Int
    let path: String
    let url: String?
    let errorMessage: String?

    static func pages(of state: ViewerImageState) -> [ImagePageModel] {
        state.images.enumerated().map { offset, image in
            ImagePageModel(
                index: offset,
                path: image.file.path,
                url: image.url?.url,
                errorMessage: image.error.map { ErrorText.message($0) }
            )
        }
    }
}
