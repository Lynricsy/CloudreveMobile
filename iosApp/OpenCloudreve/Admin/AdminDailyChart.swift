import Charts
import SwiftUI
import OpenCloudreveCore

/// 近 12 天新增文件 / 用户 / 分享：折线或柱状
struct AdminDailyChart: View {
    let daily: [AdminDailyMetric]
    @State private var showsBars = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("图表样式", selection: $showsBars) {
                Text("折线").tag(false)
                Text("柱状").tag(true)
            }
            .pickerStyle(.segmented)

            Chart(points) { point in
                if showsBars {
                    BarMark(
                        x: .value("日期", point.date, unit: .day),
                        y: .value("数量", point.count)
                    )
                    .foregroundStyle(by: .value("类别", point.series))
                    .position(by: .value("类别", point.series))
                } else {
                    LineMark(
                        x: .value("日期", point.date, unit: .day),
                        y: .value("数量", point.count)
                    )
                    .foregroundStyle(by: .value("类别", point.series))
                    .symbol(by: .value("类别", point.series))
                    .interpolationMethod(.monotone)
                }
            }
            .chartXAxis {
                AxisMarks(values: .stride(by: .day, count: 2)) { _ in
                    AxisGridLine()
                    AxisValueLabel(format: .dateTime.month(.defaultDigits).day())
                }
            }
            .frame(height: 220)
            .accessibilityIdentifier("admin.chart")
        }
        .padding(.vertical, 4)
    }

    private var points: [AdminChartPoint] {
        let files = String(localized: "新增文件")
        let users = String(localized: "新增用户")
        let shares = String(localized: "新增分享")
        return daily.flatMap { metric -> [AdminChartPoint] in
            let date = SettingsFormat.date(metric.date) ?? .distantPast
            return [
                AdminChartPoint(date: date, series: files, count: Int(metric.files)),
                AdminChartPoint(date: date, series: users, count: Int(metric.users)),
                AdminChartPoint(date: date, series: shares, count: Int(metric.shares)),
            ]
        }
    }
}
