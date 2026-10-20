package plus.ling.opencloudreve.core.di

import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import plus.ling.opencloudreve.core.api.ApiClientProvider
import plus.ling.opencloudreve.core.presentation.viewer.ViewerArchiveViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerAudioViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerEnvironment
import plus.ling.opencloudreve.core.presentation.viewer.ViewerImageViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerParams
import plus.ling.opencloudreve.core.presentation.viewer.ViewerPdfViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerTextViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerVideoViewModel
import plus.ling.opencloudreve.core.presentation.viewer.ViewerWebViewModel

/**
 * 预览器 ViewModel（需要 dataModule、settingsPresentationModule 的 `WebHandoff`）。
 * 参数 `(ViewerParams)`；[ViewerWebViewModel] 为 `(ViewerParams, darkMode: Boolean)`。
 */
val viewerPresentationModule: Module = module {
    factory { (p: ViewerParams) -> ViewerImageViewModel(p, viewerEnvironment(p.accountId)) }
    factory { (p: ViewerParams) -> ViewerVideoViewModel(p, viewerEnvironment(p.accountId)) }
    factory { (p: ViewerParams) -> ViewerAudioViewModel(p, viewerEnvironment(p.accountId)) }
    factory { (p: ViewerParams) -> ViewerPdfViewModel(p, viewerEnvironment(p.accountId)) }
    factory { (p: ViewerParams) -> ViewerTextViewModel(p, viewerEnvironment(p.accountId)) }
    factory { (p: ViewerParams) -> ViewerArchiveViewModel(p, viewerEnvironment(p.accountId)) }
    factory { (p: ViewerParams, darkMode: Boolean) -> ViewerWebViewModel(p, darkMode, viewerEnvironment(p.accountId), get()) }
}

private fun Scope.viewerEnvironment(accountId: String): ViewerEnvironment {
    val d = get<DataRepositories>()
    return ViewerEnvironment(d.files(accountId), d.site, d.userSettings(accountId), get<ApiClientProvider>())
}
