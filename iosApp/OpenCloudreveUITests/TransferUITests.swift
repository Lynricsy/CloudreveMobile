import XCTest

/// 端到端：传输。启动参数 `-OCRUITestLargeUpload 1024` 让 App（Debug 夹具）进入主界面后上传一个 1 GiB 稀疏文件，
/// 截图底部迷你栏、上传进行中与完成的传输列表；再从「照片」目录下载一张图片，截图下载完成项。结束时退出登录。
final class TransferUITests: XCTestCase {
    private let serverURL = "http://127.0.0.1:5212"
    private let email = "user@example.com"
    private let password = "User@12345"
    private let uploadName = "transfer-uitest.bin"

    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testUploadAndDownloadTransfers() {
        let app = XCUIApplication()
        // 中途失败时也退出登录，避免影响之后的 UI 测试
        addTeardownBlock { @MainActor in TransferUITests.bestEffortSignOut(XCUIApplication()) }
        // 256 MiB（11 片）+ 每批分片前等 2 秒（Debug 夹具），保证截图时上传仍在进行
        app.launchArguments += [
            "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
            "-OCRUITestLargeUpload", "256", "-OCRUITestChunkDelay", "2",
        ]
        app.launch()
        ensureSignedIn(app)

        // 迷你栏：有进行中的上传时出现在 Tab 栏上方
        let miniBar = element(app, "transfers.miniBar")
        XCTAssertTrue(miniBar.waitForExistence(timeout: 60), "迷你栏未出现")
        capture(app, "transfers-01-mini-bar")

        // 点按迷你栏进入任务 Tab：上传进行中
        if miniBar.isHittable {
            miniBar.tap()
        } else {
            openTab(app, "任务")
        }
        XCTAssertTrue(element(app, "transfer." + uploadName).waitForExistence(timeout: 20), "传输列表中没有上传项")
        capture(app, "transfers-02-uploading")

        // 等待上传完成
        let detail = element(app, "transfer.detail." + uploadName)
        XCTAssertTrue(waitForLabel(detail, containing: "已完成", timeout: 400), "上传未在时限内完成：\(detail.label)")
        capture(app, "transfers-03-upload-completed")

        // 文件 Tab → 照片 → 下载 photo-01.png
        openTab(app, "文件")
        let my = element(app, "files.location.my")
        XCTAssertTrue(my.waitForExistence(timeout: 20), "文件 Tab 根页未显示")
        my.tap()
        XCTAssertTrue(element(app, "file.照片").waitForExistence(timeout: 30), "我的文件未列出「照片」")
        element(app, "file.照片").tap()
        let photo = element(app, "file.photo-01.png")
        XCTAssertTrue(photo.waitForExistence(timeout: 30), "照片目录未列出图片")
        photo.press(forDuration: 1.2)
        let downloadItem = app.buttons["下载"].firstMatch
        XCTAssertTrue(downloadItem.waitForExistence(timeout: 10), "上下文菜单中没有“下载”")
        downloadItem.tap()

        openTab(app, "任务")
        let downloadDetail = element(app, "transfer.detail.photo-01.png")
        XCTAssertTrue(downloadDetail.waitForExistence(timeout: 20), "传输列表中没有下载项")
        XCTAssertTrue(waitForLabel(downloadDetail, containing: "已完成", timeout: 60), "下载未完成：\(downloadDetail.label)")
        XCTAssertTrue(element(app, "transfer.share.photo-01.png").waitForExistence(timeout: 10), "已下载项没有导出按钮")
        capture(app, "transfers-04-download-completed")

        // 清除已完成
        let clear = app.buttons["transfers.clearFinished"]
        XCTAssertTrue(clear.waitForExistence(timeout: 10), "没有“清除已完成”")
        clear.tap()
        XCTAssertTrue(waitForDisappearance(downloadDetail, timeout: 10), "清除已完成后下载项仍在")
        capture(app, "transfers-05-cleared")

        signOut(app)
    }

    // MARK: - 辅助

    /// 测试结束（含中途失败）时若仍在登录状态则退出全部账户；不做断言
    @MainActor
    private static func bestEffortSignOut(_ app: XCUIApplication) {
        for _ in 0..<4 {
            guard app.state == .runningForeground, !app.textFields["server.url"].exists else { return }
            let me = app.tabBars.buttons["我的"]
            guard me.waitForExistence(timeout: 3) else { return }
            me.tap()
            let button = app.buttons["me.signOut"]
            var attempts = 0
            while !(button.exists && button.isHittable) && attempts < 6 {
                app.swipeUp()
                attempts += 1
            }
            guard button.exists else { return }
            button.tap()
            let confirm = app.sheets.buttons["退出登录"]
            if confirm.waitForExistence(timeout: 5) { confirm.tap() }
            _ = app.textFields["server.url"].waitForExistence(timeout: 10)
        }
    }

    @MainActor
    private func ensureSignedIn(_ app: XCUIApplication) {
        let urlField = app.textFields["server.url"]
        let filesTab = element(app, "tab.files")
        let deadline = Date.now.addingTimeInterval(30)
        while Date.now < deadline && !urlField.exists && !filesTab.exists {
            Thread.sleep(forTimeInterval: 0.5)
        }
        if urlField.exists {
            urlField.tap()
            if let existing = urlField.value as? String, !existing.isEmpty, existing != urlField.placeholderValue {
                urlField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
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
        XCTAssertTrue(filesTab.waitForExistence(timeout: 30), "未进入主界面")
    }

    /// 退出登录；模拟器里可能残留其它账户（退出后切到下一个账户），重复直到回到服务器地址页
    @MainActor
    private func signOut(_ app: XCUIApplication) {
        let urlField = app.textFields["server.url"]
        for _ in 0..<4 where !urlField.exists {
            openTab(app, "我的")
            let signOutButton = app.buttons["me.signOut"]
            var attempts = 0
            while !(signOutButton.exists && signOutButton.isHittable) && attempts < 6 {
                app.swipeUp()
                attempts += 1
            }
            XCTAssertTrue(signOutButton.waitForExistence(timeout: 10), "找不到退出登录按钮")
            signOutButton.tap()
            let confirm = app.sheets.buttons["退出登录"]
            if confirm.waitForExistence(timeout: 5) {
                confirm.tap()
            } else {
                app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "退出登录", "me.signOut")).firstMatch.tap()
            }
            _ = urlField.waitForExistence(timeout: 10)
        }
        XCTAssertTrue(urlField.exists, "退出登录后未回到服务器地址页")
    }

    /// 切换 Tab（Tab 栏滚动收起时也能点到）
    @MainActor
    private func openTab(_ app: XCUIApplication, _ title: String) {
        for _ in 0..<4 {
            let candidates = [app.tabBars.buttons[title], app.buttons[title].firstMatch]
            if let tab = candidates.first(where: { $0.waitForExistence(timeout: 3) && $0.isHittable }) {
                tab.tap()
                return
            }
            if app.tabBars.buttons.count > 0 {
                app.tabBars.buttons.element(boundBy: 0).tap()
            }
        }
        XCTFail("找不到“\(title)” Tab")
    }

    @MainActor
    private func waitForLabel(_ element: XCUIElement, containing text: String, timeout: TimeInterval) -> Bool {
        let deadline = Date.now.addingTimeInterval(timeout)
        while Date.now < deadline {
            if element.exists && element.label.contains(text) { return true }
            Thread.sleep(forTimeInterval: 1)
        }
        return false
    }

    @MainActor
    private func waitForDisappearance(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date.now.addingTimeInterval(timeout)
        while Date.now < deadline {
            if !element.exists { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return false
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String) {
        Thread.sleep(forTimeInterval: 1)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
