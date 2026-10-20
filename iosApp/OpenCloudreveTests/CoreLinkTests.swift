import XCTest
import OpenCloudreveCore

final class CoreLinkTests: XCTestCase {
    func testCoreFrameworkLinked() {
        XCTAssertEqual(CoreInfo.shared.NAME, "OpenCloudreve")
    }
}
