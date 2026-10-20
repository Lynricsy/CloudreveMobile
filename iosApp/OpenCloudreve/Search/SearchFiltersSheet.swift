import SwiftUI
import OpenCloudreveCore

/// 高级搜索条件：全文搜索、类型、分类、大小、修改时间、标签、忽略大小写。点“搜索”后应用并（可提交时）立即搜索。
struct SearchFiltersSheet: View {
    let search: SearchHost

    @Environment(\.dismiss) private var dismiss
    @State private var draft = SearchFilterDraft()
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            Form {
                if search.state.fullTextAvailable {
                    Section {
                        Toggle("全文搜索", isOn: $draft.fullText)
                    } footer: {
                        Text("在文件内容中搜索关键字；开启后其它条件不生效。")
                    }
                }
                Section {
                    Picker("类型", selection: $draft.type) {
                        Text("全部").tag(SearchFileType?.none)
                        Text("文件").tag(SearchFileType?.some(.file))
                        Text("文件夹").tag(SearchFileType?.some(.folder))
                    }
                    Picker("分类", selection: $draft.category) {
                        Text("全部").tag(String?.none)
                        Text("图片").tag(String?.some(SearchFilters.companion.CATEGORY_IMAGE))
                        Text("视频").tag(String?.some(SearchFilters.companion.CATEGORY_VIDEO))
                        Text("音频").tag(String?.some(SearchFilters.companion.CATEGORY_AUDIO))
                        Text("文档").tag(String?.some(SearchFilters.companion.CATEGORY_DOCUMENT))
                    }
                    Toggle("忽略大小写", isOn: $draft.caseFolding)
                }
                .disabled(draft.fullText)
                Section("大小（MB）") {
                    TextField("最小", text: $draft.minMegabytes)
                        .keyboardType(.decimalPad)
                    TextField("最大", text: $draft.maxMegabytes)
                        .keyboardType(.decimalPad)
                }
                .disabled(draft.fullText)
                Section {
                    Toggle("按修改时间筛选", isOn: $draft.filterUpdated)
                    if draft.filterUpdated {
                        DatePicker("从", selection: $draft.updatedFrom, displayedComponents: .date)
                        DatePicker("到", selection: $draft.updatedTo, displayedComponents: .date)
                    }
                }
                .disabled(draft.fullText)
                Section {
                    TextField("标签，用逗号分隔", text: $draft.tagsText)
                        .textInputAutocapitalization(.never)
                } header: {
                    Text("标签")
                }
                .disabled(draft.fullText)
                Section {
                    Button("重置条件", role: .destructive) {
                        draft = SearchFilterDraft()
                    }
                }
            }
            .navigationTitle("搜索条件")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("搜索") {
                        apply()
                        dismiss()
                    }
                }
            }
            .onAppear {
                if !loaded {
                    draft = SearchFilterDraft(search.state)
                    loaded = true
                }
            }
        }
    }

    private func apply() {
        search.send(SearchIntentSetFilters(filters: draft.filters))
        search.send(SearchIntentSetFullText(enabled: draft.fullText))
        if search.state.canSubmit {
            search.send(SearchIntentSubmit.shared)
        }
    }
}
