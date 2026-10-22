import UIKit
import XCTest

/// Phase 11 收尾的端到端检查：
/// - 深链：未登录时收到 `opencloudreve://open?url=` 登录后打开分享；`cloudreve://<id>:<pw>@share` 直接打开；OAuth 回调深链被忽略；
/// - 实时刷新：停在目录中，测试代码以另一个 `X-Cr-Client-Id` 建文件，列表在 10 秒内出现；进入后台超过 10 秒再回来也能补上；
/// - iPad：横屏分栏布局各主要页面截图 + 键盘快捷键（⌘N / ⌘R / ⌘F；⌘⌫ 只能真机验证）与多选删除确认；只在 iPad 目标上运行；
/// - 外观：英文界面、深色模式、辅助功能大字号下截登录页与主要页面。
/// 每个用例结束时退出全部账户（其它 UI 测试从服务器地址页开始）。
final class UIPolishUITests: XCTestCase {
    private let serverURL = "http://127.0.0.1:5212"
    private let email = "user@example.com"
    private let password = "User@12345"

    override func setUp() {
        continueAfterFailure = false
    }

    // MARK: - 深链

    @MainActor
    func testShareDeepLinks() async throws {
        try XCTSkipIf(isPad, "iPad 目标只跑布局用例")
        let token = try await TestServerAPI.login(email: email, password: password)
        // 放在「工作」下，避免根目录条目增多影响其它 UI 测试
        let folder = TestServerAPI.myUri("工作", "深链测试")
        try await TestServerAPI.create(token: token, uri: folder, type: "folder")
        try await TestServerAPI.create(token: token, uri: TestServerAPI.myUri("工作", "深链测试", "deeplink.txt"), type: "file")
        let open = try await TestServerAPI.createShare(token: token, uri: folder, isPrivate: false)
        let locked = try await TestServerAPI.createShare(token: token, uri: folder, isPrivate: true)

        let app = launch(language: "zh-Hans")
        addTeardownBlock { @MainActor in UIPolishUITests.bestEffortSignOut(XCUIApplication()) }
        ensureSignedOut(app)

        // 未登录时收到分享深链：先保留，登录进入主界面后打开
        let shareURL = "\(serverURL)/s/\(open.id)"
        let encoded = shareURL.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? shareURL
        app.open(try XCTUnwrap(URL(string: "opencloudreve://open?url=" + encoded)))
        XCTAssertTrue(app.textFields["server.url"].waitForExistence(timeout: 10), "未登录时深链不应离开服务器地址页")
        signIn(app)
        let sharedFile = labelled(app, "deeplink.txt")
        XCTAssertTrue(sharedFile.waitForExistence(timeout: 30), "登录后未打开深链中的分享")
        capture(app, "P01-deeplink-after-login")
        closeSheet(app)
        XCTAssertTrue(waitForDisappearance(sharedFile, timeout: 10), "分享浏览未关闭")

        // cloudreve://<id>:<密码>@share：带密码直接进入
        let password = try XCTUnwrap(locked.password, "私密分享未返回密码")
        app.open(try XCTUnwrap(URL(string: "cloudreve://\(locked.id):\(password)@share")))
        XCTAssertTrue(sharedFile.waitForExistence(timeout: 30), "cloudreve:// 分享深链未打开")
        XCTAssertFalse(app.secureTextFields["shareVisit.password"].exists, "URI 已带密码，不应再要求输入")
        capture(app, "P02-deeplink-cloudreve-uri")
        closeSheet(app)
        XCTAssertTrue(waitForDisappearance(sharedFile, timeout: 10), "分享浏览未关闭")

        // OAuth 回调只给 ASWebAuthenticationSession：App 内不打开任何页面
        app.open(try XCTUnwrap(URL(string: "opencloudreve://oauth/callback?code=x&state=y")))
        pause(3)
        XCTAssertFalse(sharedFile.exists, "OAuth 回调深链不应打开分享浏览")
        XCTAssertTrue(mainScreenVisible(app), "OAuth 回调深链后应停留在主界面")
    }

    // MARK: - 实时刷新（SSE）

    @MainActor
    func testLiveRefreshFromServerEvents() async throws {
        try XCTSkipIf(isPad, "iPad 目标只跑布局用例")
        let token = try await TestServerAPI.login(email: email, password: password)
        let folder = "实时刷新"
        try await TestServerAPI.create(token: token, uri: TestServerAPI.myUri("工作", folder), type: "folder")
        let stamp = Int(Date.now.timeIntervalSince1970)

        let app = launch(language: "zh-Hans")
        addTeardownBlock { @MainActor in UIPolishUITests.bestEffortSignOut(XCUIApplication()) }
        ensureSignedIn(app)
        element(app, "files.location.my").tap()
        let work = element(app, "file.工作")
        XCTAssertTrue(work.waitForExistence(timeout: 30), "我的文件中没有「工作」")
        work.tap()
        let folderRow = element(app, "file." + folder)
        XCTAssertTrue(folderRow.waitForExistence(timeout: 30), "「工作」中没有「\(folder)」")
        folderRow.tap()
        // 等列表加载完成、事件订阅建立
        pause(3)

        // 前台：其它客户端建文件 → 服务端 5 秒去抖后推送 → 列表刷新
        let first = "sse-\(stamp)-front.txt"
        try await TestServerAPI.create(token: token, uri: TestServerAPI.myUri("工作", folder, first), type: "file")
        let started = Date.now
        XCTAssertTrue(element(app, "file." + first).waitForExistence(timeout: 10), "前台时其它客户端新建的文件未在 10 秒内出现")
        record("前台事件到列表更新耗时 \(String(format: "%.1f", Date.now.timeIntervalSince(started))) 秒")
        capture(app, "P03-live-refresh-foreground")

        // 后台超过 10 秒（订阅已断开）期间建文件，回到前台后重连补上
        XCUIDevice.shared.press(.home)
        pause(13)
        let second = "sse-\(stamp)-background.txt"
        try await TestServerAPI.create(token: token, uri: TestServerAPI.myUri("工作", folder, second), type: "file")
        app.activate()
        let resumed = Date.now
        XCTAssertTrue(element(app, "file." + second).waitForExistence(timeout: 15), "回到前台后未补上后台期间新建的文件")
        record("回到前台到列表更新耗时 \(String(format: "%.1f", Date.now.timeIntervalSince(resumed))) 秒")
        capture(app, "P04-live-refresh-resumed")
    }

    // MARK: - iPad 布局与键盘快捷键

    @MainActor
    func testIPadLayoutAndKeyboardShortcuts() async throws {
        try XCTSkipUnless(isPad, "只在 iPad 目标上运行")
        let token = try await TestServerAPI.login(email: email, password: password)
        let app = launch(language: "zh-Hans")
        addTeardownBlock { @MainActor in
            UIPolishUITests.bestEffortSignOut(XCUIApplication())
            XCUIDevice.shared.orientation = .portrait
        }
        ensureSignedIn(app)
        // 进入主界面后再转横屏：在刚启动的 iPad 模拟器上、App 启动前转屏偶发超时（“Failed to set device orientation”）
        XCUIDevice.shared.orientation = .landscapeLeft

        // 文件：分栏（侧栏位置 + 默认选中我的文件）
        XCTAssertTrue(element(app, "file.文档").waitForExistence(timeout: 30), "iPad 分栏未默认显示我的文件")
        capture(app, "iPad-01-files-split")

        // ⌘N 新建文件夹（系统 alert 中的输入框不带自定义标识）
        let newFolderAlert = app.alerts.firstMatch
        XCTAssertTrue(pressUntilAppears(app, "n", .command, newFolderAlert), "⌘N 未弹出新建文件夹")
        XCTAssertTrue(newFolderAlert.textFields.firstMatch.exists, "新建文件夹弹窗没有输入框")
        capture(app, "iPad-02-cmd-n")
        newFolderAlert.buttons["取消"].tap()

        // 推入子目录后 ⌘N 仍可用（命令目标随可见列表切换）
        element(app, "file.文档").tap()
        XCTAssertTrue(element(app, "file.note-001.txt").waitForExistence(timeout: 30), "文档目录未列出文件")
        capture(app, "iPad-03-folder")
        XCTAssertTrue(pressUntilAppears(app, "n", .command, app.alerts.firstMatch), "子目录中 ⌘N 未弹出新建文件夹")
        app.alerts.firstMatch.buttons["取消"].tap()

        // ⌘R 刷新：其它客户端在当前目录新建文件后按 ⌘R，文件应在目录事件推送（服务端约 5 秒去抖）之前出现
        let refreshed = "a-cmd-r-\(Int(Date.now.timeIntervalSince1970)).txt"
        let refreshedUri = TestServerAPI.myUri("文档", refreshed)
        try await TestServerAPI.create(token: token, uri: refreshedUri, type: "file")
        addTeardownBlock { _ = try? await TestServerAPI.delete(token: token, uris: [refreshedUri]) }
        app.typeKey("r", modifierFlags: .command)
        XCTAssertTrue(element(app, "file." + refreshed).waitForExistence(timeout: 3), "⌘R 后 3 秒内未出现新文件（没有执行刷新）")

        // 多选 + 底栏“删除”的确认弹层（iPad 上为 popover；只到确认框，取消）。
        // ⌘⌫ 只能真机验证（docs/manual-qa-ios.md）：XCUITest 在 iPad 模拟器上合成的 Delete 键（⌘⌫/⌫，\u{7F} 或 \u{8}）
        // 不会作为按键命令送达 App——诊断时同一响应者上注册的 ⌘K 能触发删除，⌘⌫ 各种写法都不能
        openMoreMenu(app)
        app.buttons["files.select"].firstMatch.tap()
        XCTAssertTrue(app.buttons["files.selectDone"].waitForExistence(timeout: 10), "未进入多选模式")
        element(app, "file.note-001.txt").tap()
        element(app, "file.note-002.txt").tap()
        XCTAssertTrue(labelled(app, "已选择 2 项").waitForExistence(timeout: 10), "未选中两项")
        app.buttons["files.selection.delete"].firstMatch.tap()
        XCTAssertTrue(app.buttons["files.delete.trash"].firstMatch.waitForExistence(timeout: 10), "多选删除未弹出确认")
        capture(app, "iPad-04-delete-confirm")
        dismissPopover(app)
        app.buttons["files.selectDone"].firstMatch.tap()

        // ⌘F 切到搜索
        goBack(app, from: "文档")
        XCTAssertTrue(pressUntilAppears(app, "f", .command, app.searchFields.firstMatch), "⌘F 未切到搜索")
        capture(app, "iPad-05-cmd-f-search")
        openTab(app, ["文件"])

        // 预览器（图片）
        let previewFolder = element(app, "file.预览")
        if previewFolder.waitForExistence(timeout: 15) {
            previewFolder.tap()
            let photo = element(app, "file.photo.jpg")
            XCTAssertTrue(photo.waitForExistence(timeout: 30), "「预览」目录未列出 photo.jpg")
            photo.tap()
            // 本机没有记住打开方式时（iPad 模拟器上首次打开）先出现打开方式选择
            let image = element(app, "image.view")
            let chooser = element(app, "viewer.option.image")
            let deadline = Date.now.addingTimeInterval(30)
            while Date.now < deadline && !image.exists && !chooser.exists {
                pause(0.5)
            }
            if chooser.exists {
                capture(app, "iPad-06-viewer-chooser")
                chooser.tap()
            }
            XCTAssertTrue(image.waitForExistence(timeout: 30), "图片预览未显示")
            capture(app, "iPad-07-viewer", settle: 2)
            app.buttons["viewer.close"].firstMatch.tap()
            // 关闭全屏预览后键盘命令仍作用于目录列表
            XCTAssertTrue(pressUntilAppears(app, "n", .command, app.alerts.firstMatch), "关闭预览后 ⌘N 未弹出新建文件夹")
            app.alerts.firstMatch.buttons["取消"].tap()
            goBack(app, from: "预览")
        }

        // 其它 Tab
        openTab(app, ["分享"])
        capture(app, "iPad-08-shares", settle: 2)
        openTab(app, ["任务"])
        capture(app, "iPad-09-tasks", settle: 2)
        openTab(app, ["我的"])
        XCTAssertTrue(element(app, "me.accountCard").waitForExistence(timeout: 20), "“我的”页未出现")
        capture(app, "iPad-10-me", settle: 2)
        tapWhenHittable(app, element(app, "me.settings"))
        XCTAssertTrue(element(app, "settings.language").waitForExistence(timeout: 15), "设置页未出现")
        capture(app, "iPad-11-settings")
        goBack(app, from: "设置")
        let admin = element(app, "me.admin")
        if admin.waitForExistence(timeout: 15) {
            tapWhenHittable(app, admin)
            XCTAssertTrue(element(app, "admin.totals").waitForExistence(timeout: 30), "管理概览未出现")
            capture(app, "iPad-12-admin", settle: 2)
            goBack(app, from: "管理")
        }

        // 竖屏：分栏折叠
        XCUIDevice.shared.orientation = .portrait
        openTab(app, ["文件"])
        capture(app, "iPad-13-files-portrait", settle: 2)
    }

    // MARK: - 外观：英文 / 深色 / 大字号

    @MainActor
    func testEnglishInterface() throws {
        try XCTSkipIf(isPad, "iPad 目标只跑布局用例")
        let app = launch(language: "en")
        tourAuthAndMainScreens(app, prefix: "EN")
    }

    @MainActor
    func testDarkMode() throws {
        try XCTSkipIf(isPad, "iPad 目标只跑布局用例")
        // XCUIDevice.appearance 在 CI 模拟器上不生效，改用 Debug 夹具强制深色
        let app = launch(language: "zh-Hans", extra: ["-OCRUITestColorScheme", "dark"])
        tourAuthAndMainScreens(app, prefix: "DARK")
    }

    @MainActor
    func testAccessibilityLargeType() throws {
        try XCTSkipIf(isPad, "iPad 目标只跑布局用例")
        let app = launch(language: "zh-Hans", extra: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityL"])
        tourAuthAndMainScreens(app, prefix: "AXL")
    }

    /// 登录页（服务器地址、邮箱）→ 登录 → 文件根页、我的文件列表、任务、我的、设置
    @MainActor
    private func tourAuthAndMainScreens(_ app: XCUIApplication, prefix: String) {
        addTeardownBlock { @MainActor in UIPolishUITests.bestEffortSignOut(XCUIApplication()) }
        ensureSignedOut(app)
        capture(app, "\(prefix)-01-server")
        enterServer(app)
        let emailField = app.textFields["login.email"]
        XCTAssertTrue(emailField.waitForExistence(timeout: 30), "登录页未出现")
        capture(app, "\(prefix)-02-login")
        enterCredentials(app)
        XCTAssertTrue(mainScreenAppears(app), "未进入主界面")

        let my = element(app, "files.location.my")
        XCTAssertTrue(my.waitForExistence(timeout: 20), "文件根页未显示")
        capture(app, "\(prefix)-03-files-root", settle: 2)
        my.tap()
        XCTAssertTrue(element(app, "file.工作").waitForExistence(timeout: 30), "我的文件未列出预置目录")
        capture(app, "\(prefix)-04-my-files", settle: 2)
        openTab(app, ["任务", "Tasks"])
        capture(app, "\(prefix)-05-tasks", settle: 2)
        openTab(app, ["我的", "Me"])
        // “我的”页是懒加载列表：大字号下设置入口在屏幕外、尚不在层级中，先确认页面出现，再滚动到设置入口
        XCTAssertTrue(element(app, "me.accountCard").waitForExistence(timeout: 20), "“我的”页未出现")
        capture(app, "\(prefix)-06-me", settle: 2)
        tapWhenHittable(app, element(app, "me.settings"))
        // 等设置页顶部的条目（大字号下“安全”等行在屏幕外、不在层级中）
        XCTAssertTrue(element(app, "settings.language").waitForExistence(timeout: 15), "设置页未出现")
        capture(app, "\(prefix)-07-settings")
    }

    // MARK: - 启动与登录

    @MainActor
    private var isPad: Bool {
        UIDevice.current.userInterfaceIdiom == .pad
    }

    /// 同步等待（异步用例中不能直接调用 `Thread.sleep`）
    @MainActor
    private func pause(_ seconds: TimeInterval) {
        Thread.sleep(forTimeInterval: seconds)
    }

    @MainActor
    private func launch(language: String, extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        let locale = language == "en" ? "en_US" : "zh_CN"
        app.launchArguments += ["-AppleLanguages", "(\(language))", "-AppleLocale", locale] + extra
        app.launch()
        return app
    }

    /// 等待启动后的首屏：服务器地址页或主界面
    @MainActor
    private func waitForFirstScreen(_ app: XCUIApplication) {
        let deadline = Date.now.addingTimeInterval(30)
        while Date.now < deadline && !app.textFields["server.url"].exists && !mainScreenVisible(app) {
            Thread.sleep(forTimeInterval: 0.5)
        }
    }

    @MainActor
    private func ensureSignedIn(_ app: XCUIApplication) {
        waitForFirstScreen(app)
        if app.textFields["server.url"].exists {
            signIn(app)
        }
        XCTAssertTrue(mainScreenAppears(app), "未进入主界面")
    }

    @MainActor
    private func ensureSignedOut(_ app: XCUIApplication) {
        waitForFirstScreen(app)
        if !app.textFields["server.url"].exists {
            Self.bestEffortSignOut(app)
        }
        XCTAssertTrue(app.textFields["server.url"].waitForExistence(timeout: 15), "未能回到服务器地址页")
    }

    @MainActor
    private func signIn(_ app: XCUIApplication) {
        enterServer(app)
        XCTAssertTrue(app.textFields["login.email"].waitForExistence(timeout: 30), "登录页未出现")
        enterCredentials(app)
    }

    @MainActor
    private func enterServer(_ app: XCUIApplication) {
        let urlField = app.textFields["server.url"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 30), "服务器地址页未出现")
        urlField.replaceText(serverURL)
        app.buttons["server.continue"].tap()
    }

    @MainActor
    private func enterCredentials(_ app: XCUIApplication) {
        app.textFields["login.email"].replaceText(email)
        app.buttons["login.continue"].tap()
        let passwordField = app.secureTextFields["login.password"]
        XCTAssertTrue(passwordField.waitForExistence(timeout: 30), "密码阶段未出现")
        passwordField.replaceText(password)
        app.buttons["login.submit"].tap()
    }

    @MainActor
    private func mainScreenVisible(_ app: XCUIApplication) -> Bool {
        element(app, "tab.files").exists || element(app, "files.location.my").exists || element(app, "file.工作").exists
    }

    @MainActor
    private func mainScreenAppears(_ app: XCUIApplication, timeout: TimeInterval = 30) -> Bool {
        let deadline = Date.now.addingTimeInterval(timeout)
        while Date.now < deadline {
            if mainScreenVisible(app) { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return false
    }

    /// 退出全部账户（不做断言，中英文界面均可）
    @MainActor
    private static func bestEffortSignOut(_ app: XCUIApplication) {
        let urlField = app.textFields["server.url"]
        for _ in 0..<4 {
            guard app.state == .runningForeground, !urlField.exists else { return }
            let me = ["我的", "Me"].lazy.flatMap { [app.tabBars.buttons[$0], app.buttons[$0].firstMatch, app.cells[$0].firstMatch] }.first { $0.exists && $0.isHittable }
            guard let me else { return }
            me.tap()
            let button = app.buttons["me.signOut"]
            app.scrollUntilHittable(button)
            guard button.exists else { return }
            button.tap()
            let confirm = app.buttons.matching(NSPredicate(format: "(label == %@ OR label == %@) AND identifier != %@", "退出登录", "Sign Out", "me.signOut")).firstMatch
            if confirm.waitForExistence(timeout: 5) { confirm.tap() }
            _ = urlField.waitForExistence(timeout: 10)
        }
    }

    // MARK: - 界面辅助

    /// 切换 Tab（标题按界面语言给出候选；Tab 栏收起时也能点到）
    @MainActor
    private func openTab(_ app: XCUIApplication, _ titles: [String]) {
        for _ in 0..<4 {
            // iPhone：底部 Tab 栏按钮；iPad 横屏：侧栏中的单元格
            let candidates = titles.flatMap { [app.tabBars.buttons[$0], app.buttons[$0].firstMatch, app.cells[$0].firstMatch] }
            if let tab = candidates.first(where: { $0.exists && $0.isHittable }) {
                tab.tap()
                return
            }
            Thread.sleep(forTimeInterval: 1)
        }
        XCTFail("找不到 Tab：\(titles)")
    }

    /// 元素在 Tab 栏下方等不可点处时先上滑，再点按
    @MainActor
    private func tapWhenHittable(_ app: XCUIApplication, _ target: XCUIElement) {
        app.scrollUntilHittable(target)
        target.tap()
    }

    /// 按快捷键直到 `expected` 出现，最多 3 次。列表刚显示时命令目标经焦点值传到菜单系统要几秒
    /// （CI 上进入主界面约 3 秒内按 ⌘N 无反应，5 秒后正常），所以按下后等不到就再按
    @MainActor
    private func pressUntilAppears(_ app: XCUIApplication, _ key: String, _ modifiers: XCUIElement.KeyModifierFlags, _ expected: XCUIElement) -> Bool {
        for _ in 0..<3 {
            app.typeKey(key, modifierFlags: modifiers)
            if expected.waitForExistence(timeout: 5) { return true }
        }
        return false
    }

    @MainActor
    private func openMoreMenu(_ app: XCUIApplication) {
        let more = app.buttons["files.more"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 10), "找不到“更多”菜单")
        more.tap()
    }

    /// 关闭 sheet：点“关闭”，否则下滑
    @MainActor
    private func closeSheet(_ app: XCUIApplication) {
        let close = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "关闭", "Close")).firstMatch
        if close.waitForExistence(timeout: 5) && close.isHittable {
            close.tap()
        } else {
            app.swipeDown(velocity: .fast)
        }
    }

    /// 关闭确认弹层（iPad 上为 popover）：点“取消”，否则点弹层外
    @MainActor
    private func dismissPopover(_ app: XCUIApplication) {
        let cancel = app.buttons["取消"].firstMatch
        if cancel.exists && cancel.isHittable {
            cancel.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.95)).tap()
        }
        Thread.sleep(forTimeInterval: 1)
    }

    /// 导航栏返回：给出当前页标题时点该导航栏的第一个按钮（iPad 分栏有多个导航栏），否则点任意导航栏的第一个按钮
    @MainActor
    private func goBack(_ app: XCUIApplication, from title: String? = nil) {
        if let title {
            let bar = app.navigationBars[title]
            if bar.waitForExistence(timeout: 5), bar.buttons.firstMatch.exists {
                bar.buttons.element(boundBy: 0).tap()
                return
            }
        }
        let back = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: 10), "找不到返回按钮")
        back.tap()
    }

    @MainActor
    private func waitForDisappearance(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element)
        return XCTWaiter().wait(for: [gone], timeout: timeout) == .completed
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    @MainActor
    private func labelled(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    @MainActor
    private func record(_ text: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = text
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// 等动画结束后截全屏并永久保留为测试附件
    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String, settle: TimeInterval = 1) {
        Thread.sleep(forTimeInterval: settle)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
