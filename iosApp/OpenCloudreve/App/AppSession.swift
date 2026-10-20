import Observation
import OpenCloudreveCore

/// App 级会话状态：镜像 `AccountRepository.activeAccount`，根视图据此在登录流程与主界面之间切换
@MainActor
@Observable
final class AppSession {
    private(set) var activeAccount: Account?
    @ObservationIgnored private var task: Task<Void, Never>?

    init(accounts: AccountRepository) {
        activeAccount = accounts.activeAccount.value
        task = Task { [weak self, accounts] in
            for await account in accounts.activeAccount {
                guard let self else { return }
                self.activeAccount = account
            }
        }
    }

    isolated deinit {
        task?.cancel()
    }
}
