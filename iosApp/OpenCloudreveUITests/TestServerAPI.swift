import Foundation

/// UI 测试直接调用 CI 本地服务端的 API（模拟“其它客户端”）：使用独立的 `X-Cr-Client-Id`，
/// 因此这里的修改会作为目录事件推送给 App。只返回 Sendable 值。
enum TestServerAPI {
    struct Failure: Error, CustomStringConvertible {
        let description: String
    }

    /// 新建分享的结果：分享 id 与（私密分享）服务端生成的密码
    struct Share: Sendable {
        let id: String
        let password: String?
    }

    private static let base = "http://127.0.0.1:5212/api/v4"
    private static let clientId = UUID().uuidString

    /// `cloudreve://my/<段…>`，各段按 encodeURIComponent 规则编码
    static func myUri(_ segments: String...) -> String {
        // 只保留 ASCII 非保留字符（`CharacterSet.alphanumerics` 含中文，不能用）
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")
        let path = segments.map { $0.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0 }
        return "cloudreve://my/" + path.joined(separator: "/")
    }

    /// 密码登录，返回 access token
    static func login(email: String, password: String) async throws -> String {
        let data = try await call("POST", "/session/token", body: ["email": email, "password": password], token: nil)
        guard let object = data as? [String: Any],
              let token = object["token"] as? [String: Any],
              let access = token["access_token"] as? String else {
            throw Failure(description: "登录响应缺少 access_token")
        }
        return access
    }

    /// 新建文件或目录（已存在时服务端返回已有对象，不报错）
    static func create(token: String, uri: String, type: String) async throws {
        _ = try await call("POST", "/file/create", body: ["uri": uri, "type": type], token: token)
    }

    /// 彻底删除文件或目录（清理测试数据）
    static func delete(token: String, uris: [String]) async throws {
        _ = try await call("DELETE", "/file", body: ["uris": uris, "skip_soft_delete": true], token: token)
    }

    /// 新建分享；响应是 `<站点>/s/<id>[/<密码>]`
    static func createShare(token: String, uri: String, isPrivate: Bool) async throws -> Share {
        let data = try await call("PUT", "/share", body: ["uri": uri, "is_private": isPrivate], token: token)
        guard let link = data as? String, let range = link.range(of: "/s/", options: .backwards) else {
            throw Failure(description: "分享响应不是链接：\(String(describing: data))")
        }
        let parts = link[range.upperBound...].split(separator: "/").map(String.init)
        guard let id = parts.first else { throw Failure(description: "分享链接缺少 id：\(link)") }
        return Share(id: id, password: parts.count > 1 ? parts[1] : nil)
    }

    /// 发送请求并解出信封中的 `data`；`code != 0` 时抛错
    private static func call(_ method: String, _ path: String, body: [String: Any], token: String?) async throws -> Any? {
        guard let url = URL(string: base + path) else { throw Failure(description: "无效地址 \(path)") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(clientId, forHTTPHeaderField: "X-Cr-Client-Id")
        if let token {
            request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await URLSession.shared.data(for: request)
        guard let envelope = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure(description: "\(method) \(path) 响应不是 JSON 对象")
        }
        let code = envelope["code"] as? Int ?? -1
        guard code == 0 else {
            throw Failure(description: "\(method) \(path) 失败：code=\(code) msg=\(envelope["msg"] ?? "")")
        }
        return envelope["data"]
    }
}
