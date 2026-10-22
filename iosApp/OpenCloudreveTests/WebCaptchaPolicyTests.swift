import Foundation
import Testing
@testable import OpenCloudreve

/// 验证码网页的信任边界：只信任站点源的验证码页面主 frame
struct WebCaptchaPolicyTests {
    private let policy = WebCaptchaPolicy(pageURL: URL(string: "https://Cloud.Example.com/captcha?theme=dark&lng=zh-CN")!)!

    @Test func acceptsMessagesOnlyFromSiteMainFrame() {
        // WKSecurityOrigin 对默认端口给 0
        #expect(policy.acceptsMessage(isMainFrame: true, scheme: "https", host: "cloud.example.com", port: 0))
        #expect(policy.acceptsMessage(isMainFrame: true, scheme: "https", host: "cloud.example.com", port: 443))
        // 验证码供应商的 iframe、或站点源的子 frame
        #expect(!policy.acceptsMessage(isMainFrame: false, scheme: "https", host: "challenges.cloudflare.com", port: 0))
        #expect(!policy.acceptsMessage(isMainFrame: false, scheme: "https", host: "cloud.example.com", port: 0))
        // 被导航到其他源的主 frame
        #expect(!policy.acceptsMessage(isMainFrame: true, scheme: "https", host: "evil.example", port: 0))
        #expect(!policy.acceptsMessage(isMainFrame: true, scheme: "http", host: "cloud.example.com", port: 0))
        #expect(!policy.acceptsMessage(isMainFrame: true, scheme: "https", host: "cloud.example.com", port: 8443))
    }

    @Test func mainFrameStaysOnCaptchaPage() {
        #expect(policy.allowsMainFrameNavigation(to: URL(string: "https://cloud.example.com/captcha?theme=light")!))
        #expect(policy.allowsMainFrameNavigation(to: URL(string: "https://cloud.example.com:443/captcha")!))
        #expect(!policy.allowsMainFrameNavigation(to: URL(string: "https://cloud.example.com/home")!))
        #expect(!policy.allowsMainFrameNavigation(to: URL(string: "https://evil.example/captcha")!))
        #expect(!policy.allowsMainFrameNavigation(to: URL(string: "javascript:alert(1)")!))
    }

    @Test func siteUnderSubpathWithCustomPort() throws {
        let local = try #require(WebCaptchaPolicy(pageURL: URL(string: "http://192.168.1.5:5212/cloud/captcha?lng=en-US")!))
        #expect(local.allowsMainFrameNavigation(to: URL(string: "http://192.168.1.5:5212/cloud/captcha")!))
        #expect(!local.allowsMainFrameNavigation(to: URL(string: "http://192.168.1.5/cloud/captcha")!))
        #expect(local.acceptsMessage(isMainFrame: true, scheme: "http", host: "192.168.1.5", port: 5212))
        #expect(!local.acceptsMessage(isMainFrame: true, scheme: "http", host: "192.168.1.5", port: 0))
    }

    @Test func nonWebPageIsNeverTrusted() {
        #expect(WebCaptchaPolicy(pageURL: URL(string: "file:///tmp/captcha.html")!) == nil)
    }
}
