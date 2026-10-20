import SwiftUI

/// 站点总数卡片：文件、用户、分享、存储实体
struct AdminTotalsGrid: View {
    let files: Int
    let users: Int
    let shares: Int
    let entities: Int

    var body: some View {
        Grid(horizontalSpacing: 12, verticalSpacing: 12) {
            GridRow {
                AdminTotalCard(title: "文件", value: files, systemImage: "doc", color: .blue)
                AdminTotalCard(title: "用户", value: users, systemImage: "person.2", color: .green)
            }
            GridRow {
                AdminTotalCard(title: "分享", value: shares, systemImage: "square.and.arrow.up", color: .orange)
                AdminTotalCard(title: "存储实体", value: entities, systemImage: "externaldrive", color: .purple)
            }
        }
        .accessibilityIdentifier("admin.totals")
    }
}
