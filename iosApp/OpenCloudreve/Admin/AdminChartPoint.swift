import Foundation

/// 图表的一个点：某天某类新增数
struct AdminChartPoint: Identifiable {
    let date: Date
    let series: String
    let count: Int
    var id: String { "\(series)-\(date.timeIntervalSince1970)" }
}
