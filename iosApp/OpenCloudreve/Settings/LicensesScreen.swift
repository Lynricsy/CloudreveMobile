import SwiftUI

/// 使用的开源组件及其许可证
struct LicensesScreen: View {
    private static let components: [(name: String, license: String)] = [
        ("Kotlin Multiplatform", "Apache-2.0"),
        ("kotlinx.coroutines", "Apache-2.0"),
        ("kotlinx.serialization", "Apache-2.0"),
        ("Ktor", "Apache-2.0"),
        ("SQLDelight", "Apache-2.0"),
        ("Koin", "Apache-2.0"),
        ("SKIE", "Apache-2.0"),
        ("Okio", "Apache-2.0"),
        ("Nuke", "MIT"),
        ("MarkdownUI", "MIT"),
        ("Cloudreve", "GPL-3.0"),
    ]

    var body: some View {
        List(Self.components, id: \.name) { component in
            LabeledContent {
                Text(verbatim: component.license)
            } label: {
                Text(verbatim: component.name)
            }
        }
        .navigationTitle("开源许可")
    }
}
