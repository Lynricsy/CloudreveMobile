import Foundation

/// 管理端枚举值的显示文案
enum AdminLabels {
    static func userStatus(_ status: String) -> String {
        switch status {
        case "active": String(localized: "正常")
        case "inactive": String(localized: "未激活")
        case "manual_banned": String(localized: "已封禁")
        case "sys_banned": String(localized: "系统封禁")
        default: status
        }
    }

    static func taskStatus(_ status: String) -> String {
        switch status {
        case "queued": String(localized: "排队中")
        case "processing": String(localized: "处理中")
        case "suspending": String(localized: "已挂起")
        case "error": String(localized: "出错")
        case "canceled": String(localized: "已取消")
        case "completed": String(localized: "已完成")
        default: status
        }
    }

    static func nodeStatus(_ status: String) -> String {
        switch status {
        case "active": String(localized: "启用")
        case "suspended": String(localized: "暂停")
        default: status
        }
    }

    static func nodeType(_ type: String) -> String {
        switch type {
        case "master": String(localized: "主机")
        case "slave": String(localized: "从机")
        default: type
        }
    }

    static func taskType(_ type: String) -> String {
        switch type {
        case "create_archive": String(localized: "创建压缩包")
        case "extract_archive": String(localized: "解压缩")
        case "relocate": String(localized: "转移存储策略")
        case "remote_download": String(localized: "离线下载")
        case "import": String(localized: "导入文件")
        case "media_meta": String(localized: "提取媒体信息")
        case "entity_recycle_routine", "explicit_entity_recycle": String(localized: "回收存储")
        case "upload_sentinel_check": String(localized: "上传会话检查")
        case "full_text_index": String(localized: "全文索引")
        default: type
        }
    }

    static func queueName(_ name: String) -> String {
        switch name {
        case "media_meta": String(localized: "媒体信息")
        case "recycle": String(localized: "回收")
        case "io_intense": String(localized: "IO 密集")
        case "remote_download": String(localized: "离线下载")
        case "thumb": String(localized: "缩略图")
        default: name
        }
    }

    static func orderField(_ field: String) -> String {
        switch field {
        case "id": "ID"
        case "nick": String(localized: "昵称")
        case "email": String(localized: "邮箱")
        case "storage": String(localized: "已用空间")
        case "updated_at": String(localized: "更新时间")
        case "created_at": String(localized: "创建时间")
        case "views": String(localized: "浏览次数")
        case "downloads": String(localized: "下载次数")
        case "remain_downloads": String(localized: "剩余下载次数")
        case "name": String(localized: "名称")
        case "size": String(localized: "大小")
        case "weight": String(localized: "权重")
        default: field
        }
    }
}
