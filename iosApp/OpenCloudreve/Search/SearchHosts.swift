import OpenCloudreveCore

typealias SearchHost = ViewModelHost<SearchState, any SearchIntent, KotlinNothing>

extension ViewModelHost where S == SearchState, I == any SearchIntent {
    /// 搜索框文字
    var query: String {
        get { state.query }
        set { send(SearchIntentSetQuery(query: newValue)) }
    }

    /// 全文搜索开关（站点未启用时写入无效）
    var fullText: Bool {
        get { state.fullText }
        set { send(SearchIntentSetFullText(enabled: newValue)) }
    }
}
