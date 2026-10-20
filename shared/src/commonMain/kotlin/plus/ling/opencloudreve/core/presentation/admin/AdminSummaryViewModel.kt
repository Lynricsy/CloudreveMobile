package plus.ling.opencloudreve.core.presentation.admin

import kotlinx.coroutines.launch
import plus.ling.opencloudreve.core.admin.AdminRepository
import plus.ling.opencloudreve.core.model.AdminSummary
import plus.ling.opencloudreve.core.model.MetricsSummary
import plus.ling.opencloudreve.core.presentation.CoreViewModel
import plus.ling.opencloudreve.core.presentation.UiError
import plus.ling.opencloudreve.core.presentation.handoff.AdminWebSection
import plus.ling.opencloudreve.core.presentation.handoff.HandoffAvailability
import plus.ling.opencloudreve.core.presentation.handoff.WebHandoff
import plus.ling.opencloudreve.core.presentation.handoff.attempt
import plus.ling.opencloudreve.core.presentation.handoff.availabilityOrNull
import kotlin.time.Instant

/** 某一天的新增数（图表一个点） */
data class AdminDailyMetric(
    /** 当天起点 */
    val date: Instant,
    val files: Int,
    val users: Int,
    val shares: Int,
)

data class AdminSummaryState(
    val summary: AdminSummary? = null,
    /** 近 12 天每日新增（按日期升序；无统计时为空） */
    val daily: List<AdminDailyMetric> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: UiError? = null,
    val handoff: HandoffAvailability = HandoffAvailability.Checking,
    val openingWeb: Boolean = false,
) {
    val metrics: MetricsSummary? get() = summary?.metricsSummary
    val fileTotal: Int get() = metrics?.fileTotal ?: 0
    val userTotal: Int get() = metrics?.userTotal ?: 0
    val shareTotal: Int get() = metrics?.shareTotal ?: 0
    val entitiesTotal: Int get() = metrics?.entitiesTotal ?: 0
    val generatedAt: Instant? get() = metrics?.generatedAt
    val version: String get() = summary?.version?.version.orEmpty()
    val commit: String get() = summary?.version?.commit.orEmpty()
    val siteUrls: List<String> get() = summary?.siteUrls.orEmpty()
}

sealed interface AdminSummaryIntent {
    /** 重新拉取（服务端缓存 24 小时；`generate=true` 在缓存缺失时现场统计） */
    data object Refresh : AdminSummaryIntent
    data object OpenInWeb : AdminSummaryIntent
    data object DismissError : AdminSummaryIntent
}

sealed interface AdminSummaryEffect {
    data class OpenUrl(val url: String) : AdminSummaryEffect
    data class Failed(val error: UiError) : AdminSummaryEffect
}

/** 管理概览（`GET /admin/summary?generate=true`） */
class AdminSummaryViewModel(
    val accountId: String,
    private val admin: AdminRepository,
    private val handoff: WebHandoff,
) : CoreViewModel<AdminSummaryState, AdminSummaryIntent, AdminSummaryEffect>(AdminSummaryState()) {

    init {
        load(initial = true)
        scope.launch { handoff.availabilityOrNull(accountId)?.let { a -> updateState { it.copy(handoff = a) } } }
    }

    override fun on(intent: AdminSummaryIntent) {
        when (intent) {
            AdminSummaryIntent.Refresh -> load(initial = false)
            AdminSummaryIntent.OpenInWeb -> openInWeb()
            AdminSummaryIntent.DismissError -> updateState { it.copy(error = null) }
        }
    }

    private fun load(initial: Boolean) {
        updateState { it.copy(loading = initial, refreshing = !initial, error = null) }
        launchCatching(onError = { e -> updateState { it.copy(loading = false, refreshing = false, error = e) } }) {
            val s = admin.summary(generate = true)
            updateState { it.copy(summary = s, daily = daily(s.metricsSummary), loading = false, refreshing = false) }
        }
    }

    private fun openInWeb() {
        if (currentState.openingWeb) return
        updateState { it.copy(openingWeb = true) }
        scope.launch {
            val r = handoff.attempt(accountId, WebHandoff.adminRedirect(AdminWebSection.Home))
            updateState { it.copy(openingWeb = false, handoff = r.availability ?: it.handoff) }
            r.url?.let { sendEffect(AdminSummaryEffect.OpenUrl(it)) }
            r.error?.let { sendEffect(AdminSummaryEffect.Failed(it)) }
        }
    }

    companion object {
        /** 把并列数组合成每日数据点（长度取最短者） */
        fun daily(m: MetricsSummary?): List<AdminDailyMetric> {
            if (m == null) return emptyList()
            val n = minOf(m.dates.size, m.files.size, m.users.size, m.shares.size)
            return (0 until n).map { i -> AdminDailyMetric(m.dates[i], m.files[i], m.users[i], m.shares[i]) }.sortedBy { it.date }
        }
    }
}
