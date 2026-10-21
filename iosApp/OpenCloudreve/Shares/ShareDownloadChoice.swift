/// 下载次数选项：不限、预设次数、自定义
enum ShareDownloadChoice: Hashable {
    case unlimited
    case preset(Int32)
    case custom

    /// 由当前上限推出选项：不在预设中的次数（含无效的 0）视为自定义
    init(limit: Int32?, presets: [Int32]) {
        guard let limit else {
            self = .unlimited
            return
        }
        self = presets.contains(limit) ? .preset(limit) : .custom
    }
}
