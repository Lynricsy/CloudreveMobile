import Foundation

/// 存储策略类型的显示名（英文界面用服务商英文名）
enum PolicyTypeName {
    static func name(_ type: String) -> String {
        switch type {
        case "local": String(localized: "本机存储")
        case "remote": String(localized: "从机存储")
        case "oss": String(localized: "阿里云 OSS")
        case "cos": String(localized: "腾讯云 COS")
        case "qiniu": String(localized: "七牛云")
        case "upyun": String(localized: "又拍云")
        case "onedrive": "OneDrive"
        case "s3": "S3"
        case "ks3": String(localized: "金山云 KS3")
        case "obs": String(localized: "华为云 OBS")
        case "load_balance": String(localized: "负载均衡")
        default: type
        }
    }
}
