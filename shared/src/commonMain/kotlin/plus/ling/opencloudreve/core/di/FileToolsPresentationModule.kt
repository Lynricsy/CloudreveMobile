package plus.ling.opencloudreve.core.di

import org.koin.dsl.module
import plus.ling.opencloudreve.core.auth.AccountRepository
import plus.ling.opencloudreve.core.model.StoragePolicy
import plus.ling.opencloudreve.core.presentation.files.DirectLinksViewModel
import plus.ling.opencloudreve.core.presentation.files.MetadataEditViewModel
import plus.ling.opencloudreve.core.presentation.files.VersionsViewModel
import plus.ling.opencloudreve.core.presentation.share.ShareEditViewModel
import plus.ling.opencloudreve.core.presentation.share.ShareListViewModel
import plus.ling.opencloudreve.core.presentation.share.ShareVisitViewModel
import plus.ling.opencloudreve.core.presentation.transfer.TransferListViewModel
import plus.ling.opencloudreve.core.presentation.transfer.UploadPickerViewModel
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskMode
import plus.ling.opencloudreve.core.presentation.workflow.ArchiveTaskViewModel
import plus.ling.opencloudreve.core.presentation.workflow.RemoteDownloadCreateViewModel
import plus.ling.opencloudreve.core.presentation.workflow.WorkflowListViewModel
import plus.ling.opencloudreve.core.transfer.TransferManager

/** [UploadPickerViewModel] 参数（含可空项，用一个对象传给 `parametersOf`） */
data class UploadPickerParams(val accountId: String, val targetUri: String, val policy: StoragePolicy? = null)

/** [ShareEditViewModel] 参数：新建给 [fileUri]，编辑给 [shareId] */
data class ShareEditParams(val accountId: String, val fileUri: String? = null, val shareId: String? = null)

/** [RemoteDownloadCreateViewModel] 参数 */
data class RemoteDownloadParams(val accountId: String, val dstUri: String, val torrentUri: String? = null)

/**
 * 文件周边 ViewModel（传输、分享、任务、版本、元数据、直链）。需要 dataModule、transferModule、authModule。
 *
 * `parametersOf` 顺序：
 * - [TransferListViewModel]、[ShareListViewModel]：`(accountId)`
 * - [UploadPickerViewModel]：`(UploadPickerParams)`
 * - [ShareEditViewModel]：`(ShareEditParams)`
 * - [ShareVisitViewModel]：`(accountId, link)`
 * - [WorkflowListViewModel]：`(accountId, initialCategory)`
 * - [RemoteDownloadCreateViewModel]：`(RemoteDownloadParams)`
 * - [ArchiveTaskViewModel]：`(accountId, mode: ArchiveTaskMode, srcUris: List<String>, dstUri, fileMask: List<String>)`
 * - [VersionsViewModel]：`(accountId, uri)`
 * - [MetadataEditViewModel]、[DirectLinksViewModel]：`(accountId, uris: List<String>)`
 */
val fileToolsPresentationModule = module {
    factory { (accountId: String) -> TransferListViewModel(accountId, get<TransferManager>()) }
    factory { (p: UploadPickerParams) ->
        UploadPickerViewModel(p.accountId, p.targetUri, p.policy, get<TransferManager>(), get<DataRepositories>().files(p.accountId))
    }
    factory { (accountId: String) -> ShareListViewModel(accountId, get<DataRepositories>().shares(accountId)) }
    factory { (p: ShareEditParams) ->
        val d = get<DataRepositories>()
        ShareEditViewModel(p.accountId, p.fileUri, p.shareId, d.shares(p.accountId), d.files(p.accountId))
    }
    factory { (accountId: String, link: String) ->
        val d = get<DataRepositories>()
        ShareVisitViewModel(
            accountId,
            link,
            get<AccountRepository>().account(accountId)?.siteUrl,
            d.shares(accountId),
            d.files(accountId),
            d.userSettings(accountId),
        )
    }
    factory { (accountId: String, initialCategory: String) ->
        WorkflowListViewModel(accountId, initialCategory, get<DataRepositories>().workflows(accountId))
    }
    factory { (p: RemoteDownloadParams) ->
        val d = get<DataRepositories>()
        RemoteDownloadCreateViewModel(p.accountId, p.dstUri, p.torrentUri, d.workflows(p.accountId), d.userSettings(p.accountId))
    }
    factory { (accountId: String, mode: ArchiveTaskMode, srcUris: List<String>, dstUri: String, fileMask: List<String>) ->
        val d = get<DataRepositories>()
        ArchiveTaskViewModel(accountId, mode, srcUris, dstUri, fileMask, d.workflows(accountId), d.userSettings(accountId))
    }
    factory { (accountId: String, uri: String) ->
        val d = get<DataRepositories>()
        VersionsViewModel(accountId, uri, d.files(accountId), d.userSettings(accountId))
    }
    factory { (accountId: String, uris: List<String>) ->
        val d = get<DataRepositories>()
        MetadataEditViewModel(accountId, uris, d.files(accountId), d.site, d.userSettings(accountId))
    }
    factory { (accountId: String, uris: List<String>) ->
        val d = get<DataRepositories>()
        DirectLinksViewModel(accountId, uris, d.files(accountId), d.userSettings(accountId))
    }
}
