import XCTest

/// 端到端：分享、后台任务、版本、标签。依赖 CI 预置数据（`scripts/ci/seed-share-tasks.py`）：
/// 「归档/分享任务」目录下有 report.txt（2 个版本）、bundle-src/（压缩素材）、share-me.md。
/// 覆盖：新建分享表单与结果（链接 + 二维码）、分享列表、版本列表、标签编辑、压缩任务完成后的后台任务列表与详情。
/// 结束时退出登录。
final class ShareTasksUITests: XCTestCase {
    private let serverURL = "http://127.0.0.1:5212"
    private let email = "user@example.com"
    private let password = "User@12345"

    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        MainActor.assumeIsolated {
            let app = XCUIApplication()
            guard app.state == .runningForeground, !app.textFields["server.url"].exists else { return }
            for title in ["完成", "取消", "关闭"] where app.buttons[title].firstMatch.exists {
                app.buttons[title].firstMatch.tap()
            }
            Self.signOut(app)
        }
        super.tearDown()
    }

    @MainActor
    func testSharesTasksVersionsAndTags() {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        ensureSignedIn(app)

        XCTAssertTrue(element(app, "files.location.my").waitForExistence(timeout: 20), "文件 Tab 根页未显示位置")
        element(app, "files.location.my").tap()
        XCTAssertTrue(element(app, "file.归档").waitForExistence(timeout: 30), "我的文件中没有「归档」目录")
        element(app, "file.归档").tap()
        XCTAssertTrue(element(app, "file.分享任务").waitForExistence(timeout: 30), "「归档」中没有「分享任务」目录")
        element(app, "file.分享任务").tap()
        XCTAssertTrue(element(app, "file.share-me.md").waitForExistence(timeout: 30), "「分享任务」目录未列出预置文件")

        // 新建分享：私密 + 自动密码 → 结果页（链接 + 二维码）
        openContextMenu(app, on: element(app, "file.share-me.md"), item: "分享").tap()
        let submit = element(app, "shareEdit.submit")
        XCTAssertTrue(submit.waitForExistence(timeout: 20), "新建分享表单未出现")
        let privateSwitch = app.switches["shareEdit.private"].firstMatch
        if privateSwitch.waitForExistence(timeout: 5), (privateSwitch.value as? String) != "1" {
            // Form 中的 Toggle 点标签不会切换：点右侧开关
            privateSwitch.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
        }
        capture(app, "p7-01-share-form")
        submit.tap()
        XCTAssertTrue(element(app, "shareResult.qr").waitForExistence(timeout: 30), "分享结果未显示二维码")
        XCTAssertTrue(element(app, "shareResult.link").exists, "分享结果未显示链接")
        capture(app, "p7-02-share-result")
        element(app, "shareResult.done").tap()

        // 版本列表：预置 2 个版本
        openContextMenu(app, on: element(app, "file.report.txt"), item: "版本").tap()
        let versionRows = app.descendants(matching: .any).matching(identifier: "version.row")
        let deadline = Date.now.addingTimeInterval(30)
        while Date.now < deadline && versionRows.count < 2 {
            Thread.sleep(forTimeInterval: 0.5)
        }
        XCTAssertGreaterThanOrEqual(versionRows.count, 2, "版本列表少于 2 个版本")
        XCTAssertTrue(element(app, "version.current").exists, "未标记当前版本")
        capture(app, "p7-03-versions")
        closeSheet(app, "版本")

        // 标签编辑：添加带颜色的标签并保存
        openContextMenu(app, on: element(app, "file.report.txt"), item: "标签与元数据").tap()
        let tagName = element(app, "metadata.tagName")
        XCTAssertTrue(tagName.waitForExistence(timeout: 20), "标签输入框未出现")
        let color = element(app, "metadata.tagColor.1")
        if color.waitForExistence(timeout: 3) { color.tap() }
        tagName.tap()
        // 输入框的回车键（“完成”）即添加标签并收起键盘
        tagName.typeText("重要\n")
        XCTAssertTrue(element(app, "metadata.tag.重要").waitForExistence(timeout: 10), "新标签未出现在列表中")
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5), "添加标签后键盘未收起")
        capture(app, "p7-04-tag-edit")
        let saveTags = element(app, "metadata.saveTags")
        XCTAssertTrue(saveTags.waitForExistence(timeout: 5), "没有“保存标签”按钮")
        saveTags.tap()
        let saved = NSPredicate(format: "exists == false")
        expectation(for: saved, evaluatedWith: saveTags)
        waitForExpectations(timeout: 20)
        closeSheet(app, "标签与元数据")

        // 压缩任务
        openContextMenu(app, on: element(app, "file.bundle-src"), item: "压缩").tap()
        let compress = element(app, "compress.submit")
        XCTAssertTrue(compress.waitForExistence(timeout: 20), "压缩表单未出现")
        capture(app, "p7-05-compress-form")
        compress.tap()
        let createdOk = element(app, "workflow.created.ok")
        XCTAssertTrue(createdOk.waitForExistence(timeout: 30), "未提示已创建压缩任务")
        createdOk.tap()

        // 分享列表
        app.tabBars.buttons["分享"].tap()
        XCTAssertTrue(element(app, "share.share-me.md").waitForExistence(timeout: 30), "分享列表中没有新建的分享")
        capture(app, "p7-06-share-list")

        // 后台任务：等压缩任务完成
        app.tabBars.buttons["任务"].tap()
        let backgroundSegment = app.segmentedControls.buttons["后台任务"].firstMatch
        XCTAssertTrue(backgroundSegment.waitForExistence(timeout: 10), "任务 Tab 没有“后台任务”分段")
        backgroundSegment.tap()
        let task = element(app, "workflow.task")
        XCTAssertTrue(task.waitForExistence(timeout: 30), "后台任务列表为空")
        // 行是 Button，子 Text 合并进 label
        let done = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label CONTAINS %@", "workflow.task", "已完成")).firstMatch
        let taskDeadline = Date.now.addingTimeInterval(240)
        while Date.now < taskDeadline && !done.exists {
            Thread.sleep(forTimeInterval: 5)
            task.swipeDown()
        }
        XCTAssertTrue(done.exists, "压缩任务未在 4 分钟内完成")
        capture(app, "p7-07-workflow-list")
        done.tap()
        XCTAssertTrue(element(app, "workflow.detail.status").waitForExistence(timeout: 20), "任务详情未出现")
        capture(app, "p7-08-workflow-detail")
        app.navigationBars.buttons.firstMatch.tap()

        Self.signOut(app)
    }

    // MARK: - 步骤

    /// 长按打开上下文菜单并返回其中的菜单项。长按偶尔被识别为点按（打开详细信息或预览器），此时关闭后重试
    @MainActor
    private func openContextMenu(_ app: XCUIApplication, on target: XCUIElement, item title: String) -> XCUIElement {
        XCTAssertTrue(target.waitForExistence(timeout: 15), "列表中没有目标项")
        let item = app.buttons[title].firstMatch
        for _ in 0..<3 {
            target.press(forDuration: 2.0)
            if item.waitForExistence(timeout: 5) { return item }
            for dismiss in ["完成", "关闭"] where app.buttons[dismiss].firstMatch.exists {
                app.buttons[dismiss].firstMatch.tap()
            }
            let close = app.buttons["viewer.close"].firstMatch
            if close.exists { close.tap() }
            Thread.sleep(forTimeInterval: 1)
        }
        XCTFail("上下文菜单未出现")
        return item
    }

    /// 点导航栏上的“完成”关闭 sheet，并等它消失。
    /// 不能用 `app.buttons["完成"]`：键盘回车键（`.submitLabel(.done)`）也叫“完成”
    @MainActor
    private func closeSheet(_ app: XCUIApplication, _ title: String) {
        let bar = app.navigationBars[title]
        XCTAssertTrue(bar.waitForExistence(timeout: 10), "没有“\(title)”页面")
        bar.buttons["完成"].tap()
        XCTAssertTrue(bar.waitForNonExistence(timeout: 10), "“\(title)”未关闭")
    }

    /// 未登录时走服务器地址 → 登录；已登录则直接进入主界面
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

    /// 退出登录直到回到服务器地址页（之前的测试可能留下多个账户）
    @MainActor
    private static func signOut(_ app: XCUIApplication) {
        let urlField = app.textFields["server.url"]
        for _ in 0..<4 where !urlField.exists {
            let meTab = [app.tabBars.buttons["我的"], app.buttons["我的"].firstMatch].first { $0.waitForExistence(timeout: 5) }
            meTab?.tap()
            let signOutButton = app.buttons["me.signOut"]
            var attempts = 0
            while !(signOutButton.exists && signOutButton.isHittable) && attempts < 6 {
                app.swipeUp()
                attempts += 1
            }
            guard signOutButton.waitForExistence(timeout: 10) else { break }
            signOutButton.tap()
            let confirm = app.sheets.buttons["退出登录"]
            if confirm.waitForExistence(timeout: 5) {
                confirm.tap()
            } else {
                app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "退出登录", "me.signOut")).firstMatch.tap()
            }
            _ = urlField.waitForExistence(timeout: 15)
        }
        XCTAssertTrue(urlField.exists, "退出登录后未回到服务器地址页")
    }

    @MainActor
    private func element(_ app: XCUIApplication, _ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)[identifier].firstMatch
    }

    /// 等动画结束后截全屏并永久保留为测试附件
    @MainActor
    private func capture(_ app: XCUIApplication, _ name: String, delay: TimeInterval = 1) {
        if delay > 0 { Thread.sleep(forTimeInterval: delay) }
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
