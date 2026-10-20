import Foundation
import OpenCloudreveCore

/// 搜索条件表单的编辑草稿（Swift 值类型），确认时转换为共享层的 `SearchFilters`
struct SearchFilterDraft {
    var fullText = false
    var type: SearchFileType?
    var category: String?
    var caseFolding = true
    var minMegabytes = ""
    var maxMegabytes = ""
    var filterUpdated = false
    var updatedFrom = Calendar.current.date(byAdding: .month, value: -1, to: .now) ?? .now
    var updatedTo = Date.now
    var tagsText = ""

    init() {}

    /// 从当前状态回填
    @MainActor
    init(_ state: SearchState) {
        let filters = state.filters
        fullText = state.fullText
        type = filters.type
        category = filters.category
        caseFolding = filters.caseFolding
        minMegabytes = Self.megabytes(filters.sizeGte)
        maxMegabytes = Self.megabytes(filters.sizeLte)
        if let from = filters.updatedGte?.int64Value {
            filterUpdated = true
            updatedFrom = Date(timeIntervalSince1970: TimeInterval(from))
        }
        if let to = filters.updatedLte?.int64Value {
            filterUpdated = true
            updatedTo = Date(timeIntervalSince1970: TimeInterval(to))
        }
        tagsText = filters.tags.joined(separator: ", ")
    }

    /// 转为共享层过滤条件（时间为 Unix 秒、大小为字节）
    var filters: SearchFilters {
        let tags = tagsText
            .split(whereSeparator: { $0 == "," || $0 == "，" })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        let calendar = Calendar.current
        let from = filterUpdated ? calendar.startOfDay(for: updatedFrom) : nil
        let to = filterUpdated ? calendar.date(byAdding: DateComponents(day: 1, second: -1), to: calendar.startOfDay(for: updatedTo)) : nil
        return SearchFilters(
            type: type,
            category: category,
            sizeGte: Self.bytes(minMegabytes),
            sizeLte: Self.bytes(maxMegabytes),
            createdGte: nil,
            createdLte: nil,
            updatedGte: from.map { KotlinLong(longLong: Int64($0.timeIntervalSince1970)) },
            updatedLte: to.map { KotlinLong(longLong: Int64($0.timeIntervalSince1970)) },
            tags: tags,
            caseFolding: caseFolding
        )
    }

    private static func bytes(_ megabytes: String) -> KotlinLong? {
        let text = megabytes.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let value = Double(text), value >= 0 else { return nil }
        return KotlinLong(longLong: Int64(value * 1_048_576))
    }

    private static func megabytes(_ bytes: KotlinLong?) -> String {
        guard let bytes else { return "" }
        let value = Double(bytes.int64Value) / 1_048_576
        return value.formatted(.number.precision(.fractionLength(0...2)))
    }
}
