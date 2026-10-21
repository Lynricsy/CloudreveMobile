/// 有效期选项：保持原到期时间（编辑且未修改时）、永久、预设时长、自定义
enum ShareExpireChoice: Hashable {
    case keep
    case never
    case preset(Int64)
    case custom
}
