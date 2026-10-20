import XCTest

/// 端到端：像用户一样连接 CI 中启动的本地 OpenCloudreve（`http://127.0.0.1:5212`），
/// 用预先注册的账号登录，进入主界面并打开账户切换器；关键屏截图作为测试附件保留。
final class LoginFlowUITests: XCTestCase {
    private let serverURL = "http://127.0.0.1:5212"
    private let email = "user@example.com"
    private let password = "User@12345"

    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testSignInAndOpenAccountSwitcher() {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()

        let urlField = app.textFields["server.url"]
        XCTAssertTrue(urlField.waitForExistence(timeout: 30), "服务器地址页未出现")
        capture(app, "01-server")
        urlField.tap()
        urlField.typeText(serverURL)
        app.buttons["server.continue"].tap()

        let emailField = app.textFields["login.email"]
        XCTAssertTrue(emailField.waitForExistence(timeout: 30), "登录页未出现")
        XCTAssertTrue(app.buttons["login.webLogin"].waitForExistence(timeout: 10), "4.19.11 站点应显示网页登录按钮")
        capture(app, "02-login-email")
        emailField.tap()
        emailField.typeText(email)
        app.buttons["login.continue"].tap()

        let passwordField = app.secureTextFields["login.password"]
        XCTAssertTrue(passwordField.waitForExistence(timeout: 30), "密码阶段未出现")
        passwordField.tap()
        passwordField.typeText(password)
        capture(app, "03-login-password")
        app.buttons["login.submit"].tap()

        let filesTab = app.descendants(matching: .any)["tab.files"]
        XCTAssertTrue(filesTab.waitForExistence(timeout: 30), "登录后未进入主界面")
        capture(app, "04-main-tabs")

        let meTab = app.tabBars.buttons["我的"].exists ? app.tabBars.buttons["我的"] : app.buttons["我的"].firstMatch
        meTab.tap()
        let card = app.descendants(matching: .any)["me.accountCard"]
        XCTAssertTrue(card.waitForExistence(timeout: 15), "我的页面未显示账户卡")
        capture(app, "05-me")

        app.buttons["me.switchAccount"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["switcher.list"].waitForExistence(timeout: 15), "账户切换器未出现")
        XCTAssertTrue(app.descendants(matching: .any)["switcher.add"].exists)
        capture(app, "06-account-switcher")
    }

    /// 等动画结束后截全屏并永久保留为测试附件
    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        Thread.sleep(forTimeInterval: 1)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
