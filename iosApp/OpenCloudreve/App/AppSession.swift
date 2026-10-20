import Observation
import OpenCloudreveCore

/// App 级会话状态：镜像 `AccountRepository.activeAccount`，根视图据此在登录流程与主界面之间切换
@MainActor
@Observable
final class AppSession {
    private(set) var activeAccount: Account?
    /// 无当前账户时登录页的预填信息（修改密码后设置；再次出现当前账户时清除）
    var reloginHint: ReloginHint?
    @ObservationIgnored private var task: Task<Void, Never>?

    init(accounts: AccountRepository) {
        activeAccount = accounts.activeAccount.value
        task = Task { [weak self, accounts] in
            for await account in accounts.activeAccount {
                guard let self else { return }
                self.activeAccount = account
                if account != nil { self.reloginHint = nil }
            }
        }
    }

    isolated deinit {
        task?.cancel()
    }
}
