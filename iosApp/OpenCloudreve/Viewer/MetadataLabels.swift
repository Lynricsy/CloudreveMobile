import Foundation

/// 元数据键的显示名（服务端 `pkg/mediameta/exif.go` 的 EXIF 键）；未知键原样显示
enum MetadataLabels {
    static func label(for key: String) -> String {
        switch key {
        case "camera_make": String(localized: "相机制造商")
        case "camera_model": String(localized: "相机型号")
        case "camera_owner": String(localized: "相机所有者")
        case "body_serial": String(localized: "机身序列号")
        case "lens_make": String(localized: "镜头制造商")
        case "lens_model": String(localized: "镜头型号")
        case "software": String(localized: "软件")
        case "exposure_time": String(localized: "曝光时间")
        case "f": String(localized: "光圈值")
        case "aperture": String(localized: "光圈")
        case "focal_length": String(localized: "焦距")
        case "iso": String(localized: "ISO 感光度")
        case "x": String(localized: "宽度")
        case "y": String(localized: "高度")
        case "orientation": String(localized: "方向")
        case "taken_at": String(localized: "拍摄时间")
        case "flash": String(localized: "闪光灯")
        case "des": String(localized: "描述")
        case "projection_type": String(localized: "投影类型")
        case "exposure_bias": String(localized: "曝光补偿")
        case "latitude": String(localized: "纬度")
        case "longitude": String(localized: "经度")
        case "altitude": String(localized: "海拔")
        case "artist": String(localized: "作者")
        case "copyright": String(localized: "版权")
        default: key
        }
    }
}
