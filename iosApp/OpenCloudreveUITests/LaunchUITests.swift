import XCTest

final class LaunchUITests: XCTestCase {
    @MainActor
    func testLaunchScreenshot() {
        let app = XCUIApplication()
        app.launch()
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "launch"
        shot.lifetime = .keepAlways
        add(shot)
    }
}
