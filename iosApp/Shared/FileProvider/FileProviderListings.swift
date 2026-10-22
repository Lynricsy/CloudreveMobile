/// File Provider 工作集的目录缓存选择
enum FileProviderListings {
    /// [listings] 为新到旧的缓存目录列表；同一目录（按规范化 URI 比较）可能因写入时的 URI 写法不同存了多份，
    /// 只保留最新的一份——旧快照里已删除/改名的条目不能再出现在工作集中。取不到目录的列表原样保留。
    static func newestPerDirectory<Listing>(
        _ listings: [Listing],
        normalize: (String) -> String = FileProviderIdentity.normalized,
        directory: (Listing) -> String?
    ) -> [Listing] {
        var seen = Set<String>()
        return listings.filter { listing in
            guard let uri = directory(listing) else { return true }
            return seen.insert(normalize(uri)).inserted
        }
    }
}
