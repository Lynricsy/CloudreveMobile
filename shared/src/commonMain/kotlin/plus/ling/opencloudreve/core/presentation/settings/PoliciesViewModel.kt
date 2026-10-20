package plus.ling.opencloudreve.core.presentation.settings

import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.settings.UserSettingsRepository

/** 负载均衡子策略及其权重占比 */
data class PolicyWeight(
    val policy: StoragePolicy,
    val weight: Int,
    /** 权重占比 0..1（全部权重为 0 时平均分配） */
    val share: Double,
)

data class PolicyItem(
    val policy: StoragePolicy,
    /** 列表首位为用户组默认策略 */
    val isDefault: Boolean,
    /** 负载均衡策略的子策略；非负载均衡为空 */
    val children: List<PolicyWeight>,
) {
    val isLoadBalance: Boolean get() = policy.type == StoragePolicy.TYPE_LOAD_BALANCE
}

data class PoliciesState(
    val accountId: String,
    val items: List<PolicyItem> = emptyList(),
    val loading: Boolean = false,
    val error: UiError? = null,
)

sealed interface PoliciesIntent {
    data object Refresh : PoliciesIntent
}

/** 可用存储策略（`GET /user/setting/policies`，只读） */
class PoliciesViewModel(
    val accountId: String,
    private val userSettings: UserSettingsRepository,
) : CoreViewModel<PoliciesState, PoliciesIntent, Nothing>(PoliciesState(accountId)) {

    init {
        load()
    }

    override fun on(intent: PoliciesIntent) {
        when (intent) {
            PoliciesIntent.Refresh -> load()
        }
    }

    private fun load() {
        updateState { it.copy(loading = true, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, error = e) } }) {
            val policies = userSettings.policies()
            updateState { it.copy(loading = false, items = items(policies)) }
        }
    }

    companion object {
        fun items(policies: List<StoragePolicy>): List<PolicyItem> = policies.mapIndexed { index, p ->
            val children = p.children.orEmpty()
            val total = children.sumOf { (it.weight ?: 0).coerceAtLeast(0) }
            PolicyItem(
                policy = p,
                isDefault = index == 0,
                children = children.map { c ->
                    val w = (c.weight ?: 0).coerceAtLeast(0)
                    PolicyWeight(c, w, if (total > 0) w.toDouble() / total else 1.0 / children.size)
                },
            )
        }
    }
}
