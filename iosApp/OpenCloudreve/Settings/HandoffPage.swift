import Foundation

/// 待打开的网页（`sheet(item:)` 需要 `Identifiable`；每次交接地址都不同）
struct HandoffPage: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}
