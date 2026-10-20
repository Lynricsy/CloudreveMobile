import SwiftUI
import OpenCloudreveCore

/// 管理端通用列表：分页加载、下拉刷新、搜索、排序、多选批量删除、在网页后台打开。
/// 各实体页面提供行视图、详情目标与额外的过滤菜单内容。
struct AdminEntityList<T: AnyObject, Row: View, Destination: View, Header: View, Filters: View>: View {
    let host: AdminListHost<T>
    let supportsDelete: Bool
    let searchFields: [AdminSearchField]
    let idOf: (T) -> Int32
    @ViewBuilder let header: () -> Header
    @ViewBuilder let filters: () -> Filters
    @ViewBuilder let row: (T) -> Row
    @ViewBuilder let destination: (T) -> Destination

    @State private var selection = Set<Int32>()
    @State private var editMode: EditMode = .inactive
    @State private var confirmingDelete = false
    @State private var query = ""
    @State private var searchKey = ""

    var body: some View {
        List(selection: $selection) {
            header()

            if let error = host.state.error {
                Section {
                    ErrorRow(message: ErrorText.message(error))
                    Button("重试") { host.send(AdminListIntentRefresh.shared) }
                }
            }

            Section {
                if host.state.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else if host.state.isEmpty {
                    Text("没有符合条件的项目")
                        .foregroundStyle(.secondary)
                }
                ForEach(rows) { item in
                    NavigationLink {
                        destination(item.value)
                    } label: {
                        row(item.value)
                    }
                }
                if host.state.hasMore {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .onAppear { host.send(AdminListIntentLoadMore.shared) }
                        .id(host.state.loadedPages)
                }
            } footer: {
                if !host.state.loading && host.state.totalItems > 0 {
                    Text("共 \(Int(host.state.totalItems)) 项")
                }
            }
        }
        .environment(\.editMode, $editMode)
        .refreshable {
            host.send(AdminListIntentRefresh.shared)
            while host.state.refreshing {
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
        .searchable(text: $query, prompt: searchPrompt)
        .searchScopes($searchKey, activation: .onSearchPresentation) {
            if searchFields.count > 1 {
                ForEach(searchFields, id: \.key) { field in
                    Text(field.title).tag(field.key)
                }
            }
        }
        .onSubmit(of: .search) { applySearch() }
        .onChange(of: query) { _, newValue in
            if newValue.isEmpty { applySearch() }
        }
        .onChange(of: searchKey) { _, _ in
            if !query.isEmpty { applySearch() }
        }
        .onAppear {
            if searchKey.isEmpty { searchKey = searchFields.first?.key ?? "" }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                AdminWebButton(availability: host.state.handoff, opening: host.state.openingWeb) {
                    host.send(AdminListIntentOpenInWeb.shared)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    filters()
                    if !host.state.orderByOptions.isEmpty {
                        AdminOrderPicker(host: host)
                    }
                    if !host.state.conditions.isEmpty {
                        Button("清除全部过滤条件", systemImage: "xmark.circle") {
                            host.send(AdminListIntentClearConditions.shared)
                        }
                    }
                } label: {
                    Label("过滤与排序", systemImage: host.state.conditions.isEmpty ? "line.3.horizontal.decrease" : "line.3.horizontal.decrease.circle.fill")
                }
                .accessibilityIdentifier("admin.filter")
            }
            if supportsDelete {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(editMode.isEditing ? "完成" : "选择", systemImage: editMode.isEditing ? "checkmark" : "checkmark.circle") {
                        withAnimation {
                            editMode = editMode.isEditing ? .inactive : .active
                        }
                        selection.removeAll()
                    }
                    .accessibilityIdentifier("admin.select")
                }
            }
            if editMode.isEditing {
                ToolbarItem(placement: .bottomBar) {
                    Button(allSelected ? "取消全选" : "全选") {
                        if allSelected {
                            selection.removeAll()
                        } else {
                            selection = Set(rows.map(\.id))
                        }
                    }
                }
                ToolbarSpacer(.flexible, placement: .bottomBar)
                ToolbarItem(placement: .bottomBar) {
                    Button("删除", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                        .disabled(selection.isEmpty || host.state.deleting)
                        .accessibilityIdentifier("admin.deleteSelected")
                }
            }
        }
        .toolbarVisibility(editMode.isEditing ? .hidden : .automatic, for: .tabBar)
        .confirmationDialog("删除所选的 \(selection.count) 项？", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                host.send(AdminListIntentDelete(ids: selection.map { KotlinInt(int: $0) }))
            }
        } message: {
            Text("此操作不可撤销。")
        }
        .modifier(AdminListEffectHandling(host: host, onDeleted: {
            selection.removeAll()
            editMode = .inactive
        }))
    }

    private var rows: [AdminRowItem<T>] {
        host.state.items.map { AdminRowItem(id: idOf($0), value: $0) }
    }

    private var allSelected: Bool {
        !rows.isEmpty && selection.count == rows.count
    }

    private var searchPrompt: Text {
        if let field = searchFields.first(where: { $0.key == searchKey }) ?? searchFields.first {
            return Text(field.title)
        }
        return Text("搜索")
    }

    /// 把搜索词写入当前搜索范围对应的条件，并清除其他搜索条件
    private func applySearch() {
        let text = query.trimmingCharacters(in: .whitespaces)
        for field in searchFields where field.key != searchKey && host.state.conditions[field.key] != nil {
            host.send(AdminListIntentSetCondition(key: field.key, value: nil))
        }
        guard !searchKey.isEmpty, host.state.conditions[searchKey] != (text.isEmpty ? nil : text) else { return }
        host.send(AdminListIntentSetCondition(key: searchKey, value: text.isEmpty ? nil : text))
    }
}
