import SwiftUI
import OpenCloudreveCore

/// 有效期：保持原到期时间（编辑时）/ 永久 / 预设（5 分钟 … 30 天）/ 自定义天数或小时。
/// 选项与自定义输入保存在本地，变化时转成 `SetExpire`。
struct ShareExpireSection: View {
    let host: ShareEditHost
    @State private var selection: ShareExpireChoice
    @State private var unit: ShareExpireUnit
    @State private var amount: String

    init(host: ShareEditHost) {
        self.host = host
        // 表单在加载完成后才出现，这里取一次当前值作为初始选项
        let state = host.state
        let seconds = state.expireSeconds?.int64Value
        let initial: ShareExpireChoice
        var unit = ShareExpireUnit.days
        var amount = ""
        if state.originalExpires != nil {
            initial = .keep
        } else if let seconds {
            if Self.presets.contains(seconds) {
                initial = .preset(seconds)
            } else {
                initial = .custom
                if let split = ShareExpireUnit.split(seconds) {
                    unit = split.unit
                    amount = String(split.amount)
                } else if seconds > 0 {
                    unit = .hours
                    amount = String((seconds + 3_599) / 3_600)
                }
            }
        } else {
            initial = .never
        }
        _selection = State(initialValue: initial)
        _unit = State(initialValue: unit)
        _amount = State(initialValue: amount)
    }

    var body: some View {
        let state = host.state
        Section {
            Picker("有效期", selection: $selection) {
                if state.originalExpires != nil {
                    Text("保持当前到期时间").tag(ShareExpireChoice.keep)
                }
                Text("永久").tag(ShareExpireChoice.never)
                ForEach(Self.presets, id: \.self) { seconds in
                    Text(verbatim: ShareFormat.duration(seconds: seconds)).tag(ShareExpireChoice.preset(seconds))
                }
                Text("自定义").tag(ShareExpireChoice.custom)
            }
            .accessibilityIdentifier("shareEdit.expire")
            if selection == .custom {
                HStack {
                    TextField("时长", text: $amount, prompt: Text("输入时长"))
                        .keyboardType(.numberPad)
                    Picker("单位", selection: $unit) {
                        Text("天").tag(ShareExpireUnit.days)
                        Text("小时").tag(ShareExpireUnit.hours)
                    }
                    .pickerStyle(.segmented)
                    .fixedSize()
                }
            }
        } footer: {
            if let original = FileFormat.dateTime(state.originalExpires) {
                Text("当前到期时间：\(original)（选择新的有效期以修改）", comment: "编辑分享时的原到期时间，参数为日期时间")
            } else if !state.expireValid {
                Text("请输入有效的时长")
                    .foregroundStyle(.red)
            } else if selection != .never {
                Text("到期后分享链接自动失效。")
            }
        }
        .onChange(of: selection) { old, new in
            if new == .custom, amount.isEmpty, case let .preset(seconds) = old, let split = ShareExpireUnit.split(seconds) {
                unit = split.unit
                amount = String(split.amount)
            }
            apply(new)
        }
        .onChange(of: amount) { _, text in
            let digits = String(text.filter { ("0"..."9").contains($0) }.prefix(5))
            if digits != text {
                amount = digits
                return
            }
            if selection == .custom { applyCustom() }
        }
        .onChange(of: unit) { _, _ in
            if selection == .custom { applyCustom() }
        }
    }

    private func apply(_ choice: ShareExpireChoice) {
        switch choice {
        case .keep:
            break
        case .never:
            host.send(ShareEditIntentSetExpire(seconds: nil))
        case let .preset(seconds):
            host.send(ShareEditIntentSetExpire(seconds: KotlinLong(longLong: seconds)))
        case .custom:
            applyCustom()
        }
    }

    /// 自定义时长；未填或为 0 时记为 0（无效，填入前不能提交）
    private func applyCustom() {
        let count = Int64(amount) ?? 0
        host.send(ShareEditIntentSetExpire(seconds: KotlinLong(longLong: max(count, 0) * unit.seconds)))
    }

    private static var presets: [Int64] {
        ShareEditState.companion.EXPIRE_PRESETS.map(\.int64Value)
    }
}
