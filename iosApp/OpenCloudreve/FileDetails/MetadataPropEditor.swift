import SwiftUI
import OpenCloudreveCore

/// 单个自定义属性的编辑器（按定义类型）；修改立即提交（本地校验失败时显示 `propErrors`）
struct MetadataPropEditor: View {
    let entry: CustomPropEntry
    let host: MetadataEditHost

    var body: some View {
        let state = host.state
        let editable = state.canEditProps && entry.definition != nil && !state.saving
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(verbatim: CustomPropText.name(entry.name))
                    .font(.subheadline.weight(.semibold))
                Spacer(minLength: 0)
                if state.canEditProps {
                    Button("移除", role: .destructive) {
                        host.send(MetadataEditIntentRemoveProp(id: entry.id))
                    }
                    .buttonStyle(.borderless)
                    .font(.footnote)
                    .disabled(state.saving)
                }
            }
            editor(state: state, editable: editable)
            if let error = state.propErrors[entry.id] {
                Text(CustomPropText.error(error, definition: entry.definition))
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private func editor(state: MetadataEditState, editable: Bool) -> some View {
        let definition = entry.definition
        switch entry.type {
        case CustomPropDefinition.companion.TYPE_BOOLEAN:
            Toggle(isOn: booleanBinding) {
                Text(verbatim: CustomPropText.name(entry.name))
            }
            .labelsHidden()
            .disabled(!editable)
        case CustomPropDefinition.companion.TYPE_RATING:
            ratingStars(max: Int(definition?.max ?? 0), editable: editable)
        case CustomPropDefinition.companion.TYPE_SELECT:
            Picker(selection: selectBinding) {
                Text("未选择").tag("")
                ForEach(definition?.options ?? [], id: \.self) { option in
                    Text(verbatim: option).tag(option)
                }
            } label: {
                Text("选项")
            }
            .pickerStyle(.menu)
            .disabled(!editable)
        case CustomPropDefinition.companion.TYPE_MULTI_SELECT:
            multiSelect(options: definition?.options ?? [], editable: editable)
        case CustomPropDefinition.companion.TYPE_USER:
            PropUserPicker(
                entry: entry,
                current: state.resolvedUsers[entry.value],
                results: state.userSearchResults,
                searching: state.searchingUsers,
                editable: editable,
                search: { host.send(MetadataEditIntentSearchUsers(keyword: $0)) },
                pick: { set($0) }
            )
        default:
            PropTextField(entry: entry, editable: editable) { set($0) }
        }
    }

    /// 星级：再次点按当前星级清除评分
    private func ratingStars(max: Int, editable: Bool) -> some View {
        let count = max > 0 ? max : 5
        let rating = Int(entry.ratingValue)
        return HStack(spacing: 4) {
            ForEach(1...count, id: \.self) { star in
                Button {
                    let next: KotlinInt? = star == rating ? nil : KotlinInt(int: Int32(star))
                    set(CustomPropCodec.shared.encodeRating(value: next))
                } label: {
                    Label {
                        Text("\(star) 星", comment: "评分的无障碍描述")
                    } icon: {
                        Image(systemName: star <= rating ? "star.fill" : "star")
                            .foregroundStyle(star <= rating ? Color.yellow : Color.secondary)
                    }
                    .labelStyle(.iconOnly)
                    .font(.title3)
                }
                .buttonStyle(.borderless)
                .accessibilityAddTraits(star == rating ? .isSelected : [])
            }
        }
        .disabled(!editable)
    }

    /// 多选：逐项勾选，提交 JSON 数组
    private func multiSelect(options: [String], editable: Bool) -> some View {
        let values = entry.multiSelectValues
        return VStack(alignment: .leading, spacing: 6) {
            ForEach(options, id: \.self) { option in
                Button {
                    let next = values.contains(option) ? values.filter { $0 != option } : values + [option]
                    set(CustomPropCodec.shared.encodeMultiSelect(values: next))
                } label: {
                    HStack {
                        Text(verbatim: option)
                            .foregroundStyle(Color.primary)
                        Spacer(minLength: 0)
                        if values.contains(option) {
                            Image(systemName: "checkmark")
                                .foregroundStyle(.tint)
                        }
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.borderless)
                .accessibilityAddTraits(values.contains(option) ? .isSelected : [])
            }
        }
        .disabled(!editable)
    }

    private var booleanBinding: Binding<Bool> {
        Binding(
            get: { entry.booleanValue },
            set: { set(CustomPropCodec.shared.encodeBoolean(value: $0)) }
        )
    }

    private var selectBinding: Binding<String> {
        Binding(get: { entry.value }, set: { set($0) })
    }

    private func set(_ value: String) {
        host.send(MetadataEditIntentSetProp(id: entry.id, value: value))
    }
}
