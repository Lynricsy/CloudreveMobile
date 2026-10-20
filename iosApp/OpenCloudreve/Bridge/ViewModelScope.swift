import SwiftUI
import OpenCloudreveCore

/// 在视图生命周期内持有一个 `ViewModelHost`。
///
/// `@State` 的初始值表达式在每次父视图重建时都会被求值（结果随即丢弃），直接写
/// `@State var host = ViewModelHost(CoreGraph.shared.xxxViewModel())` 会反复创建 Kotlin VM（还可能发起网络请求）。
/// 这里改为首次出现时才调用 `make` 创建一次；视图离开层级时 `@State` 释放宿主，`deinit` 中 `dispose()` VM。
struct ViewModelScope<S: AnyObject, I: AnyObject, E: AnyObject, Content: View>: View {
    private let make: () -> CoreViewModel<S, I, E>
    private let content: (ViewModelHost<S, I, E>) -> Content
    @State private var host: ViewModelHost<S, I, E>?

    init(
        _ make: @escaping () -> CoreViewModel<S, I, E>,
        @ViewBuilder content: @escaping (ViewModelHost<S, I, E>) -> Content
    ) {
        self.make = make
        self.content = content
    }

    var body: some View {
        if let host {
            content(host)
        } else {
            Color.clear.onAppear {
                if host == nil { host = ViewModelHost(make()) }
            }
        }
    }
}
