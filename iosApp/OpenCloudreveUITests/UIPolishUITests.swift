import UIKit
import XCTest

/// Phase 11 收尾的端到端检查：
/// - 深链：未登录时收到 `opencloudreve://open?url=` 登录后打开分享；`cloudreve://<id>:<pw>@share` 直接打开；OAuth 回调深链被忽略；
/// - 实时刷新：停在目录中，测试代码以另一个 `X-Cr-Client-Id` 建文件，列表在 10 秒内出现；进入后台超过 10 秒再回来也能补上；
/// - iPad：横屏分栏布局各主要页面截图 + 键盘快捷键（⌘N / ⌘R / ⌘F / Delete）；只在 iPad 目标上运行；
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
    func testIPadLayoutAndKeyboardShortcuts() throws {
        try XCTSkipUnless(isPad, "只在 iPad 目标上运行")
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = launch(language: "zh-Hans")
        addTeardownBlock { @MainActor in
            UIPolishUITests.bestEffortSignOut(XCUIApplication())
            XCUIDevice.shared.orientation = .portrait
        }
        ensureSignedIn(app)

        // 文件：分栏（侧栏位置 + 默认选中我的文件）
        XCTAssertTrue(element(app, "file.文档").waitForExistence(timeout: 30), "iPad 分栏未默认显示我的文件")
        capture(app, "iPad-01-files-split")

        // ⌘N 新建文件夹
        app.typeKey("n", modifierFlags: .command)
        let newFolderField = element(app, "files.newFolder.field")
        XCTAssertTrue(newFolderField.waitForExistence(timeout: 10), "⌘N 未弹出新建文件夹")
        capture(app, "iPad-02-cmd-n")
        app.alerts.firstMatch.buttons["取消"].tap()

        // ⌘R 刷新（不应出错，列表仍在）
        app.typeKey("r", modifierFlags: .command)
        XCTAssertTrue(element(app, "file.文档").waitForExistence(timeout: 15), "⌘R 刷新后列表消失")

        // 多选 + Delete 删除所选（只到确认框，取消）
        element(app, "file.文档").tap()
        XCTAssertTrue(element(app, "file.note-001.txt").waitForExistence(timeout: 30), "文档目录未列出文件")
        capture(app, "iPad-03-folder")
        openMoreMenu(app)
        app.buttons["files.select"].firstMatch.tap()
        XCTAssertTrue(app.buttons["files.selectDone"].waitForExistence(timeout: 10), "未进入多选模式")
        element(app, "file.note-001.txt").tap()
        element(app, "file.note-002.txt").tap()
        app.typeKey(XCUIKeyboardKey.delete.rawValue, modifierFlags: [])
        let trash = app.buttons["files.delete.trash"].firstMatch
        XCTAssertTrue(trash.waitForExistence(timeout: 10), "Delete 未弹出删除确认")
        capture(app, "iPad-04-delete-key")
        dismissPopover(app)
        app.buttons["files.selectDone"].firstMatch.tap()

        // 预览器（图片）
        goBack(app)
        let previewFolder = element(app, "file.预览")
        if previewFolder.waitForExistence(timeout: 15) {
            previewFolder.tap()
            let photo = element(app, "file.photo.jpg")
            XCTAssertTrue(photo.waitForExistence(timeout: 30), "「预览」目录未列出 photo.jpg")
            photo.tap()
            XCTAssertTrue(element(app, "image.view").waitForExistence(timeout: 30), "图片预览未显示")
            capture(app, "iPad-05-viewer", settle: 2)
            app.buttons["viewer.close"].firstMatch.tap()
            goBack(app)
        }

        // ⌘F 切到搜索
        app.typeKey("f", modifierFlags: .command)
        Thread.sleep(forTimeInterval: 1)
        capture(app, "iPad-06-cmd-f-search")
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 10), "⌘F 未切到搜索")

        // 其它 Tab
        openTab(app, ["分享"])
        capture(app, "iPad-07-shares", settle: 2)
        openTab(app, ["任务"])
        capture(app, "iPad-08-tasks", settle: 2)
        openTab(app, ["我的"])
        XCTAssertTrue(element(app, "me.settings").waitForExistence(timeout: 20), "“我的”页未出现")
        capture(app, "iPad-09-me", settle: 2)
        element(app, "me.settings").tap()
        XCTAssertTrue(element(app, "settings.security").waitForExistence(timeout: 15), "设置页未出现")
        capture(app, "iPad-10-settings")
        goBack(app)
        let admin = element(app, "me.admin")
        if admin.waitForExistence(timeout: 15) {
            admin.tap()
            XCTAssertTrue(element(app, "admin.totals").waitForExistence(timeout: 30), "管理概览未出现")
            capture(app, "iPad-11-admin", settle: 2)
            goBack(app)
        }

        // 竖屏：分栏折叠
        XCUIDevice.shared.orientation = .portrait
        openTab(app, ["文件"])
        capture(app, "iPad-12-files-portrait", settle: 2)
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
        XCUIDevice.shared.appearance = .dark
        addTeardownBlock { @MainActor in XCUIDevice.shared.appearance = .light }
        let app = launch(language: "zh-Hans")
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
        XCTAssertTrue(element(app, "me.settings").waitForExistence(timeout: 20), "“我的”页未出现")
        capture(app, "\(prefix)-06-me", settle: 2)
        element(app, "me.settings").tap()
        XCTAssertTrue(element(app, "settings.security").waitForExistence(timeout: 15), "设置页未出现")
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
        // 地址栏可能预填了内容：点在右端让光标落在末尾，再删掉已有内容
        urlField.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        if let current = urlField.value as? String, !current.isEmpty, current != urlField.placeholderValue {
            urlField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
        }
        urlField.typeText(serverURL)
        app.buttons["server.continue"].tap()
    }

    @MainActor
    private func enterCredentials(_ app: XCUIApplication) {
        let emailField = app.textFields["login.email"]
        emailField.tap()
        emailField.typeText(email)
        app.buttons["login.continue"].tap()
        let passwordField = app.secureTextFields["login.password"]
        XCTAssertTrue(passwordField.waitForExistence(timeout: 30), "密码阶段未出现")
        passwordField.tap()
        passwordField.typeText(password)
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
            let me = ["我的", "Me"].lazy.flatMap { [app.tabBars.buttons[$0], app.buttons[$0].firstMatch] }.first { $0.exists && $0.isHittable }
            guard let me else { return }
            me.tap()
            let button = app.buttons["me.signOut"]
            var attempts = 0
            while !(button.exists && button.isHittable) && attempts < 6 {
                app.swipeUp()
                attempts += 1
            }
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
            let candidates = titles.flatMap { [app.tabBars.buttons[$0], app.buttons[$0].firstMatch] }
            if let tab = candidates.first(where: { $0.exists && $0.isHittable }) {
                tab.tap()
                return
            }
            Thread.sleep(forTimeInterval: 1)
        }
        XCTFail("找不到 Tab：\(titles)")
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

    @MainActor
    private func goBack(_ app: XCUIApplication) {
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
