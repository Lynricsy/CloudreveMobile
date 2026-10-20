import FileProvider

/// 主 App 修改了远端文件后通知系统“文件”刷新（扩展随即重新枚举工作集）
enum FileProviderSignal {
    static func workingSetChanged(accountId: String) {
        // 取 manager 只用到域标识，显示名不参与匹配
        let domain = NSFileProviderDomain(identifier: NSFileProviderDomainIdentifier(rawValue: accountId), displayName: accountId)
        NSFileProviderManager(for: domain)?.signalEnumerator(for: .workingSet) { _ in }
    }
}
