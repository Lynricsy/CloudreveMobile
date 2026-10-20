import Foundation

/// JSON 文本格式化（缩进、键排序）；无法解析时原样返回
enum AdminJSON {
    static func pretty(_ raw: String) -> String {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
              let output = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .fragmentsAllowed]),
              let text = String(data: output, encoding: .utf8) else { return raw }
        return text
    }

    /// 键 → Kotlin `JsonElement`（其字符串形式即 JSON）组成的对象
    static func object(_ fields: [String: Any]) -> String {
        let body = fields.keys.sorted().map { key in
            "\(String(reflecting: key)): \(String(describing: fields[key]!))"
        }
        return pretty("{" + body.joined(separator: ",") + "}")
    }
}
