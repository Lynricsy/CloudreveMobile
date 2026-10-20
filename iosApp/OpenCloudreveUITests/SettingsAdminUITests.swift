import XCTest

/// 端到端：“我的”页、设置、账户安全、两步验证二维码（只展示不启用）、新建 WebDAV 账号、管理概览与用户列表；
/// 最后用 CI 预置的普通账号确认管理入口不显示，再切回管理员账号。
/// CI 中 `user@example.com` 是首个注册用户（管理员），`member@example.com` 为普通用户（见 .github/workflows/ios.yml）。
final class SettingsAdminUITests: XCTestCase {
    private let serverURL = "http://127.0.0.1:5212"
    private let adminEmail = "user@example.com"
    private let adminPassword = "User@12345"
    private let memberEmail = "member@example.com"
    private let memberPassword = "Member@12345"

    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testSettingsSecurityWebDAVAndAdmin() {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()

        ensureSignedIn(app, email: adminEmail, password: adminPassword)
        openMeTab(app)
        ensureActiveAccount(app, email: adminEmail, password: adminPassword)

        // “我的”页：管理员可见“管理”入口（资料加载完成后出现）
        XCTAssertTrue(element(app, "me.admin").waitForExistence(timeout: 30), "管理员的“我的”页未显示管理入口")
        XCTAssertTrue(element(app, "me.capacity").waitForExistence(timeout: 15), "未显示存储容量")
        capture(app, "SA01-me")

        // 设置首页
        element(app, "me.settings").tap()
        XCTAssertTrue(element(app, "settings.security").waitForExistence(timeout: 15), "设置首页未出现")
        capture(app, "SA02-settings")

        // 账户安全
        element(app, "settings.security").tap()
        let enable2fa = element(app, "security.enable2fa")
        XCTAssertTrue(enable2fa.waitForExistence(timeout: 20), "安全页未显示“启用两步验证”")
        capture(app, "SA03-security")

        // 两步验证二维码：只展示，取消后不启用
        enable2fa.tap()
        XCTAssertTrue(element(app, "twofa.qr").waitForExistence(timeout: 20), "未显示两步验证二维码")
        XCTAssertTrue(element(app, "twofa.secret").exists)
        capture(app, "SA04-2fa-qr")
        app.buttons["twofa.cancel"].tap()
        XCTAssertTrue(enable2fa.waitForExistence(timeout: 15), "取消后应回到安全页且 2FA 仍未开启")

        // WebDAV：新建账号
        goBack(app)
        element(app, "settings.webdav").tap()
        let add = app.buttons["webdav.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 15), "WebDAV 页未出现")
        waitUntilEnabled(add)
        add.tap()
        let name = app.textFields["webdav.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 15), "新建 WebDAV 账号表单未出现")
        name.tap()
        name.typeText("Laptop")
        capture(app, "SA05-webdav-new")
        app.buttons["webdav.save"].tap()
        XCTAssertTrue(
            app.descendants(matching: .any).matching(labelContains: "Laptop").firstMatch.waitForExistence(timeout: 20),
            "保存后列表中未出现新账号"
        )
        capture(app, "SA06-webdav-list")

        // 管理：概览（总数卡片 + 12 天图表）
        goBack(app)
        goBack(app)
        element(app, "me.admin").tap()
        XCTAssertTrue(element(app, "admin.totals").waitForExistence(timeout: 30), "管理概览未出现")
        capture(app, "SA07-admin-summary", settle: 2)

        // 用户列表：两个预置账号都在
        element(app, "admin.users").tap()
        XCTAssertTrue(
            app.descendants(matching: .any).matching(labelContains: memberEmail).firstMatch.waitForExistence(timeout: 20),
            "用户列表未显示普通用户"
        )
        XCTAssertTrue(app.descendants(matching: .any).matching(labelContains: adminEmail).firstMatch.exists)
        capture(app, "SA08-admin-users")

        // 普通账号：“我的”页不显示管理入口
        goBack(app)
        goBack(app)
        addAccount(app, email: memberEmail, password: memberPassword)
        openMeTab(app)
        XCTAssertTrue(
            accountCard(app, containing: memberEmail).waitForExistence(timeout: 20),
            "未切换到普通账号"
        )
        XCTAssertTrue(element(app, "me.capacity").waitForExistence(timeout: 20), "普通账号资料未加载")
        XCTAssertFalse(element(app, "me.admin").exists, "普通账号不应显示管理入口")
        capture(app, "SA09-member-me")

        // 切回管理员，避免影响其他测试
        switchTo(app, email: adminEmail)
        openMeTab(app)
        XCTAssertTrue(
            accountCard(app, containing: adminEmail).waitForExistence(timeout: 20),
            "未能切回管理员账号"
        )
    }

    // MARK: - 步骤

    /// 未登录时走服务器地址 → 登录；已登录则直接进入主界面
    @MainActor
    private func ensureSignedIn(_ app: XCUIApplication, email: String, password: String) {
        let urlField = app.textFields["server.url"]
        let filesTab = element(app, "tab.files")
        let deadline = Date.now.addingTimeInterval(30)
        while Date.now < deadline && !urlField.exists && !filesTab.exists {
            Thread.sleep(forTimeInterval: 0.5)
        }
        if urlField.exists {
            signIn(app, email: email, password: password)
        }
        XCTAssertTrue(filesTab.waitForExistence(timeout: 30), "未进入主界面")
    }

    /// 在服务器地址页（或账户切换器的添加账户流程）中登录
    @MainActor
    private func signIn(_ app: XCUIApplication, email: String, password: String) {
        let urlField = app.textFields["server.url"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 30), "服务器地址页未出现")
        // 地址栏可能预填了 “https://”：点在右端让光标落在末尾，再删掉已有内容
        urlField.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        if let current = urlField.value as? String, !current.isEmpty, current != urlField.placeholderValue {
            urlField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2))
        }
        urlField.typeText(serverURL)
        app.buttons["server.continue"].tap()

        let emailField = app.textFields["login.email"]
        XCTAssertTrue(emailField.waitForExistence(timeout: 30), "登录页未出现")
        emailField.tap()
        emailField.typeText(email)
        app.buttons["login.continue"].tap()

        let passwordField = app.secureTextFields["login.password"]
        XCTAssertTrue(passwordField.waitForExistence(timeout: 30), "密码阶段未出现")
        passwordField.tap()
        passwordField.typeText(password)
        app.buttons["login.submit"].tap()
    }

    /// 当前账户不是期望的账户时切换（不存在则添加）
    @MainActor
    private func ensureActiveAccount(_ app: XCUIApplication, email: String, password: String) {
        let card = element(app, "me.accountCard")
        XCTAssertTrue(card.waitForExistence(timeout: 20), "我的页面未显示账户卡")
        guard !card.label.contains(email) else { return }
        app.buttons["me.switchAccount"].tap()
        let row = app.buttons.matching(identifier: "switcher.account").matching(labelContains: email).firstMatch
        if row.waitForExistence(timeout: 10) {
            row.tap()
        } else {
            app.buttons["switcher.add"].tap()
            signIn(app, email: email, password: password)
        }
        XCTAssertTrue(element(app, "tab.files").waitForExistence(timeout: 30))
        openMeTab(app)
    }

    /// 通过账户切换器添加并切换到另一个账户（主界面随之重建）
    @MainActor
    private func addAccount(_ app: XCUIApplication, email: String, password: String) {
        let row = app.buttons.matching(identifier: "switcher.account").matching(labelContains: email).firstMatch
        app.buttons["me.switchAccount"].tap()
        XCTAssertTrue(element(app, "switcher.list").waitForExistence(timeout: 15), "账户切换器未出现")
        if row.waitForExistence(timeout: 3) {
            row.tap()
        } else {
            app.buttons["switcher.add"].tap()
            signIn(app, email: email, password: password)
        }
        XCTAssertTrue(element(app, "tab.files").waitForExistence(timeout: 30), "添加账户后未回到主界面")
    }

    @MainActor
    private func switchTo(_ app: XCUIApplication, email: String) {
        app.buttons["me.switchAccount"].tap()
        let row = app.buttons.matching(identifier: "switcher.account").matching(labelContains: email).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 15), "账户切换器中没有 \(email)")
        row.tap()
        XCTAssertTrue(element(app, "tab.files").waitForExistence(timeout: 30))
    }

    @MainActor
    private func openMeTab(_ app: XCUIApplication) {
        let meTab = app.tabBars.buttons["我的"].exists ? app.tabBars.buttons["我的"] : app.buttons["我的"].firstMatch
        XCTAssertTrue(meTab.waitForExistence(timeout: 15), "找不到“我的” Tab")
        meTab.tap()
    }

    /// 导航栏返回上一页
    @MainActor
    private func goBack(_ app: XCUIApplication) {
        let back = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: 10), "找不到返回按钮")
        back.tap()
    }

    @MainActor
    private func waitUntilEnabled(_ element: XCUIElement) {
        let enabled = expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: element)
        wait(for: [enabled], timeout: 20)
    }

    /// 标签中包含某邮箱的账户卡
    @MainActor
    private func accountCard(_ app: XCUIApplication, containing email: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: "me.accountCard").matching(labelContains: email).firstMatch
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
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

private extension XCUIElementQuery {
    func matching(labelContains text: String) -> XCUIElementQuery {
        matching(NSPredicate(format: "label CONTAINS %@", text))
    }
}
