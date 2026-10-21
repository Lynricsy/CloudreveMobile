import XCTest

/// 端到端：预览器。依赖 CI 预置素材（`scripts/ci/seed-viewer-media.py`）：
/// 「预览」目录下有 photo.jpg（EXIF）、clip.mp4 + clip.srt、song-a/b.mp3、manual.pdf、notes.md、bundle.zip。
/// 覆盖：打开方式选择、图片 + EXIF 面板、视频外挂字幕、音乐播放与底部“正在播放”、PDF、Markdown 预览/编辑/保存、压缩包目录树。
/// 结束时退出登录。
final class ViewerUITests: XCTestCase {
    private let serverURL = "http://127.0.0.1:5212"
    private let email = "user@example.com"
    private let password = "User@12345"

    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    func testOpenEachViewer() {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        ensureSignedIn(app)

        XCTAssertTrue(element(app, "files.location.my").waitForExistence(timeout: 20), "文件 Tab 根页未显示位置")
        element(app, "files.location.my").tap()
        XCTAssertTrue(element(app, "file.预览").waitForExistence(timeout: 30), "我的文件中没有「预览」目录")
        element(app, "file.预览").tap()
        XCTAssertTrue(element(app, "file.photo.jpg").waitForExistence(timeout: 30), "「预览」目录未列出素材")

        // 图片：多个候选 → 打开方式（记住选择）
        open(app, "photo.jpg")
        XCTAssertTrue(element(app, "viewer.option.image").waitForExistence(timeout: 20), "图片没有出现打开方式选择")
        capture(app, "viewer-01-chooser")
        let remember = app.switches["viewer.remember"].firstMatch
        if remember.exists { remember.tap() }
        element(app, "viewer.option.image").tap()
        XCTAssertTrue(element(app, "image.view").waitForExistence(timeout: 30), "图片未显示")
        capture(app, "viewer-02-image")
        element(app, "image.info").tap()
        XCTAssertTrue(app.buttons["image.infoDone"].waitForExistence(timeout: 10), "图片信息面板未出现")
        _ = app.staticTexts["Model"].waitForExistence(timeout: 5)
        capture(app, "viewer-03-image-exif")
        app.buttons["image.infoDone"].tap()
        closeViewer(app)

        // 视频：唯一候选直接打开，同名字幕自动选中
        open(app, "clip.mp4")
        XCTAssertTrue(element(app, "video.player").waitForExistence(timeout: 30), "视频播放器未出现")
        XCTAssertTrue(element(app, "video.subtitle").waitForExistence(timeout: 30), "外挂字幕未显示")
        capture(app, "viewer-04-video-subtitle")
        closeViewer(app)

        // 音乐：播放列表 = 同目录两首；关闭后底部附件栏显示正在播放
        open(app, "song-a.mp3")
        XCTAssertTrue(element(app, "music.title").waitForExistence(timeout: 20), "音乐播放页未出现")
        XCTAssertTrue(element(app, "music.track.song-b.mp3").waitForExistence(timeout: 20), "播放列表中没有同目录的第二首")
        Thread.sleep(forTimeInterval: 3)
        capture(app, "viewer-05-music")
        closeViewer(app)
        let nowPlaying = element(app, "nowPlaying.open")
        if nowPlaying.waitForExistence(timeout: 10) {
            capture(app, "viewer-06-now-playing")
            let stop = element(app, "nowPlaying.stop")
            if stop.exists { stop.tap() }
        }

        // PDF
        open(app, "manual.pdf")
        XCTAssertTrue(element(app, "viewer.option.pdf").waitForExistence(timeout: 20), "PDF 没有出现打开方式选择")
        element(app, "viewer.option.pdf").tap()
        XCTAssertTrue(element(app, "pdf.view").waitForExistence(timeout: 30), "PDF 未显示")
        Thread.sleep(forTimeInterval: 2)
        capture(app, "viewer-07-pdf")
        closeViewer(app)

        // Markdown：预览 → 编辑 → 保存
        open(app, "notes.md")
        XCTAssertTrue(element(app, "viewer.option.markdown").waitForExistence(timeout: 20), "Markdown 没有出现打开方式选择")
        element(app, "viewer.option.markdown").tap()
        XCTAssertTrue(element(app, "text.preview").waitForExistence(timeout: 30), "Markdown 预览未出现")
        capture(app, "viewer-08-markdown-preview")
        app.buttons["编辑"].firstMatch.tap()
        let editor = element(app, "text.editor")
        XCTAssertTrue(editor.waitForExistence(timeout: 10), "编辑区未出现")
        // 点在文末下方的空白处：光标落在末尾
        editor.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85)).tap()
        app.typeText("\n来自 iOS UI 测试的修改")
        capture(app, "viewer-09-markdown-edit")
        let save = app.buttons["text.save"].firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 5) && save.isEnabled, "保存按钮不可用")
        save.tap()
        XCTAssertTrue(element(app, "text.saved").waitForExistence(timeout: 20), "未提示保存成功")
        // “已保存”提示几秒后消失：出现后立即截图
        capture(app, "viewer-10-markdown-saved", delay: 0)
        closeViewer(app)

        // 压缩包：目录树
        open(app, "bundle.zip")
        XCTAssertTrue(element(app, "archive.entry.docs").waitForExistence(timeout: 30), "压缩包目录未列出")
        capture(app, "viewer-11-archive-root")
        element(app, "archive.entry.docs").tap()
        XCTAssertTrue(element(app, "archive.entry.guide").waitForExistence(timeout: 10), "未进入压缩包子目录")
        capture(app, "viewer-12-archive-folder")
        closeViewer(app)

        signOut(app)
    }

    // MARK: - 步骤

    @MainActor
    private func open(_ app: XCUIApplication, _ name: String) {
        let row = element(app, "file." + name)
        XCTAssertTrue(row.waitForExistence(timeout: 15), "列表中没有 \(name)")
        row.tap()
    }

    @MainActor
    private func closeViewer(_ app: XCUIApplication) {
        let close = app.buttons["viewer.close"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 10), "找不到预览关闭按钮")
        close.tap()
        XCTAssertTrue(element(app, "file.photo.jpg").waitForExistence(timeout: 15), "关闭预览后未回到列表")
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
            urlField.replaceText(serverURL)
            app.buttons["server.continue"].tap()
            let emailField = app.textFields["login.email"]
            XCTAssertTrue(emailField.waitForExistence(timeout: 30), "登录页未出现")
            emailField.replaceText(email)
            app.buttons["login.continue"].tap()
            let passwordField = app.secureTextFields["login.password"]
            XCTAssertTrue(passwordField.waitForExistence(timeout: 30), "密码阶段未出现")
            passwordField.replaceText(password)
            app.buttons["login.submit"].tap()
        }
        XCTAssertTrue(filesTab.waitForExistence(timeout: 30), "未进入主界面")
    }

    /// 结束时退出登录（其它 UI 测试从服务器地址页开始）。
    /// 之前的测试可能留下多个已登录账户：退出一个后会切到下一个，重复直到回到服务器地址页
    @MainActor
    private func signOut(_ app: XCUIApplication) {
        let urlField = app.textFields["server.url"]
        for _ in 0..<4 where !urlField.exists {
            signOutCurrentAccount(app)
            _ = urlField.waitForExistence(timeout: 15)
        }
        XCTAssertTrue(urlField.exists, "退出登录后未回到服务器地址页")
    }

    @MainActor
    private func signOutCurrentAccount(_ app: XCUIApplication) {
        let meTab = [app.tabBars.buttons["我的"], app.buttons["我的"].firstMatch].first { $0.waitForExistence(timeout: 5) }
        XCTAssertNotNil(meTab, "找不到“我的” Tab")
        meTab?.tap()
        let signOutButton = app.buttons["me.signOut"]
        app.scrollUntilHittable(signOutButton)
        XCTAssertTrue(signOutButton.waitForExistence(timeout: 10), "找不到退出登录按钮")
        signOutButton.tap()
        let confirm = app.sheets.buttons["退出登录"]
        if confirm.waitForExistence(timeout: 5) {
            confirm.tap()
        } else {
            app.buttons.matching(NSPredicate(format: "label == %@ AND identifier != %@", "退出登录", "me.signOut")).firstMatch.tap()
        }
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
