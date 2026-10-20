import Testing
import OpenCloudreveCore
@testable import OpenCloudreve

/// `ViewModelHost` 桥接：初始状态、意图转发后的同步状态、流式状态投递、一次性事件、销毁后停止更新。
/// 宿主 App 启动时已调用 `CoreBootstrap.start()`，这里再调一次保证独立运行时也已启动。
@MainActor
@Suite(.serialized)
struct ViewModelHostTests {
    /// 本机不会监听的端口：请求立即被拒绝，用于触发异步错误而不依赖外部网络
    private let unreachableSite = "http://127.0.0.1:9"

    init() {
        CoreBootstrap.start()
    }

    @Test func initialStateMirrorsViewModel() {
        let host = ServerSetupHost(CoreGraph.shared.serverSetupViewModel())
        #expect(host.state.url == "")
        #expect(!host.state.canSubmit)
        #expect(!host.state.checking)
    }

    @Test func sendUpdatesStateSynchronously() {
        let host = ServerSetupHost(CoreGraph.shared.serverSetupViewModel())
        host.url = "cloud.example.com"
        #expect(host.state.url == "cloud.example.com")
        #expect(host.state.canSubmit)

        host.url = "not a url"
        host.send(ServerSetupIntentSubmit.shared)
        #expect(host.state.error?.kind == .validation)
    }

    @Test func asynchronousStateChangesReachHost() async throws {
        let host = LoginHost(CoreGraph.shared.loginViewModel(siteUrl: unreachableSite, email: "a@example.com", language: nil))
        #expect(host.state.loadingConfig)
        #expect(host.state.email == "a@example.com")
        try await waitUntil { !host.state.loadingConfig }
        #expect(host.state.configError?.kind == .network)
    }

    @Test func effectsAreDelivered() async throws {
        let host = LoginHost(CoreGraph.shared.loginViewModel(siteUrl: unreachableSite, email: nil, language: nil))
        let box = EffectBox()
        let collector = Task {
            await host.effects { effect in box.effects.append(effect) }
        }
        defer { collector.cancel() }

        host.send(LoginIntentStartWebLogin.shared)
        try await waitUntil { !box.effects.isEmpty }
        guard case let .launchWebLogin(launch) = onEnum(of: box.effects[0]) else {
            Issue.record("期望 LaunchWebLogin，实际 \(box.effects[0])")
            return
        }
        #expect(launch.callbackScheme == "opencloudreve")
        #expect(launch.authorizeUrl.hasPrefix(unreachableSite + "/session/authorize?"))
        #expect(host.state.submitting)
    }

    @Test func disposeStopsUpdates() async throws {
        let viewModel = CoreGraph.shared.serverSetupViewModel()
        let host = ServerSetupHost(viewModel)
        host.dispose()
        #expect(host.isDisposed)

        host.url = "ignored.example.com"
        #expect(host.state.url == "")

        viewModel.on(intent: ServerSetupIntentUrlChanged(value: "direct.example.com"))
        try await Task.sleep(for: .milliseconds(200))
        #expect(host.state.url == "")
    }

    /// 轮询直到条件成立（最多 10 秒）
    private func waitUntil(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(10)
        while !condition() {
            guard ContinuousClock.now < deadline else {
                Issue.record("等待超时")
                return
            }
            try await Task.sleep(for: .milliseconds(20))
        }
    }
}

@MainActor
private final class EffectBox {
    var effects: [any LoginEffect] = []
}
