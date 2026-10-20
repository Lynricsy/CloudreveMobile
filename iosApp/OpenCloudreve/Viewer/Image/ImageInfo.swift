import Foundation
import OpenCloudreveCore

/// 图片信息面板的数据：基本信息 + `exif:*` + `geocoding:*` 元数据
struct ImageInfo {
    let name: String
    let size: Int64
    let exif: [MetadataRow]
    let geocoding: [MetadataRow]

    init(image: ViewerImage) {
        name = image.file.name
        size = image.file.size
        exif = image.exif.map(MetadataRow.init(entry:))
        geocoding = image.geocoding.map(MetadataRow.init(entry:))
    }
}
