import Foundation
import OpenCloudreveCore

/// Kotlin 基本类型与 Swift 之间的小工具（SKIE 对 `suspend` 返回的装箱类型因版本而异，用重载同时兼容）
enum KotlinValues {
    static func bool(_ value: Bool) -> Bool { value }
    static func bool(_ value: KotlinBoolean) -> Bool { value.boolValue }

    /// `Data` → Kotlin `ByteArray`
    static func bytes(_ data: Data) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }

    /// Kotlin `ByteArray` → `Data`
    static func data(_ array: KotlinByteArray) -> Data {
        var data = Data(count: Int(array.size))
        for index in 0..<Int(array.size) {
            data[index] = UInt8(bitPattern: array.get(index: Int32(index)))
        }
        return data
    }

    /// Kotlin 异常（经 SKIE 转成的 `Error`）的可读信息
    static func message(_ error: Error) -> String {
        let text = (error as NSError).localizedDescription
        return text.isEmpty ? String(describing: error) : text
    }
}
